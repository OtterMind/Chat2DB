import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Group, Panel, Separator } from 'react-resizable-panels'
import {
  Play, Pause, Scissors, Copy, Trash2, Undo2, Redo2, Magnet,
  Plus, SkipBack, Info, FolderOpen, Upload, FileVideo, AudioLines, Film, Search, Circle, Mic, Activity,
} from 'lucide-react'
import { Checkbox, Input, Modal, Radio, Select, message } from 'antd'
import Page from '../components/Page'
import Timeline from '../editor/Timeline'
import PreviewMonitor from '../editor/PreviewMonitor'
import EditorToolbar from '../editor/EditorToolbar'
import AssistantButton from '../editor/AssistantButton'
import ProjectAutosave from '../editor/ProjectAutosave'
import MediaBin from '../editor/MediaBin'
import CommandPalette, { PALETTE_ICONS, type PaletteAction } from '../editor/CommandPalette'
import BrainBar from '../editor/BrainBar'
import RecorderModal from '../editor/RecorderModal'
import { formatTimecode, useEditor, TIMELINE_MAX } from '../editor/model'
import { useI18n } from '../i18n'
import { pickMedia, proxyApi, renderApi, saveDialog, type Quality } from '../api/render'
import { analyzeApi } from '../api/analyze'
import { useRuntime } from '../store/runtime'
import { wsClient } from '../api/websocket'
import { backendOrigin } from '../api/runtime'

const TOOL_NOTE: Record<string, [en: string, fa: string]> = {
  bgremove: ['Background removal arrives with the render engine.', 'حذف پس‌زمینه بعد از اتصال موتور رندر فعال می‌شود.'],
  enhance: ['Enhancement arrives with the render engine.', 'ارتقای کیفیت بعد از اتصال موتور رندر فعال می‌شود.'],
  titles: ['Title templates will land on the text lane.', 'قالب‌های تیتراژ روی لایه‌ی متن اضافه خواهند شد.'],
  music: ['Mixing and ducking apply to the audio lane.', 'میکس و داکینگ صدا روی لایه‌ی صدا اعمال می‌شود.'],
}

/** One picker at a time, from any door (entry effect, toolbar, palette, bin).
    A second call while the first dialog is open is exactly the "Import fired
    twice on entry" complaint — with this flag it is impossible by construction. */
let pickerBusy = false

