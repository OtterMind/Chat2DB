import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Play, Pause, Scissors, Copy, Trash2, Undo2, Redo2, Magnet, ZoomIn, ZoomOut,
  Plus, SkipBack, Info, FolderOpen, Upload, FileVideo, AudioLines, Film,
} from 'lucide-react'
import { Input, Modal, Radio, Select, message } from 'antd'
import Page from '../components/Page'
import Timeline from '../editor/Timeline'
import PreviewMonitor from '../editor/PreviewMonitor'
import EditorToolbar from '../editor/EditorToolbar'
import AssistantButton from '../editor/AssistantButton'
import { formatTimecode, useEditor, TIMELINE_MAX } from '../editor/model'
import { useI18n } from '../i18n'
import { pickMedia, renderApi, saveDialog, type Quality } from '../api/render'
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
    keepRanges, splitAtSourceTimes, transitions,
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

  /** Import real media: OS picker in the desktop app, typed path in the browser. */
  const importMedia = async () => {
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
          src: info.path,
          label: path.split(/[\\/]/).pop() ?? 'clip',
          color: info.has_video ? '#6366F1' : '#10B981',
        })
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
  }

  /** Export: ask for format and destination, then hand the model to the compositor. */
  const exportTimeline = async () => {
    const withMedia = clips.filter((c) => c.src)
    if (withMedia.length === 0) {
      message.warning(t('Import media into the timeline first.', 'اول یک فایل به تایم‌لاین اضافه کن.'))
      return
    }

    const settings = await askExportSettings(t)
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
        },
        { quality: settings.quality, output: chosen }
      )
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

  return (
    <Page
      title={t('Editor', 'میز تدوین')}
      subtitle={t('Multi-track timeline — cut, move and trim', 'تایم‌لاین چندلایه — برش، جابه‌جایی و تریم')}
      width="lg"
    >
      <div className="ed">
        <div className="ed__stage">
          <PreviewMonitor />

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

        <AssistantButton />

        <EditorToolbar onImport={importMedia} />

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
}

const FORMATS: { id: string; label: [string, string]; width: number; height: number }[] = [
  { id: 'vertical', label: ['9:16 — Shorts, Reels, TikTok', '۹:۱۶ — شورتس، ریلز، تیک‌تاک'], width: 1080, height: 1920 },
  { id: 'square', label: ['1:1 — Square', '۱:۱ — مربع'], width: 1080, height: 1080 },
  { id: 'portrait', label: ['4:5 — Portrait feed', '۴:۵ — پرتره'], width: 1080, height: 1350 },
  { id: 'landscape', label: ['16:9 — YouTube 1080p', '۱۶:۹ — یوتیوب ۱۰۸۰p'], width: 1920, height: 1080 },
  { id: 'landscape4k', label: ['16:9 — 4K', '۱۶:۹ — چهار کی'], width: 3840, height: 2160 },
]

/** Format, quality and frame rate before anything starts encoding. */
function askExportSettings(t: (en: string, fa: string) => string): Promise<ExportSettings | null> {
  return new Promise((resolve) => {
    const state: ExportSettings = { width: 1080, height: 1920, fps: 30, quality: 'balanced' }
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
              defaultValue="vertical"
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
