import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Play, Pause, Scissors, Copy, Trash2, Undo2, Redo2, Magnet, ZoomIn, ZoomOut,
  Plus, SkipBack, Info, FolderOpen, Upload, FileVideo, AudioLines, Film,
} from 'lucide-react'
import { message } from 'antd'
import Page from '../components/Page'
import Timeline from '../editor/Timeline'
import { formatTimecode, useEditor, TIMELINE_MAX } from '../editor/model'
import { useI18n } from '../i18n'
import { pickMedia, renderApi } from '../api/render'
import { analyzeApi } from '../api/analyze'
import { useRuntime } from '../store/runtime'
import { wsClient } from '../api/websocket'

const TOOL_NOTE: Record<string, [en: string, fa: string]> = {
  bgremove: ['Background removal arrives with the render engine.', 'حذف پس‌زمینه بعد از اتصال موتور رندر فعال می‌شود.'],
  enhance: ['Enhancement arrives with the render engine.', 'ارتقای کیفیت بعد از اتصال موتور رندر فعال می‌شود.'],
  titles: ['Title templates will land on the text lane.', 'قالب‌های تیتراژ روی لایه‌ی متن اضافه خواهند شد.'],
  music: ['Mixing and ducking apply to the audio lane.', 'میکس و داکینگ صدا روی لایه‌ی صدا اعمال می‌شود.'],
}