export default function Studio() {
  const [params] = useSearchParams()
  const { t, lang } = useI18n()
  const note = params.get('tool') ? TOOL_NOTE[params.get('tool')!]?.[lang === 'fa' ? 1 : 0] : undefined

  const {
    playing, snapping, selectedId, clips, tracks, past, future,
    togglePlay, setPlayhead, toggleSnapping, splitAtPlayhead,
    removeSelected, duplicateSelected, undo, redo, addTrack, addClip,
    keepRanges, splitAtSourceTimes, transitions,
  } = useEditor()

  const [analysing, setAnalysing] = useState<'silence' | 'scenes' | 'beats' | null>(null)
  const [cmdOpen, setCmdOpen] = useState(false)
  const [binOpen, setBinOpen] = useState(true)
  const [scOpen, setScOpen] = useState(false)

  /** Ctrl+K opens the command palette — the keyboard-first door to every tool.
   *  "?" opens the shortcuts cheat-sheet. */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setCmdOpen((v) => !v)
      }
      if (e.key === '?' ) setScOpen((v) => !v)
      if (e.key === 'Escape') { setCmdOpen(false); setScOpen(false) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  /**
   * Beat detection.
   *
   * The grid is drawn on the ruler and every cut can then land on the music;
   * "cut on beat" splits the selected clip at every beat inside it.
   */
  const detectBeats = async () => {
    const source =
      clips.find((c) => c.id === selectedId && c.src) ??
      clips.filter((c) => c.src).sort((a, b) => a.start - b.start)[0]
    if (!source?.src) {
      message.warning(t('Import media first.', 'اول یک فایل اضافه کن.'))
      return
    }
    setAnalysing('beats')
    try {
      const result = await analyzeApi.beats(source.src)
      if (!result.beats.length) {
        message.info(t('No steady beat found.', 'ضرب منظمی پیدا نشد.'))
        return
      }
      // Beat times are inside the source; place them on the timeline.
      const shift = source.start - source.offset
      useEditor.getState().setBeats(result.beats.map((b) => b + shift), result.bpm)
      message.success(
        t(
          `${result.bpm.toFixed(0)} BPM — ${result.beats.length} beats on the ruler`,
          `${result.bpm.toFixed(0)} ضرب در دقیقه — ${result.beats.length} ضرب روی خط‌کش`
        )
      )
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setAnalysing(null)
    }
  }

  const cutOnBeat = () => {
    const state = useEditor.getState()
    if (state.beats.length === 0) {
      message.warning(t('Find the beat first.', 'اول ضرب را پیدا کن.'))
      return
    }
    const target = state.clips.find((c) => c.id === state.selectedId) ?? state.clips[0]
    if (!target) return
    const cuts = state.splitAtBeats(target.id)
    message.success(
      cuts > 0
        ? t(`Cut into ${cuts + 1} pieces on the beat`, `روی ضرب به ${cuts + 1} تکه بریده شد`)
        : t('No beat inside this clip', 'ضربی داخل این کلیپ نیست')
    )
  }

  /*
   * Keyboard shortcuts.
   *
   * The buttons advertised them ("Delete", "S", "Ctrl+Z") but nothing listened,
   * so every one of them was a lie. Typing in a field or a modal must still be
   * typing, which is what the editable check is for.
   */
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      const typing =
        target?.isContentEditable ||
        ['INPUT', 'TEXTAREA', 'SELECT'].includes(target?.tagName ?? '') ||
        Boolean(target?.closest('.ant-modal'))
      if (typing) return

      const meta = event.ctrlKey || event.metaKey
      const key = event.key

      if (meta && key.toLowerCase() === 'z') {
        event.preventDefault()
        if (event.shiftKey) redo()
        else undo()
        return
      }
      if (meta && key.toLowerCase() === 'y') {
        event.preventDefault()
        redo()
        return
      }
      if (meta && key.toLowerCase() === 'd') {
        event.preventDefault()
        duplicateSelected()
        return
      }
      if (meta) return

      if (key === ' ') {
        event.preventDefault()
        togglePlay()
      } else if (key.toLowerCase() === 'k') {
        // The J/K/L transport: K pauses; J/L shuttle back/forward. A video editor
        // is driven from the keyboard, and these three are its alphabet.
        event.preventDefault()
        useEditor.getState().togglePlay(false)
      } else if (key.toLowerCase() === 'l') {
        event.preventDefault()
        if (!useEditor.getState().playing) useEditor.getState().togglePlay(true)
        else setPlayhead(useEditor.getState().playhead + 1)
      } else if (key.toLowerCase() === 'j') {
        event.preventDefault()
        useEditor.getState().togglePlay(false)
        setPlayhead(useEditor.getState().playhead - 1)
      } else if (key === ',' || key === '.') {
        // One frame at a time, the way colourists scrub.
        event.preventDefault()
        setPlayhead(useEditor.getState().playhead + (key === '.' ? 1 : -1) / 30)
      } else if (key === 'Delete' || key === 'Backspace') {
        if (!useEditor.getState().selectedId) return
        event.preventDefault()
        removeSelected()
      } else if (key.toLowerCase() === 's') {
        event.preventDefault()
        splitAtPlayhead()
      } else if (key === 'ArrowLeft') {
        event.preventDefault()
        setPlayhead(useEditor.getState().playhead - (event.shiftKey ? 1 : 1 / 30))
      } else if (key === 'ArrowRight') {
        event.preventDefault()
        setPlayhead(useEditor.getState().playhead + (event.shiftKey ? 1 : 1 / 30))
      } else if (key === 'Home') {
        event.preventDefault()
        setPlayhead(0)
      } else if (key === 'End') {
        event.preventDefault()
        setPlayhead(useEditor.getState().contentEnd())
      }
    }

    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [duplicateSelected, redo, removeSelected, setPlayhead, splitAtPlayhead, togglePlay, undo])

  /** The clip an automatic edit should act on. */
  const targetClip = clips.find((c) => c.id === selectedId && c.src) ?? clips.find((c) => c.src)

  const removeSilence = async () => {
    if (!targetClip?.src) {
      message.warning(t('Import media first.', 'اول یک فایل اضافه کن.'))
      return
    }
    setAnalysing('silence')
    try {
      const result = await analyzeApi.silence(targetClip.src)
      if (result.speech.length === 0) {
        message.info(t('No speech detected.', 'گفتاری پیدا نشد.'))
        return
      }
      const parts = keepRanges(targetClip.id, result.speech)
      const saved = result.silences.reduce((sum, r) => sum + (r.end - r.start), 0)
      message.success(
        t(
          `Removed ${result.silences.length} silent gaps (${saved.toFixed(1)}s) — ${parts} segments left`,
          `${result.silences.length} سکوت حذف شد (${saved.toFixed(1)} ثانیه) — ${parts} قطعه باقی ماند`
        )
      )
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setAnalysing(null)
    }
  }

  const splitScenes = async () => {
    if (!targetClip?.src) {
      message.warning(t('Import media first.', 'اول یک فایل اضافه کن.'))
      return
    }
    setAnalysing('scenes')
    try {
      const { scenes } = await analyzeApi.scenes(targetClip.src)
      const cuts = splitAtSourceTimes(targetClip.id, scenes)
      message.success(
        cuts > 0
          ? t(`Split into ${cuts + 1} shots`, `به ${cuts + 1} نما تقسیم شد`)
          : t('No scene changes found', 'تغییر نما پیدا نشد')
      )
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setAnalysing(null)
    }
  }

  const [exporting, setExporting] = useState(false)
  const [recOpen, setRecOpen] = useState(false)
  const [dirOpen, setDirOpen] = useState(false)
  const [lastOutput, setLastOutput] = useState<string | null>(null)
  const importedOnEntry = useRef(false)

  /**
   * Editing proxies.
   *
   * A 4K phone video makes every seek decode a huge frame, so the timeline feels
   * broken even though nothing is wrong. The backend builds a small copy in a
   * worker thread; this polls until it lands and then points the preview at it.
   */
  const requestProxy = async (path: string) => {
    try {
      let state = await proxyApi.start(path)
      if (state.status === 'skipped') return
      for (let attempt = 0; attempt < 600 && state.status === 'building'; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 1000))
        state = await proxyApi.status(path)
      }
      if (state.status === 'ready' && state.proxy) {
        useEditor.getState().setProxy(path, state.proxy)
        message.success(t('Smooth preview ready', 'پیش‌نمایش روان آماده شد'))
      }
    } catch {
      /* the original still plays; a proxy is an optimisation, not a requirement */
    }
  }

  /** Import real media: OS picker in the desktop app, typed path in the browser. */
  const importMedia = async () => {
    if (pickerBusy) return
    pickerBusy = true
    try {
    let paths: string[] = []
    try {
      const picker = pickMedia()
      if (picker) {
        paths = await picker
      } else {
        // Electron blocks window.prompt(), so the browser fallback asks in-app.
        const manual = await askForPath(t)
        paths = manual ? [manual] : []
      }
    } catch (err) {
      message.error(t('Could not open the file picker: ', 'باز کردن پنجره انتخاب فایل ممکن نشد: ') + (err as Error).message)
      return
    }
    if (!paths.length) return

    for (const path of paths) {
      try {
        const info = await renderApi.probe(path)
        const lane = info.has_video
          ? tracks.find((x) => x.kind === 'video')
          : tracks.find((x) => x.kind === 'audio')
        const laneClips = clips.filter((c) => c.trackId === lane?.id)
        const start = laneClips.reduce((acc, c) => Math.max(acc, c.start + c.duration), 0)
        addClip({
          trackId: lane?.id ?? 'v1',
          start,
          duration: Math.max(0.5, info.duration),
          offset: 0,
          sourceDuration: Math.max(0.5, info.duration),
          width: info.width || undefined,
          height: info.height || undefined,
          src: info.path,
          label: path.split(/[\\/]/).pop() ?? 'clip',
          /* hue-coded lanes: video blue, audio emerald, text violet */
          color: info.has_video ? '#3B82F6' : '#10B981',
        })
        // Big footage gets a 720p editing proxy in the background; the preview
        // switches to it when it is ready and the export never uses it.
        void requestProxy(info.path)
      } catch (err) {
        const offline = /network error/i.test((err as Error).message)
        message.error(
          offline
            ? t(
                'The local processing service is not running — see Diagnostics.',
                'سرویس پردازش محلی اجرا نمی‌شود — به بخش عیب‌یابی برو.'
              )
            : t('Could not read ', 'خواندن فایل ممکن نشد ') + path + ': ' + (err as Error).message
        )
      }
    }
    } finally {
      pickerBusy = false
    }
  }

  /*
   * "New video" on the home screen lands here with ?import=1, so the very first
   * thing the user sees is the file picker instead of an empty timeline.
   */
  useEffect(() => {
    if (params.get('import') !== '1' || importedOnEntry.current) return
    importedOnEntry.current = true
    void importMedia()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params])

  /** Export: ask for format and destination, then hand the model to the compositor. */
  const exportTimeline = async () => {
    const withMedia = clips.filter((c) => c.src)
    if (withMedia.length === 0) {
      message.warning(t('Import media into the timeline first.', 'اول یک فایل به تایم‌لاین اضافه کن.'))
      return
    }

    // The export starts from the shape the user has been editing in.
    const settings = await askExportSettings(t, formatForRatio(useEditor.getState().canvasRatio()))
    if (!settings) return

    const suggested = `timeline-${settings.width}x${settings.height}.mp4`
    const chosen = await (saveDialog(suggested) ?? Promise.resolve(null))

    setExporting(true)
    const { upsertTask, patchTask } = useRuntime.getState()
    let renderId: string | null = null

    try {
      const state = await renderApi.start(
        'timeline',
        {
          tracks,
          clips: withMedia,
          transitions,
          width: settings.width,
          height: settings.height,
          fps: settings.fps,
          progressBar: settings.progressBar,
          brandText: settings.brandText,
        },
        { quality: settings.quality, output: chosen }
      )
      // Package B: the export queue — the same edit, other platforms' shapes.
      for (const extra of settings.extras) {
        void renderApi.start(
          'timeline',
          {
            tracks, clips: withMedia, transitions,
            width: extra.width, height: extra.height, fps: settings.fps,
            progressBar: settings.progressBar, brandText: settings.brandText,
          },
          { quality: settings.quality, output: null }
        )
      }
      renderId = state.id

      upsertTask({
        id: state.id,
        kind: 'export',
        label: t('Exporting timeline', 'خروجی گرفتن از تایم‌لاین'),
        stage: t('Rendering', 'رندر'),
        progress: 0,
        status: 'running',
        route: '/studio',
      })

      const finish = (ok: boolean, payload: { output?: string; error?: string }) => {
        if (ok) {
          patchTask(state.id, { progress: 100, status: 'done', stage: t('Ready', 'آماده') })
          setLastOutput(payload.output ?? null)
          message.success(t('Export finished', 'خروجی آماده شد'))
        } else {
          patchTask(state.id, { status: 'failed', error: payload.error })
          message.error(payload.error ?? t('Export failed', 'خروجی گرفتن ناموفق بود'))
        }
        setExporting(false)
        unsubscribe()
        window.clearInterval(poll)
      }

      const unsubscribe = wsClient.onEvent((event) => {
        const e = event as unknown as {
          type: string
          render_id?: string
          progress?: number
          output?: string
          error?: string
        }
        if (e.render_id !== state.id) return
        if (e.type === 'render:progress') patchTask(state.id, { progress: e.progress ?? 0 })
        if (e.type === 'render:done') finish(true, e)
        if (e.type === 'render:failed') finish(false, e)
      })

      // The socket alone is not enough: a render that fails in the first
      // milliseconds broadcasts before this screen has subscribed, which used to
      // leave the task pinned at 0% forever. Polling closes that race.
      const poll = window.setInterval(async () => {
        if (!renderId) return
        try {
          const status = await renderApi.get(renderId)
          if (status.status === 'running') {
            patchTask(renderId, { progress: status.progress })
          } else if (status.status === 'done') {
            finish(true, { output: status.output })
          } else if (status.status === 'failed') {
            finish(false, { error: status.error ?? undefined })
          }
        } catch {
          /* transient: the next tick retries */
        }
      }, 1500)
    } catch (err) {
      setExporting(false)
      const detail = (err as { response?: { data?: { detail?: string } } }).response?.data?.detail
      message.error(detail ?? (err as Error).message)
      if (renderId) useRuntime.getState().patchTask(renderId, { status: 'failed' })
    }
  }

  /** The palette's verbs — the exact handlers the toolbar uses, nothing mocked. */
  const paletteActions: PaletteAction[] = [
    { id: 'import', icon: <PALETTE_ICONS.FolderOpen size={15} />, label: ['Import media', 'افزودن رسانه'], run: importMedia },
    { id: 'export', icon: <PALETTE_ICONS.Upload size={15} />, label: ['Export', 'خروجی'], run: () => void exportTimeline() },
    { id: 'split', icon: <PALETTE_ICONS.Scissors size={15} />, label: ['Split at playhead', 'برش در محل پلی‌هد'], run: splitAtPlayhead },
    { id: 'dup', icon: <PALETTE_ICONS.Copy size={15} />, label: ['Duplicate clip', 'تکثیر کلیپ'], run: duplicateSelected },
    { id: 'del', icon: <PALETTE_ICONS.Trash2 size={15} />, label: ['Delete clip', 'حذف کلیپ'], run: removeSelected },
    { id: 'undo', icon: <PALETTE_ICONS.Undo2 size={15} />, label: ['Undo', 'واگرد'], run: undo },
    { id: 'redo', icon: <PALETTE_ICONS.Redo2 size={15} />, label: ['Redo', 'ازنو'], run: redo },
    { id: 'beats', icon: <PALETTE_ICONS.AudioWaveform size={15} />, label: ['Find the beat', 'یافتن ضرب'], run: () => void detectBeats() },
    { id: 'cutbeat', icon: <PALETTE_ICONS.AudioWaveform size={15} />, label: ['Cut on beat', 'برش روی ضرب'], run: () => void cutOnBeat() },
    { id: 'scenes', icon: <PALETTE_ICONS.Film size={15} />, label: ['Split scenes', 'تقسیم نما'], run: () => void splitScenes() },
    { id: 'silence', icon: <PALETTE_ICONS.VolumeX size={15} />, label: ['Remove silence', 'حذف سکوت'], run: () => void removeSilence() },
    { id: 'v', icon: <PALETTE_ICONS.Film size={15} />, label: ['Add video lane', 'لایه ویدیو'], run: () => addTrack('video') },
    { id: 'a', icon: <PALETTE_ICONS.Music4 size={15} />, label: ['Add audio lane', 'لایه صدا'], run: () => addTrack('audio') },
    { id: 't', icon: <PALETTE_ICONS.Type size={15} />, label: ['Add text lane', 'لایه متن'], run: () => addTrack('text') },
    { id: 'bin', icon: <PALETTE_ICONS.FolderOpen size={15} />, label: ['Toggle library', 'نمایش/پنهان کردن کتابخانه'], run: () => setBinOpen((v) => !v) },
  ]

  return (
    <Page
      title={t('Editor', 'میز تدوین')}
      subtitle={t('Multi-track timeline — cut, move and trim', 'تایم‌لاین چندلایه — برش، جابه‌جایی و تریم')}
      width="lg"
      bare
    >
      <div className="ed">
        <ProjectAutosave />

        {/* 0.9.31 layout: library | stage+inspector, and a resizable split
            between the working surface and the timeline — the panel grammar
            the advisors' mockups use, without touching the edit model. */}
        <div className="ed__layout">
        <Group orientation="vertical">
          <Panel defaultSize="60" minSize="38">
          <div className="ed__body">
            {binOpen && <MediaBin onImport={importMedia} />}
            <div className="ed__main">
        {/* The monitor is the stage: the properties panel it used to share the
            row with said nothing the timeline does not already show. */}
        <div className="ed__stage">
          <PreviewMonitor />
        </div>

        <div className="ed__toolbar">
          <div className="ed__group">
            <button className="ed__btn" onClick={() => setPlayhead(0)} title={t('Back to start', 'برگشت به ابتدا')}><SkipBack size={16} /></button>
            <button className="ed__btn ed__btn--primary" onClick={() => togglePlay()} title={t('Play / pause (Space)', 'پخش / مکث (Space)')}>
              {playing ? <Pause size={16} /> : <Play size={16} />}
            </button>
            <TransportClock />
          </div>

          <div className="ed__group">
            <button className="ed__btn" onClick={splitAtPlayhead} title={t('Split at playhead (S)', 'برش در محل پلی‌هد (S)')}><Scissors size={16} /></button>
            <button className="ed__btn" onClick={duplicateSelected} disabled={!selectedId} title={t('Duplicate (Ctrl+D)', 'تکثیر (Ctrl+D)')}><Copy size={16} /></button>
            <button className="ed__btn" onClick={removeSelected} disabled={!selectedId} title={t('Delete (Delete)', 'حذف (Delete)')}><Trash2 size={16} /></button>
          </div>

          <div className="ed__group">
            <button className="ed__btn" onClick={undo} disabled={past.length === 0} title={t('Undo (Ctrl+Z)', 'واگرد (Ctrl+Z)')}><Undo2 size={16} /></button>
            <button className="ed__btn" onClick={redo} disabled={future.length === 0} title={t('Redo (Ctrl+Shift+Z)', 'ازنو (Ctrl+Shift+Z)')}><Redo2 size={16} /></button>
            <button className={`ed__btn ${snapping ? 'is-on' : ''}`} onClick={toggleSnapping} title={t('Snapping', 'چسبندگی')}><Magnet size={16} /></button>
          </div>
          <div className="ed__group">
            <button className={`ed__btn ${binOpen ? 'is-on' : ''}`} onClick={() => setBinOpen((v) => !v)} title={t('Library', 'کتابخانه')}><FolderOpen size={16} /></button>
            <button className="ed__btn" onClick={() => setCmdOpen(true)} title={`${t('Command palette', 'منوی فرمان')} (Ctrl+K)`}><Search size={16} /></button>
            <button className="ed__btn" onClick={() => setScOpen(true)} title={`${t('Shortcuts', 'میان‌برها')} (?)`}><Info size={16} /></button>
          </div>

          <div className="ed__group">
            <button
              className="ed__btn"
              onClick={removeSilence}
              disabled={analysing !== null}
              title={t('Detect and cut silent gaps', 'یافتن و حذف سکوت‌ها')}
            >
              <AudioLines size={15} className={analysing === 'silence' ? 'ce-spin' : ''} />{' '}
              {t('Remove silence', 'حذف سکوت')}
            </button>
            <button
              className="ed__btn"
              onClick={splitScenes}
              disabled={analysing !== null}
              title={t('Split the clip at shot changes', 'برش کلیپ در محل تغییر نما')}
            >
              <Film size={15} className={analysing === 'scenes' ? 'ce-spin' : ''} />{' '}
              {t('Split scenes', 'برش نماها')}
            </button>
          </div>

          <div className="ed__group">
            <button className="ed__btn" onClick={importMedia} title={t('Import media', 'افزودن رسانه')}>
              <FolderOpen size={15} /> {t('Import', 'افزودن')}
            </button>
            <button className="ed__btn" onClick={() => setRecOpen(true)} title={t('Record screen / webcam', 'ضبط صفحه / وبکم')}><Circle size={16} /></button>
            <button className="ed__btn" onClick={() => setDirOpen(true)} title={t('Director Mode — say it, we plan it', 'حالت کارگردان — بگو، برنامه می‌ریزیم')}><Mic size={16} /></button>
            <button className="ed__btn ed__btn--primary" onClick={exportTimeline} disabled={exporting}>
              <Upload size={15} /> {exporting ? t('Exporting…', 'در حال خروجی…') : t('Export', 'خروجی')}
            </button>
          </div>

          <div className="ed__group ed__group--end">
            <button className="ed__btn" onClick={() => addTrack('video')} title={t('Video lane', 'لایه ویدیو')}>
              <Plus size={14} /> {t('Video', 'ویدیو')}
            </button>
            <button className="ed__btn" onClick={() => addTrack('audio')} title={t('Audio lane', 'لایه صدا')}>
              <Plus size={14} /> {t('Audio', 'صدا')}
            </button>
            <button className="ed__btn" onClick={() => addTrack('text')} title={t('Text lane', 'لایه متن')}>
              <Plus size={14} /> {t('Text', 'متن')}
            </button>
          </div>
        </div>

        <AssistantButton />

        <EditorToolbar
          onImport={importMedia}
          onRemoveSilence={removeSilence}
          onSplitScenes={splitScenes}
          onDetectBeats={detectBeats}
          onCutOnBeat={cutOnBeat}
        />
            </div>{/* ed__main */}
          </div>{/* ed__body */}
          </Panel>
          <Separator className="ed__rsz" />
          <Panel defaultSize="40" minSize="24" maxSize="62">
            <div className="ed__tlwrap">
        <Timeline />
            </div>
          </Panel>
        </Group>
        </div>{/* ed__layout */}

        <CommandPalette open={cmdOpen} onClose={() => setCmdOpen(false)} actions={paletteActions} />

        {lastOutput && (
          <div className="ce-note">
            <FileVideo size={16} />
            <span>
              {t('Saved to', 'ذخیره شد در')} <span className="ce-num" dir="ltr">{lastOutput}</span>
            </span>
          </div>
        )}

        <div className="ce-note">
          <Info size={16} />
          <span>
            {note ??
              t(
                'This timeline is fully functional: drag clips, trim the edges, press S to split and Ctrl+Z to undo.',
                'این تایم‌لاین کاملاً کار می‌کند: کلیپ‌ها را بکش، لبه‌ها را تریم کن، با کلید S برش بزن و با Ctrl+Z برگرد.'
              )}
          </span>
        </div>

        <BrainBar />
        <RecorderModal open={recOpen} onClose={() => setRecOpen(false)} />
        <DirectorModal open={dirOpen} onClose={() => setDirOpen(false)} />
        <PerfHud />

        <Modal open={scOpen} onCancel={() => setScOpen(false)} footer={null} width={560}
          title={t('Keyboard shortcuts', 'میان‌برهای صفحه‌کلید')}>
          <div className="sc-grid" dir="ltr">
            {([
              ['Space', t('Play / pause', 'پخش / مکث')],
              ['S', t('Split at playhead', 'برش در محل پلی‌هد')],
              ['J / K / L', t('Shuttle / prev / next', 'عقب / قبلی / بعدی')],
              [', / .', t('Step one frame', 'یک فریم جلو/عقب')],
              ['Ctrl+Z', t('Undo', 'واگرد')],
              ['Ctrl+D', t('Duplicate clip', 'تکثیر کلیپ')],
              ['Delete', t('Delete clip', 'حذف کلیپ')],
              ['Ctrl+K', t('Command palette', 'پالت فرمان')],
              ['F11', t('Fullscreen', 'تمام‌صفحه')],
              ['?', t('This sheet', 'همین برگه')],
            ] as [string, string][]).map(([k, v]) => (
              <div className="sc-row" key={k}><span className="kbd">{k}</span><span>{v}</span></div>
            ))}
          </div>
        </Modal>
      </div>
    </Page>
  )
}

/**
 * Small prompt used only in the browser preview — Electron refuses
 * window.prompt(), which previously made the import button fail in total silence.
 */
function askForPath(t: (en: string, fa: string) => string): Promise<string | null> {
  return new Promise((resolve) => {
    let value = ''
    Modal.confirm({
      title: t('Import media', 'افزودن رسانه'),
      content: (
        <Input
          dir="ltr"
          placeholder="/path/to/video.mp4"
          onChange={(e) => {
            value = e.target.value
          }}
        />
      ),
      okText: t('Import', 'افزودن'),
      cancelText: t('Cancel', 'انصراف'),
      onOk: () => resolve(value.trim() || null),
      onCancel: () => resolve(null),
    })
  })
}

interface ExportSettings {
  width: number
  height: number
  fps: number
  quality: Quality
  progressBar: boolean
  brandText: string
  extras: { width: number; height: number; id: string }[]
}

const FORMATS: { id: string; label: [string, string]; width: number; height: number }[] = [
  { id: 'vertical', label: ['9:16 — Shorts, Reels, TikTok', '۹:۱۶ — شورتس، ریلز، تیک‌تاک'], width: 1080, height: 1920 },
  { id: 'square', label: ['1:1 — Square', '۱:۱ — مربع'], width: 1080, height: 1080 },
  { id: 'portrait', label: ['4:5 — Portrait feed', '۴:۵ — پرتره'], width: 1080, height: 1350 },
  { id: 'landscape', label: ['16:9 — YouTube 1080p', '۱۶:۹ — یوتیوب ۱۰۸۰p'], width: 1920, height: 1080 },
  { id: 'landscape4k', label: ['16:9 — 4K', '۱۶:۹ — چهار کی'], width: 3840, height: 2160 },
]