export default function Studio() {
  const [params] = useSearchParams()
  const { t, lang } = useI18n()
  const note = params.get('tool') ? TOOL_NOTE[params.get('tool')!]?.[lang === 'fa' ? 1 : 0] : undefined

  const {
    playing, playhead, pxPerSecond, snapping, selectedId, clips, tracks, past, future,
    togglePlay, setPlayhead, setZoom, toggleSnapping, splitAtPlayhead,
    removeSelected, duplicateSelected, undo, redo, addTrack, addClip,
    keepRanges, splitAtSourceTimes,
  } = useEditor()

  const [analysing, setAnalysing] = useState<'silence' | 'scenes' | null>(null)

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
  const [lastOutput, setLastOutput] = useState<string | null>(null)

  /** Import real media: OS picker in the desktop app, manual path in the browser. */
  const importMedia = async () => {
    const picked = await pickMedia()
    const paths =
      picked ??
      (() => {
        const manual = window.prompt(t('Absolute path of a video or audio file', 'مسیر کامل فایل ویدیو یا صدا'))
        return manual ? [manual] : []
      })()
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
          src: info.path,
          label: path.split(/[\\/]/).pop() ?? 'clip',
          color: info.has_video ? '#6366F1' : '#10B981',
        })
      } catch (err) {
        message.error(
          t('Could not read ', 'خواندن فایل ممکن نشد ') + path + ': ' + (err as Error).message
        )
      }
    }
  }

  /** Export: hands the edit model to the backend compositor. */
  const exportTimeline = async () => {
    const withMedia = clips.filter((c) => c.src)
    if (withMedia.length === 0) {
      message.warning(t('Import media into the timeline first.', 'اول یک فایل به تایم‌لاین اضافه کن.'))
      return
    }
    setExporting(true)
    const { upsertTask, patchTask } = useRuntime.getState()
    try {
      const state = await renderApi.start('timeline', {
        tracks,
        clips: withMedia,
        width: 1080,
        height: 1920,
        fps: 30,
      })
      upsertTask({
        id: state.id,
        kind: 'export',
        label: t('Exporting timeline', 'خروجی گرفتن از تایم‌لاین'),
        stage: t('Rendering', 'رندر'),
        progress: 0,
        status: 'running',
        route: '/studio',
      })

      const unsubscribe = wsClient.onEvent((event) => {
        const e = event as unknown as { type: string; render_id?: string; progress?: number; output?: string; error?: string }
        if (e.render_id !== state.id) return
        if (e.type === 'render:progress') patchTask(state.id, { progress: e.progress ?? 0 })
        if (e.type === 'render:done') {
          patchTask(state.id, { progress: 100, status: 'done', stage: t('Ready', 'آماده') })
          setLastOutput(e.output ?? null)
          setExporting(false)
          message.success(t('Export finished', 'خروجی آماده شد'))
          unsubscribe()
        }
        if (e.type === 'render:failed') {
          patchTask(state.id, { status: 'failed', error: e.error })
          setExporting(false)
          message.error(e.error ?? t('Export failed', 'خروجی گرفتن ناموفق بود'))
          unsubscribe()
        }
      })
    } catch (err) {
      setExporting(false)
      const detail = (err as { response?: { data?: { detail?: string } } }).response?.data?.detail
      message.error(detail ?? (err as Error).message)
    }
  }

  // playback clock — advances the playhead until the last clip ends
  const raf = useRef<number>()
  useEffect(() => {
    if (!playing) return
    let last = performance.now()
    const end = Math.max(0, ...clips.map((c) => c.start + c.duration))
    const tick = (now: number) => {
      const dt = (now - last) / 1000
      last = now
      const next = useEditor.getState().playhead + dt
      if (next >= Math.min(end, TIMELINE_MAX)) {
        setPlayhead(end)
        togglePlay(false)
        return
      }
      setPlayhead(next)
      raf.current = requestAnimationFrame(tick)
    }
    raf.current = requestAnimationFrame(tick)
    return () => {
      if (raf.current) cancelAnimationFrame(raf.current)
    }
  }, [playing, clips, setPlayhead, togglePlay])

  // editor keyboard shortcuts
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return
      const meta = e.ctrlKey || e.metaKey
      if (meta && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); return }
      if (meta && e.key.toLowerCase() === 'd') { e.preventDefault(); duplicateSelected(); return }
      switch (e.key) {
        case ' ': e.preventDefault(); togglePlay(); break
        case 's': case 'S': splitAtPlayhead(); break
        case 'Delete': case 'Backspace': removeSelected(); break
        case 'Home': setPlayhead(0); break
        case 'ArrowRight': setPlayhead(playhead + (e.shiftKey ? 1 : 1 / 30)); break
        case 'ArrowLeft': setPlayhead(playhead - (e.shiftKey ? 1 : 1 / 30)); break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [duplicateSelected, playhead, redo, removeSelected, setPlayhead, splitAtPlayhead, togglePlay, undo])

  return (
    <Page
      title={t('Editor', 'میز تدوین')}
      subtitle={t('Multi-track timeline — cut, move and trim', 'تایم‌لاین چندلایه — برش، جابه‌جایی و تریم')}
      width="lg"
    >
      <div className="ed">
        <div className="ed__stage">
          <div className="ed__preview">
            <div className="ed__preview-box">
              <span className="ed__preview-hint">{t('Preview', 'پیش‌نمایش')}</span>
              <strong className="ed__tc">{formatTimecode(playhead, true)}</strong>
            </div>
          </div>

          <aside className="ed__inspector">
            <h4>{t('Properties', 'مشخصات')}</h4>
            {selectedId ? (
              (() => {
                const clip = clips.find((c) => c.id === selectedId)!
                return (
                  <>
                    <div className="ce-kv"><span>{t('Name', 'نام')}</span><strong>{clip.label}</strong></div>
                    <div className="ce-kv"><span>{t('Start', 'شروع')}</span><strong className="ce-num" dir="ltr">{formatTimecode(clip.start, true)}</strong></div>
                    <div className="ce-kv"><span>{t('Duration', 'مدت')}</span><strong className="ce-num" dir="ltr">{formatTimecode(clip.duration, true)}</strong></div>
                    <div className="ce-kv"><span>{t('In point', 'نقطه ورود')}</span><strong className="ce-num" dir="ltr">{formatTimecode(clip.offset, true)}</strong></div>
                  </>
                )
              })()
            ) : (
              <p className="ce-hint">{t('Select a clip to see its properties.', 'یک کلیپ را انتخاب کن تا مشخصاتش اینجا بیاید.')}</p>
            )}
          </aside>
        </div>

        <div className="ed__toolbar">
          <div className="ed__group">
            <button className="ed__btn" onClick={() => setPlayhead(0)} title={t('Back to start', 'برگشت به ابتدا')}><SkipBack size={16} /></button>
            <button className="ed__btn ed__btn--primary" onClick={() => togglePlay()} title={t('Play / pause (Space)', 'پخش / مکث (Space)')}>
              {playing ? <Pause size={16} /> : <Play size={16} />}
            </button>
            <span className="ed__tc ed__tc--sm" dir="ltr">{formatTimecode(playhead, true)}</span>
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
            <button className="ed__btn" onClick={() => setZoom(pxPerSecond / 1.35)} title={t('Zoom out', 'کوچک‌نمایی')}><ZoomOut size={16} /></button>
            <input
              className="ed__zoom" type="range" min={8} max={220} value={pxPerSecond}
              onChange={(e) => setZoom(Number(e.target.value))} aria-label={t('Zoom', 'بزرگ‌نمایی')}
            />
            <button className="ed__btn" onClick={() => setZoom(pxPerSecond * 1.35)} title={t('Zoom in', 'بزرگ‌نمایی')}><ZoomIn size={16} /></button>
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

        <Timeline />

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
      </div>
    </Page>
  )
}