/** The catalogue entry closest to the canvas the editor is showing. */
function formatForRatio(ratio: number) {
  return FORMATS.reduce((best, format) =>
    Math.abs(format.width / format.height - ratio) < Math.abs(best.width / best.height - ratio) ? format : best
  ).id
}

/** Format, quality and frame rate before anything starts encoding. */
function askExportSettings(
  t: (en: string, fa: string) => string,
  preferred = 'vertical'
): Promise<ExportSettings | null> {
  return new Promise((resolve) => {
    const initial = FORMATS.find((f) => f.id === preferred) ?? FORMATS[0]
    const state: ExportSettings = {
      width: initial.width, height: initial.height, fps: 30, quality: 'balanced',
      progressBar: localStorage.getItem('ce-brand-progress') === '1',
      brandText: localStorage.getItem('ce-brand-text') ?? '',
      extras: [],
    }
    let resolved = false
    const done = (value: ExportSettings | null) => {
      if (resolved) return
      resolved = true
      resolve(value)
    }

    Modal.confirm({
      title: t('Export settings', 'تنظیمات خروجی'),
      width: 460,
      icon: null,
      content: (
        <div className="ce-exportform">
          <label>
            <span>{t('Format', 'قالب')}</span>
            <Select
              defaultValue={initial.id}
              style={{ width: '100%' }}
              options={FORMATS.map((f) => ({ value: f.id, label: f.label[0] }))}
              onChange={(id) => {
                const format = FORMATS.find((f) => f.id === id)!
                state.width = format.width
                state.height = format.height
              }}
            />
          </label>

          <label>
            <span>{t('Quality', 'کیفیت')}</span>
            <Radio.Group
              defaultValue="balanced"
              optionType="button"
              buttonStyle="solid"
              onChange={(e) => {
                state.quality = e.target.value as Quality
              }}
              options={[
                { value: 'high', label: t('High', 'بالا') },
                { value: 'balanced', label: t('Balanced', 'متعادل') },
                { value: 'fast', label: t('Fast', 'سریع') },
              ]}
            />
          </label>

          <label>
            <span>{t('Frame rate', 'نرخ فریم')}</span>
            <Radio.Group
              defaultValue={30}
              optionType="button"
              buttonStyle="solid"
              onChange={(e) => {
                state.fps = Number(e.target.value)
              }}
              options={[
                { value: 24, label: '24' },
                { value: 30, label: '30' },
                { value: 60, label: '60' },
              ]}
            />
          </label>

          <label>
            <span>{t('Brand', 'برند')}</span>
            <Input
              defaultValue={state.brandText}
              placeholder={t('Watermark text (optional)', 'متن واترمارک (اختیاری)')}
              onChange={(e) => {
                state.brandText = e.target.value
                localStorage.setItem('ce-brand-text', e.target.value)
              }}
            />
          </label>
          <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <Checkbox
              defaultChecked={state.progressBar}
              onChange={(e) => {
                state.progressBar = e.target.checked
                localStorage.setItem('ce-brand-progress', e.target.checked ? '1' : '0')
              }}
            />
            <span>{t('Progress bar overlay', 'نوار پیشرفت روی ویدیو')}</span>
          </label>
          <label>
            <span>{t('Also export for', 'خروجی هم‌زمان برای')}</span>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {FORMATS.filter((f) => f.id !== initial.id).map((f) => (
                <Checkbox
                  key={f.id}
                  onChange={(e) => {
                    state.extras = e.target.checked
                      ? [...state.extras, { id: f.id, width: f.width, height: f.height }]
                      : state.extras.filter((x) => x.id !== f.id)
                  }}
                >
                  <span style={{ fontSize: 11 }}>{f.label[0].split(' — ')[0]}</span>
                </Checkbox>
              ))}
            </div>
          </label>
          <p className="ce-hint">
            {t(
              'You will be asked where to save the file next.',
              'در مرحله بعد محل ذخیره فایل را انتخاب می‌کنی.'
            )}
          </p>
        </div>
      ),
      okText: t('Continue', 'ادامه'),
      cancelText: t('Cancel', 'انصراف'),
      onOk: () => done(state),
      onCancel: () => done(null),
    })
  })
}


/** A9: the clock subscribes alone — playhead ticks no longer re-render Studio. */
function TransportClock() {
  const playhead = useEditor((s) => s.playhead)
  return <span className="ed__tc ed__tc--sm" dir="ltr">{formatTimecode(playhead, true)}</span>
}

/** B9: F3 — a small, honest performance HUD (FPS, WS events/s, backend ms). */
function PerfHud() {
  const [on, setOn] = useState(false)
  const [fps, setFps] = useState(0)
  const [ws, setWs] = useState(0)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'F3') { e.preventDefault(); setOn((v) => !v) } }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
  useEffect(() => {
    if (!on) return undefined
    let frames = 0; let events = 0; let raf = 0
    const tick = () => { frames++; raf = requestAnimationFrame(tick) }
    raf = requestAnimationFrame(tick)
    const off = wsClient.onEvent(() => { events++ })
    const id = window.setInterval(() => { setFps(frames); setWs(events); frames = 0; events = 0 }, 1000)
    return () => { cancelAnimationFrame(raf); window.clearInterval(id); off() }
  }, [on])
  if (!on) return null
  return (
    <div className="ce-hud mono" dir="ltr">
      <span>{fps} fps</span><span>{ws} ws/s</span><span>F3 off</span>
    </div>
  )
}

/** B1: Director Mode — say the edit; faster-whisper transcribes on-device and the
 *  assistant plans it. The plan is shown before anything touches the timeline. */
function DirectorModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n()
  const [recording, setRecording] = useState(false)
  const [out, setOut] = useState<string>('')
  const recRef = useRef<MediaRecorder | null>(null)

  const start = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const rec = new MediaRecorder(stream)
      const chunks: Blob[] = []
      rec.ondataavailable = (e) => chunks.push(e.data)
      rec.onstop = async () => {
        stream.getTracks().forEach((x) => x.stop())
        const blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' })
        const reader = new FileReader()
        reader.onload = async () => {
          const b64 = String(reader.result).split(',')[1]
          setOut(t('Thinking…', 'در حال فکر کردن…'))
          try {
            const saved = await fetch(`${backendOrigin}/api/render/recordings/save`, {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ name: `direct-${Date.now()}`, data: b64, ext: 'webm' }),
            }).then((r) => r.json())
            const tr = await fetch(`${backendOrigin}/api/captions/transcribe`, {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ path: saved.path }),
            }).then((r) => r.json())
            const said: string = tr.text || ''
            const chat = await fetch(`${backendOrigin}/api/assistant/chat`, {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ messages: [{ role: 'user', content: said || '...' }], language: 'fa' }),
            }).then((r) => r.json())
            setOut(`${t('Heard', 'شنیدم')}: «${said}»
${chat.reply ?? ''}`)
          } catch (err) {
            setOut((err as Error).message)
          }
        }
        reader.readAsDataURL(blob)
        setRecording(false)
      }
      recRef.current = rec
      rec.start()
      setRecording(true)
    } catch {
      setOut(t('Microphone needs permission here.', 'میکروفون اینجا به اجازه نیاز دارد.'))
    }
  }

  return (
    <Modal open={open} onCancel={onClose} footer={null} title={t('Director Mode', 'حالت کارگردان')}>
      <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => (recording ? recRef.current?.stop() : void start())}>
        <Mic size={14} /> {recording ? t('Stop', 'توقف') : t('Speak', 'صحبت کن')}
      </button>
      {out && <pre className="ce-hud ce-hud--static" dir="auto">{out}</pre>}
    </Modal>
  )
}
