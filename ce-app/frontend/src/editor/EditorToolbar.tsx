import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Scissors, Copy, Trash2, Gauge, Volume2, VolumeX, Crop, Move, Droplets, Snowflake,
  Rewind, AudioLines, Sparkles, SlidersHorizontal, Music4, Type, Layers,
  Wand2, Repeat, Ratio, ChevronLeft, RotateCw, Film, Blend, Undo2, Redo2, MoveHorizontal, Diamond, X,
  AudioWaveform, Captions as CaptionsIcon,
} from 'lucide-react'
import { Slider, Segmented, Input, ColorPicker, message, Modal } from 'antd'
import { reframeApi } from '../api/reframe'
import { titlesApi, type TitlePreset } from '../api/titles'
import { captionsApi } from '../api/captions'
import { renderApi } from '../api/render'
import {
  useEditor, propsOf, sampleChannel, MIN_CLIP, KEYFRAME_CHANNELS,
  type Clip, type ClipProps, type KeyframeChannel,
} from './model'
import { useI18n } from '../i18n'
import { TRANSITIONS } from './transitions'
import { Workflow as WorkflowIcon } from 'lucide-react'
import { backendOrigin } from '../api/runtime'
import { FEATURES } from '../features/catalog'

type PanelId =
  | null
  | 'filters'
  | 'adjust'
  | 'animate'
  | 'audio'
  | 'text'
  | 'speed'
  | 'volume'
  | 'crop'
  | 'transform'
  | 'opacity'
  | 'transition'
  | 'timing'
  | 'keyframes'
  | 'ratio'
  | 'captions'
  | 'soon'

interface Tool {
  id: string
  icon: ReactNode
  label: [en: string, fa: string]
  run?: () => void
  panel?: PanelId
  disabled?: boolean
  soon?: boolean
  /** Toggles render pressed, so their state is visible without a tooltip. */
  active?: boolean
}

const ICON = { size: 19, strokeWidth: 1.8 } as const

/**
 * Context-sensitive tool rail, the way every mobile NLE works: one set of tools
 * when nothing is selected, another for the selected clip, and nested panels
 * with a back arrow. Rows scroll horizontally instead of wrapping, so adding
 * tools never reflows the editor.
 */
export default function EditorToolbar({
  onImport,
  onRemoveSilence,
  onSplitScenes,
  onDetectBeats,
  onCutOnBeat,
}: {
  onImport: () => void
  onRemoveSilence?: () => void
  onSplitScenes?: () => void
  onDetectBeats?: () => void
  onCutOnBeat?: () => void
}) {
  const { t, lang } = useI18n()
  const navigate = useNavigate()
  const i = lang === 'fa' ? 1 : 0
  const [soonLabel, setSoonLabel] = useState('')
  const [dubOpen, setDubOpen] = useState(false)
  const [autoOpen, setAutoOpen] = useState(false)
  // Word-level alignment (whisperX) is an on-demand refinement, like Hazm for
  // text: used automatically when fetched, never a button the user must press.
  const [alignAvailable, setAlignAvailable] = useState(false)
  useEffect(() => {
    captionsApi.alignStatus().then((s) => setAlignAvailable(s.available)).catch(() => setAlignAvailable(false))
  }, [])

  const {
    clips, selectedId, playhead, transitions, panel: openPanel, setPanel: setStorePanel,
    splitAtPlayhead, duplicateSelected, removeSelected, setProps, freezeFrame,
    addTransition, neighbourOf, addTrack, select, undo, redo, past, future,
  } = useEditor()

  // The panel lives in the store so the timeline can open one too.
  const panel = openPanel as PanelId
  const setPanel = (next: PanelId) => setStorePanel(next)

  const history: Tool[] = [
    {
      id: 'undo',
      icon: <Undo2 {...ICON} />,
      label: ['Undo', 'واگرد'],
      run: undo,
      disabled: past.length === 0,
    },
    {
      id: 'redo',
      icon: <Redo2 {...ICON} />,
      label: ['Redo', 'ازنو'],
      run: redo,
      disabled: future.length === 0,
    },
    {
      id: 'aitransitions',
      icon: <Wand2 {...ICON} />,
      label: ['AI Transitions', 'ترنزیشن هوشمند'],
      run: () => void applyAiTransitions(),
    },
  ]

  const clip = clips.find((c) => c.id === selectedId) ?? null
  const props = clip ? propsOf(clip) : null

  /** One music-sized transition per junction, suggested by the backend. */
  const applyAiTransitions = async () => {
    const state = useEditor.getState()
    const bpm = state.bpm || 120
    try {
      const res = await fetch(`${backendOrigin}/api/style/ai-transitions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ timeline: { clips: state.clips, tracks: state.tracks }, bpm }),
      })
      const data = await res.json()
      let applied = 0
      for (const tr of data.transitions ?? []) {
        if (addTransition(tr.fromClipId, tr.type, tr.duration)) applied++
      }
      if (applied === 0) {
        message.info(t('No side-by-side junction to put a transition on — add a second clip on the same lane.',
          'اتصال کنارهمی برای ترنزیشن نیست — یک کلیپ دوم روی همان لاین اضافه کن.'))
      } else {
        message.success(t(`AI transitions applied to ${applied} junction(s)`, `ترنزیشن هوشمند روی ${applied} اتصال نشست`))
      }
    } catch {
      message.error(t('Could not reach the backend', 'بک‌اند در دسترس نیست'))
    }
  }

  /** Transcribe the clip under the playhead and lay captions on the text lane. */
  /**
   * Auto-reframe: follow the speaker instead of trusting the middle of the frame.
   *
   * The result arrives as `x` keyframes and is applied as one undoable step, so
   * the camera move is visible on the clip and can be dragged, keyed or deleted
   * like any other. When no face is found the backend says so and nothing is
   * applied — a silent centre crop pretending to be face tracking is what this
   * replaces.
   */
  const autoReframe = async () => {
    const state = useEditor.getState()
    const target =
      state.clips.find((c) => c.id === state.selectedId && c.src) ??
      state.clips.filter((c) => c.src).sort((a, b) => a.start - b.start)[0]
    if (!target?.src) {
      message.warning(t('Import media first.', 'اول یک فایل اضافه کن.'))
      return
    }
    const canvas = state.canvasSize()
    const hide = message.loading(t('Looking for the speaker…', 'دنبال گوینده می‌گردم…'), 0)
    try {
      const plan = await reframeApi.plan(target.src, canvas.width, canvas.height)
      if (plan.fallback || plan.keyframes.length < 2) {
        message.info(
          t(
            `No face to follow — ${plan.reason}`,
            `چهره‌ای برای دنبال کردن نبود — ${plan.reason}`
          )
        )
        return
      }
      state.setClipKeyframes(target.id, plan.keyframes, plan.scale)
      const noun =
        plan.tracker === 'face' ? t('the speaker', 'گوینده')
        : plan.tracker === 'pose' ? t('the athlete', 'ورزشکار')
        : t('the action', 'اکشن')
      message.success(
        t(
          `Following ${noun} (${Math.round(plan.coverage * 100)}% of frames, ${plan.keyframes.length} keys)`,
          `قاب روی ${noun} قفل شد (${Math.round(plan.coverage * 100)}٪ فریم‌ها، ${plan.keyframes.length} کی‌فریم)`
        )
      )
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      hide()
    }
  }

  const [capModal, setCapModal] = useState(false)
  const [quality, setQuality] = useState<'auto' | 'fast' | 'balanced' | 'best'>('auto')

  const fetchModelAndRetry = (size: string, source: Clip) => {
    Modal.confirm({
      title: t(`Model ${size} is not on this device`, `مدل ${size} روی این دستگاه نیست`),
      content: t('Download it now? It lands in your own cache and survives updates.',
        'الان دانلود شود؟ در کش خود شما می‌نشیند و به‌روزرسانی‌ها آن را پاک نمی‌کنند.'),
      okText: t('Download', 'دانلود'),
      cancelText: t('Later', 'بعداً'),
      onOk: async () => {
        const started = await fetch(`${backendOrigin}/api/ai/whisper/download/start`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ size }),
        }).then((r) => r.json())
        const hide2 = message.loading(t(`Fetching ${size}…`, `دانلود ${size}…`), 0)
        const poll = window.setInterval(async () => {
          const p = await fetch(`${backendOrigin}/api/tasks/${started.id}`).then((r) => r.json())
          if (p.status === 'running') return
          window.clearInterval(poll); hide2()
          if (p.status === 'done') void runTranscribe(source, size as 'fast' | 'balanced' | 'best')
          else message.error(p.error || t('download failed', 'دانلود ناموفق'))
        }, 2000)
      },
    })
  }

  const runTranscribe = async (source: Clip, q: typeof quality) => {
    if (!source.src) return
    const src = source.src
    const state = useEditor.getState()
    const KEY = 'ce-cap'
    const show = (content: string) =>
      message.open({ key: KEY, type: 'loading', content, duration: 0 })
    show(t('Starting transcription…', 'شروع رونویسی…'))
    const fail = (detail: string, status?: number) => {
      message.destroy(KEY)
      if (status === 409) {
        const size = detail.match(/model (\S+) not/)?.[1] ?? 'medium'
        fetchModelAndRetry(size, source)
      } else {
        message.error(
          status === 503
            ? t('Speech recognition is not available in this build.', 'تشخیص گفتار در این نسخه نصب نشده است.')
            : detail
        )
      }
    }
    try {
      const started = await captionsApi.transcribeStart(src, undefined, alignAvailable, q)
      // Live progress: the task reports model-load and per-segment words, so the
      // spinner finally shows WHAT the transcription is doing.
      const task = await new Promise<any>((resolve, reject) => {
        const poll = window.setInterval(async () => {
          try {
            const p = await fetch(`${backendOrigin}/api/tasks/${started.id}`).then((r) => r.json())
            if (p.status === 'running') {
              const pct = Math.round((p.progress ?? 0) * 100)
              show(`${p.stage === 'model' ? t('loading model', 'بارگیری مدل') : t('listening', 'گوش می‌دهم')} ${pct}%${p.label ? ` · ${p.label}` : ''}`)
              return
            }
            window.clearInterval(poll)
            p.status === 'done' ? resolve(p) : reject(new Error(p.error || 'failed'))
          } catch (e) { window.clearInterval(poll); reject(e as Error) }
        }, 700)
      })
      message.destroy(KEY)
      const result = task.result
      const count = state.addCaptions(result.cues, source.start - source.offset)
      const aligned = result.alignment === 'aligned'
      message.success(
        `${count} ${t('captions added', 'زیرنویس اضافه شد')} · ${result.language} · ${result.quality ?? q}` +
          (aligned ? ` · ${t('word-aligned', 'کلمه‌تراز')}` : '')
      )
    } catch (err) {
      const resp = (err as { response?: { data?: { detail?: string }; status?: number } }).response
      if (resp) fail(resp.data?.detail ?? (err as Error).message, resp.status)
      else fail((err as Error).message)
    }
  }

  const generateCaptions = async () => {
    const state = useEditor.getState()
    const source =
      state.clips.find((c) => c.id === state.selectedId && c.src) ??
      state.clips.filter((c) => c.src).sort((a, b) => a.start - b.start)[0]
    if (!source?.src) {
      message.warning(t('Import media first.', 'اول یک فایل اضافه کن.'))
      return
    }
    setCapModal(true)
  }

  const notReady = (label: [string, string]) => () => {
    setSoonLabel(label[i])
    setPanel('soon')
  }

  const globalTools: Tool[] = [
    { id: 'edit', icon: <Scissors {...ICON} />, label: ['Edit', 'ویرایش'], run: () => {
      const first = clips.find((c) => playhead >= c.start && playhead < c.start + c.duration) ?? clips[0]
      if (first) select(first.id)
      else onImport()
    } },
    { id: 'audio', icon: <Music4 {...ICON} />, label: ['Audio', 'صدا'], run: () => addTrack('audio') },
    {
      id: 'text',
      icon: <Type {...ICON} />,
      label: ['Text', 'متن'],
      run: () => {
        const id = useEditor.getState().addTextClip(t('Your text', 'متن شما'))
        select(id)
        setPanel('text')
      },
    },
    { id: 'overlay', icon: <Layers {...ICON} />, label: ['Overlay', 'لایه رویی'], run: () => addTrack('video') },
    { id: 'effects', icon: <Sparkles {...ICON} />, label: ['Effects', 'جلوه‌ها'], panel: 'filters' },
    { id: 'automation', icon: <WorkflowIcon {...ICON} />, label: ['Automation', 'اتوماسیون'], run: () => setAutoOpen(true) },
    { id: 'filters', icon: <Wand2 {...ICON} />, label: ['Filters', 'فیلترها'], panel: 'filters' },
    { id: 'adjust', icon: <SlidersHorizontal {...ICON} />, label: ['Adjust', 'تنظیم رنگ'], panel: 'adjust' },
    { id: 'ratio', icon: <Ratio {...ICON} />, label: ['Ratio', 'نسبت تصویر'], panel: 'ratio' },
    ...(onDetectBeats
      ? [{ id: 'beats', icon: <AudioWaveform {...ICON} />, label: ['Find the beat', 'یافتن ضرب'] as [string, string], run: onDetectBeats }]
      : []),
    // Everything that was taken off the home screen: these act on footage, so
    // they belong next to the footage.
    ...FEATURES.filter((feature) => feature.place === 'editor').map<Tool>((feature) => {
      // Every rail tool is wired to a real door — none is left a dead "SOON".
      // The heavy ones (dub, translate) open the dub modal; the panel-backed ones
      // select a clip and open their panel; the on-demand ones say exactly what
      // they need and take the user to Settings.
      const withClip = (panelId: PanelId) => () => {
        const first = clips.find((c) => c.src) ?? clips[0]
        if (first) {
          select(first.id)
          setPanel(panelId)
        } else {
          message.info(t('Add a clip first.', 'اول یک کلیپ اضافه کن.'))
        }
      }
      const local: Record<string, (() => void) | undefined> = {
        subtitles: () => void generateCaptions(),
        silence: onRemoveSilence,
        facetrack: () => void autoReframe(),
        translate: () => setDubOpen(true),
        voiceover: () => setDubOpen(true),
        music: withClip('audio'),
        enhance: withClip('adjust'),
        titles: () => {
          const id = useEditor.getState().addTextClip(t('Title', 'تیتراژ'))
          select(id)
          setPanel('text')
        },
        bgremove: () => {
          message.info(t('Background removal is on-demand — add a segmentation provider in Settings.',
            'حذف پس‌زمینه on-demand است — یک provider در Settings اضافه کن.'))
          navigate('/settings')
        },
        broll: () => {
          message.info(t('Auto B-Roll needs a Pexels key in Settings.', 'بی‌رول خودکار به کلید Pexels در Settings نیاز دارد.'))
          navigate('/settings')
        },
      }
      const run = local[feature.id]
      return {
        id: `f-${feature.id}`,
        icon: feature.icon,
        label: feature.label,
        soon: false,
        run: run ?? (() => navigate(feature.route)),
      }
    }),
    ...(onCutOnBeat
      ? [{ id: 'cutbeat', icon: <AudioWaveform {...ICON} />, label: ['Cut on beat', 'برش روی ضرب'] as [string, string], run: onCutOnBeat }]
      : []),
    ...(onSplitScenes
      ? [{ id: 'scenes', icon: <Film {...ICON} />, label: ['Split scenes', 'تقسیم نما'] as [string, string], run: onSplitScenes }]
      : []),
  ]

  const clipTools: Tool[] = [
    { id: 'split', icon: <Scissors {...ICON} />, label: ['Split', 'برش'], run: splitAtPlayhead },
    { id: 'timing', icon: <MoveHorizontal {...ICON} />, label: ['Trim & slip', 'تریم و لغزش'], panel: 'timing' },
    { id: 'keyframes', icon: <Diamond {...ICON} />, label: ['Keyframes', 'کی‌فریم'], panel: 'keyframes' },
    { id: 'speed', icon: <Gauge {...ICON} />, label: ['Speed', 'سرعت'], panel: 'speed' },
    { id: 'volume', icon: <Volume2 {...ICON} />, label: ['Volume', 'صدا'], panel: 'volume' },
    { id: 'transition', icon: <Blend {...ICON} />, label: ['Transition', 'ترنزیشن'], panel: 'transition' },
    { id: 'crop', icon: <Crop {...ICON} />, label: ['Crop', 'برش کادر'], panel: 'crop' },
    { id: 'transform', icon: <Move {...ICON} />, label: ['Transform', 'جابه‌جایی'], panel: 'transform' },
    { id: 'opacity', icon: <Droplets {...ICON} />, label: ['Opacity', 'شفافیت'], panel: 'opacity' },
    { id: 'duplicate', icon: <Copy {...ICON} />, label: ['Duplicate', 'تکثیر'], run: duplicateSelected },
    {
      id: 'freeze',
      icon: <Snowflake {...ICON} />,
      label: ['Freeze', 'فریز'],
      run: () => clip && freezeFrame(clip.id),
    },
    {
      id: 'reverse',
      icon: <Rewind {...ICON} />,
      label: ['Reverse', 'معکوس'],
      active: Boolean(props?.reversed),
      run: () => {
        if (!clip || !props) return
        setProps(clip.id, { reversed: !props.reversed })
        message.success(props.reversed ? t('Reverse off', 'معکوس خاموش') : t('Reverse on', 'معکوس روشن'))
      },
    },
    {
      id: 'mute',
      icon: props?.muted ? <VolumeX {...ICON} /> : <AudioLines {...ICON} />,
      label: ['Mute', 'بی‌صدا'],
      // A toggle has to look like a toggle, or it reads as "the button does
      // nothing" even while it is working.
      active: Boolean(props?.muted),
      run: () => {
        if (!clip || !props) return
        setProps(clip.id, { muted: !props.muted })
        message.success(props.muted ? t('Sound on', 'صدا روشن') : t('Sound off', 'صدا خاموش'))
      },
    },
    {
      id: 'rotate',
      icon: <RotateCw {...ICON} />,
      label: ['Rotate', 'چرخش'],
      run: () => clip && props && setProps(clip.id, { transform: { ...props.transform, rotate: (props.transform.rotate + 90) % 360 } }),
    },
    { id: 'replace', icon: <Repeat {...ICON} />, label: ['Replace', 'جایگزینی'], run: onImport },
    { id: 'animations', icon: <Film {...ICON} />, label: ['Animations', 'انیمیشن'], panel: 'animate' },
    { id: 'clipfilters', icon: <Wand2 {...ICON} />, label: ['Filters', 'فیلترها'], panel: 'filters' },
    { id: 'clipadjust', icon: <SlidersHorizontal {...ICON} />, label: ['Adjust', 'تنظیم رنگ'], panel: 'adjust' },
    { id: 'clipaudio', icon: <AudioLines {...ICON} />, label: ['Audio', 'پردازش صدا'], panel: 'audio' },
    ...(clip?.text !== undefined && clip?.src == null
      ? [
          { id: 'edittext', icon: <Type {...ICON} />, label: ['Edit text', 'ویرایش متن'] as [string, string], panel: 'text' as PanelId },
          { id: 'captions', icon: <CaptionsIcon {...ICON} />, label: ['Captions', 'کپشن‌ها'] as [string, string], panel: 'captions' as PanelId },
        ]
      : []),
    { id: 'delete', icon: <Trash2 {...ICON} />, label: ['Delete', 'حذف'], run: removeSelected },
  ]

  const tools = [...history, ...(clip ? clipTools : globalTools)]

  return (
    <div className="tb">
      {panel && (
        <div className="tb__panel">
          <button className="tb__back" onClick={() => setPanel(null)}>
            <ChevronLeft size={18} />
          </button>
          <div className="tb__panel-body">
            {panel === 'speed' && clip && props && (
              <PanelSpeed clip={clip} speed={props.speed} onChange={(v) => setProps(clip.id, { speed: v })} />
            )}
            {panel === 'volume' && clip && props && (
              <PanelVolume
                volume={props.volume}
                fadeIn={props.fadeIn}
                fadeOut={props.fadeOut}
                max={clip.duration / 2}
                onChange={(patch) => setProps(clip.id, patch)}
              />
            )}
            {panel === 'opacity' && clip && props && (
              <Field label={t('Opacity', 'شفافیت')} value={`${Math.round(props.opacity * 100)}%`}>
                <Slider
                  min={0}
                  max={1}
                  step={0.01}
                  value={props.opacity}
                  onChange={(v) => setProps(clip.id, { opacity: v })}
                />
              </Field>
            )}
            {panel === 'crop' && clip && props && (
              <PanelCrop crop={props.crop} onChange={(crop) => setProps(clip.id, { crop })} />
            )}
            {panel === 'transform' && clip && props && (
              <PanelTransform
                transform={props.transform}
                onChange={(transform) => setProps(clip.id, { transform })}
              />
            )}
            {panel === 'timing' && clip && <PanelTiming clip={clip} />}
            {panel === 'keyframes' && clip && <PanelKeyframes clip={clip} />}
            {panel === 'transition' && clip && (
              <PanelTransition
                clip={clip}
                hasNeighbour={Boolean(neighbourOf(clip.id))}
                existing={transitions.find((x) => x.fromClipId === clip.id) ?? null}
                onApply={(type, duration) => {
                  const created = addTransition(clip.id, type, duration)
                  if (!created) {
                    message.warning(
                      t('Place another clip right after this one first.', 'اول یک کلیپ دیگر بلافاصله بعد از این بگذار.')
                    )
                  }
                }}
              />
            )}
            {panel === 'filters' && clip && props && (
              <PanelFilters current={props.filter} onPick={(filter) => setProps(clip.id, { filter })} />
            )}
            {panel === 'adjust' && clip && props && (
              <PanelAdjust adjust={props.adjust} onChange={(adjust) => setProps(clip.id, { adjust })} />
            )}
            {panel === 'animate' && clip && props && (
              <PanelAnimate
                animIn={props.animIn}
                animOut={props.animOut}
                duration={props.animDuration}
                onChange={(patch) => setProps(clip.id, patch)}
              />
            )}
            {panel === 'audio' && clip && props && (
              <PanelAudio
                clip={clip}
                denoise={props.denoise}
                enhanceVoice={props.enhanceVoice}
                duck={props.duck}
                onChange={(patch) => setProps(clip.id, patch)}
              />
            )}
            {panel === 'text' && clip && props && (
              <PanelText
                clipId={clip.id}
                text={clip.text ?? ''}
                props={props}
                onText={(value) => useEditor.getState().setText(clip.id, value)}
                onProps={(patch) => setProps(clip.id, patch)}
              />
            )}
            {panel === 'ratio' && <PanelRatio />}
            {panel === 'captions' && clip && <PanelCaptions clip={clip} />}
            {panel === 'soon' && (
              <p className="ce-hint">
                {soonLabel} — {t('arriving in the next phase of the editor.', 'در فاز بعدی ویرایشگر اضافه می‌شود.')}
              </p>
            )}
          </div>
        </div>
      )}

      <Modal
        open={capModal}
        onCancel={() => setCapModal(false)}
        onOk={() => {
          setCapModal(false)
          const state = useEditor.getState()
          const source =
            state.clips.find((c) => c.id === state.selectedId && c.src) ??
            state.clips.filter((c) => c.src).sort((a, b) => a.start - b.start)[0]
          if (source?.src) void runTranscribe(source, quality)
        }}
        okText={t('Transcribe', 'رونویسی')}
        title={t('Caption quality', 'کیفیت کپشن')}
      >
        <Segmented
          value={quality}
          onChange={(v) => setQuality(v as typeof quality)}
          options={[
            { value: 'auto', label: t('Auto', 'خودکار') },
            { value: 'fast', label: t('Fast', 'سریع') },
            { value: 'balanced', label: t('Balanced', 'متعادل') },
            { value: 'best', label: t('Best', 'بهترین') },
          ]}
        />
        <p className="ce-hint" style={{ marginTop: 10 }}>
          {t(
            'Best (large-v3) spells like the big services but needs a one-time download; Fast is instant. Spelling polish runs for every language afterwards.',
            '«بهترین» (large-v3) مثل سرویس‌های بزرگ می‌نویسد ولی یک دانلود اولیه می‌خواهد؛ «سریع» آنی است. پولیش املا بعداً برای هر زبانی اجرا می‌شود.'
          )}
        </p>
      </Modal>

      <DubModal open={dubOpen} onClose={() => setDubOpen(false)} />

      <Modal open={autoOpen} onCancel={() => setAutoOpen(false)} footer={null}
        title={t('Automation — run a pipeline on this clip', 'اتوماسیون — اجرا روی این کلیپ')}>
        <div className="tb__stack">
          <button className="ce-btn ce-btn--sm" onClick={() => {
            const state = useEditor.getState()
            const target = state.clips.find((c) => c.id === state.selectedId && c.src) ??
              state.clips.filter((c) => c.src).sort((a, b) => a.start - b.start)[0]
            if (!target?.src) { message.warning(t('Select a clip first.', 'اول یک کلیپ انتخاب کن.')); return }
            setAutoOpen(false)
            void (async () => {
              const started = await fetch(`${backendOrigin}/api/workflows/run`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ preset: 'shorts', path: target.src }),
              }).then((r) => r.json())
              const hide = message.loading(t('Shorts factory running…', 'کارخانه‌ی شورت در حال اجرا…'), 0)
              const poll = window.setInterval(async () => {
                const p = await fetch(`${backendOrigin}/api/tasks/${started.id}`).then((r) => r.json())
                if (p.status === 'running') return
                window.clearInterval(poll); hide()
                p.status === 'done'
                  ? message.success(t('Automation done — project saved', 'اتوماسیون تمام شد و پروژه ذخیره شد'))
                  : message.error(p.error || t('automation failed', 'اتوماسیون ناموفق'))
              }, 900)
            })()
          }}>
            <WorkflowIcon size={14} /> {t('Shorts factory on this clip', 'کارخانه‌ی شورت روی این کلیپ')}
          </button>
          <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => { setAutoOpen(false); navigate('/workflows') }}>
            {t('Open the Workflows studio', 'بازکردن استودیوی ورکفلوها')}
          </button>
        </div>
      </Modal>

      <div className="tb__rail">
        {tools.map((tool) => (
          <button
            key={tool.id}
            className={`tb__tool ${tool.soon ? 'is-soon' : ''} ${tool.active ? 'is-active' : ''} ${
              panel && tool.panel === panel ? 'is-open' : ''
            }`}
            disabled={tool.disabled}
            onClick={() => {
              if (tool.soon) return notReady(tool.label)()
              if (tool.panel) setPanel(tool.panel)
              else tool.run?.()
            }}
          >
            <span className="tb__icon">{tool.icon}</span>
            <span className="tb__label">{tool.label[i]}</span>
          </button>
        ))}
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------- panels -- */

function Field({ label, value, children }: { label: string; value?: string; children: ReactNode }) {
  return (
    <label className="tb__field">
      <span className="tb__field-head">
        {label}
        {value && <strong dir="ltr">{value}</strong>}
      </span>
      {children}
    </label>
  )
}

function PanelSpeed({ clip, speed, onChange }: { clip: Clip; speed: number; onChange: (v: number) => void }) {
  const { t } = useI18n()
  return (
    <div className="tb__stack">
      <Field label={t('Speed', 'سرعت')} value={`${speed.toFixed(2)}×`}>
        <Slider min={0.25} max={4} step={0.05} value={speed} onChange={onChange} />
      </Field>
      <Segmented
        value={String(speed)}
        onChange={(v) => onChange(Number(v))}
        options={['0.5', '1', '1.5', '2', '3'].map((v) => ({ value: v, label: `${v}×` }))}
      />
      <span className="ce-hint">
        {t('Clip length', 'طول کلیپ')}: <span dir="ltr">{clip.duration.toFixed(2)}s</span>
      </span>
    </div>
  )
}

function PanelVolume({
  volume, fadeIn, fadeOut, max, onChange,
}: {
  volume: number
  fadeIn: number
  fadeOut: number
  max: number
  onChange: (patch: { volume?: number; fadeIn?: number; fadeOut?: number }) => void
}) {
  const { t } = useI18n()
  return (
    <div className="tb__stack">
      <Field label={t('Volume', 'بلندی صدا')} value={`${Math.round(volume * 100)}%`}>
        <Slider min={0} max={2} step={0.01} value={volume} onChange={(v) => onChange({ volume: v })} />
      </Field>
      <div className="tb__row">
        <Field label={t('Fade in', 'محو ورودی')} value={`${fadeIn.toFixed(1)}s`}>
          <Slider min={0} max={Math.max(0.5, max)} step={0.1} value={fadeIn} onChange={(v) => onChange({ fadeIn: v })} />
        </Field>
        <Field label={t('Fade out', 'محو خروجی')} value={`${fadeOut.toFixed(1)}s`}>
          <Slider min={0} max={Math.max(0.5, max)} step={0.1} value={fadeOut} onChange={(v) => onChange({ fadeOut: v })} />
        </Field>
      </div>
    </div>
  )
}

function PanelCrop({
  crop, onChange,
}: {
  crop: { left: number; top: number; right: number; bottom: number }
  onChange: (crop: { left: number; top: number; right: number; bottom: number }) => void
}) {
  const { t } = useI18n()
  const edges: [keyof typeof crop, string][] = [
    ['left', t('Left', 'چپ')],
    ['right', t('Right', 'راست')],
    ['top', t('Top', 'بالا')],
    ['bottom', t('Bottom', 'پایین')],
  ]
  return (
    <div className="tb__grid">
      {edges.map(([key, label]) => (
        <Field key={key} label={label} value={`${Math.round(crop[key] * 100)}%`}>
          <Slider
            min={0}
            max={0.45}
            step={0.01}
            value={crop[key]}
            onChange={(v) => onChange({ ...crop, [key]: v })}
          />
        </Field>
      ))}
    </div>
  )
}

function PanelTransform({
  transform, onChange,
}: {
  transform: { x: number; y: number; scale: number; rotate: number }
  onChange: (transform: { x: number; y: number; scale: number; rotate: number }) => void
}) {
  const { t } = useI18n()
  return (
    <div className="tb__grid">
      <Field label={t('Scale', 'مقیاس')} value={`${Math.round(transform.scale * 100)}%`}>
        <Slider min={0.1} max={3} step={0.01} value={transform.scale} onChange={(v) => onChange({ ...transform, scale: v })} />
      </Field>
      <Field label={t('Rotation', 'چرخش')} value={`${Math.round(transform.rotate)}°`}>
        <Slider min={-180} max={180} step={1} value={transform.rotate} onChange={(v) => onChange({ ...transform, rotate: v })} />
      </Field>
      <Field label={t('Horizontal', 'افقی')} value={`${Math.round(transform.x * 100)}%`}>
        <Slider min={-0.5} max={0.5} step={0.01} value={transform.x} onChange={(v) => onChange({ ...transform, x: v })} />
      </Field>
      <Field label={t('Vertical', 'عمودی')} value={`${Math.round(transform.y * 100)}%`}>
        <Slider min={-0.5} max={0.5} step={0.01} value={transform.y} onChange={(v) => onChange({ ...transform, y: v })} />
      </Field>
    </div>
  )
}

/**
 * The three trims every real editor has and a timeline drag cannot express:
 * ripple (close the gap), roll (move the cut, keep the total length) and slip
 * (change what is inside the clip without moving it).
 */
function PanelTiming({ clip }: { clip: Clip }) {
  const { t } = useI18n()
  const { rippleTrim, rollEdit, slipClip, rippleDelete, clips } = useEditor()
  const neighbour = clips
    .filter((c) => c.trackId === clip.trackId && c.start >= clip.start + clip.duration - 0.001)
    .sort((a, b) => a.start - b.start)[0]
  const slack = Math.max(0, clip.sourceDuration - clip.duration)

  return (
    <div className="tb__stack">
      <div className="tb__row">
        <Field label={t('Ripple trim start', 'تریم پیوسته از ابتدا')}>
          <div className="tb__row">
            <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => rippleTrim(clip.id, 'start', clip.start + 0.5)}>
              +0.5s
            </button>
            <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => rippleTrim(clip.id, 'start', clip.start - 0.5)}>
              −0.5s
            </button>
          </div>
        </Field>
        <Field label={t('Ripple trim end', 'تریم پیوسته از انتها')}>
          <div className="tb__row">
            <button
              className="ce-btn ce-btn--ghost ce-btn--sm"
              onClick={() => rippleTrim(clip.id, 'end', clip.start + clip.duration - 0.5)}
            >
              −0.5s
            </button>
            <button
              className="ce-btn ce-btn--ghost ce-btn--sm"
              onClick={() => rippleTrim(clip.id, 'end', clip.start + clip.duration + 0.5)}
            >
              +0.5s
            </button>
          </div>
        </Field>
      </div>

      <Field
        label={t('Roll the cut with the next clip', 'جابه‌جایی مرز با کلیپ بعدی')}
        value={neighbour ? `${(clip.start + clip.duration).toFixed(2)}s` : '—'}
      >
        <Slider
          min={clip.start + MIN_CLIP}
          max={neighbour ? neighbour.start + neighbour.duration - MIN_CLIP : clip.start + clip.duration}
          step={0.05}
          disabled={!neighbour}
          value={clip.start + clip.duration}
          onChange={(v) => rollEdit(clip.id, v)}
        />
      </Field>

      <Field label={t('Slip the content', 'لغزش محتوا')} value={`${clip.offset.toFixed(2)}s`}>
        <Slider
          min={0}
          max={Math.max(0.01, slack)}
          step={0.05}
          disabled={slack < 0.05}
          value={clip.offset}
          onChange={(v) => slipClip(clip.id, v - clip.offset)}
        />
      </Field>

      <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => rippleDelete(clip.id)}>
        {t('Ripple delete (close the gap)', 'حذف پیوسته (بستن فاصله)')}
      </button>
    </div>
  )
}

/**
 * Keyframes.
 *
 * Only the five channels FFmpeg can genuinely animate are offered — position,
 * scale, rotation and volume — because a keyframe the export cannot reproduce
 * would make the monitor lie. Values move linearly between keys, which is
 * exactly what the expressions in the compositor do.
 */
function PanelKeyframes({ clip }: { clip: Clip }) {
  const { t } = useI18n()
  const { playhead, setKeyframe, removeKeyframe, clearKeyframes } = useEditor()
  const props = propsOf(clip)
  const local = Math.max(0, Math.min(clip.duration, playhead - clip.start))
  const keys = clip.keyframes ?? []

  const i = useI18n().lang === 'fa' ? 1 : 0
  const RANGES: Record<KeyframeChannel, { min: number; max: number; step: number; label: [string, string]; unit?: string }> = {
    x: { min: -0.5, max: 0.5, step: 0.01, label: ['Horizontal', 'افقی'] },
    y: { min: -0.5, max: 0.5, step: 0.01, label: ['Vertical', 'عمودی'] },
    scale: { min: 0.1, max: 3, step: 0.01, label: ['Scale', 'مقیاس'] },
    rotate: { min: -180, max: 180, step: 1, label: ['Rotation', 'چرخش'], unit: '°' },
    volume: { min: 0, max: 2, step: 0.01, label: ['Volume', 'بلندی صدا'] },
  }
  const staticValue = (channel: KeyframeChannel) =>
    channel === 'volume' ? props.volume : (props.transform as Record<string, number>)[channel]

  return (
    <div className="tb__stack">
      <span className="ce-hint">
        {t(
          `Keys are placed at the playhead — now ${local.toFixed(2)}s into this clip. Values move linearly between keys, in the preview and in the export alike.`,
          `کی‌فریم روی پلی‌هد ساخته می‌شود — الان ثانیه‌ی ${local.toFixed(2)} از این کلیپ. بین دو کی‌فریم مقدار خطی تغییر می‌کند، هم در پیش‌نمایش هم در خروجی.`
        )}
      </span>

      <div className="tb__grid">
        {KEYFRAME_CHANNELS.map((channel) => {
          const range = RANGES[channel]
          const current = sampleChannel(clip, channel, local) ?? staticValue(channel)
          const keyed = keys.some((k) => k[channel] !== undefined)
          const here = keys.find((k) => Math.abs(k.t - local) < 0.02 && k[channel] !== undefined)
          return (
            <Field
              key={channel}
              label={range.label[i]}
              value={`${current.toFixed(2)}${range.unit ?? ''}${keyed ? ' ◆' : ''}`}
            >
              <div className="tb__row">
                <Slider
                  className="tb__grow"
                  min={range.min}
                  max={range.max}
                  step={range.step}
                  value={current}
                  onChange={(value) => setKeyframe(clip.id, local, { [channel]: value })}
                />
                <button
                  className={`ce-btn ce-btn--ghost ce-btn--sm ${here ? 'is-on' : ''}`}
                  title={
                    here
                      ? t('Remove the key here', 'حذف کی‌فریم اینجا')
                      : t('Add a key here', 'افزودن کی‌فریم اینجا')
                  }
                  onClick={() =>
                    here ? removeKeyframe(clip.id, here.t) : setKeyframe(clip.id, local, { [channel]: current })
                  }
                >
                  <Diamond size={13} />
                </button>
              </div>
            </Field>
          )
        })}
      </div>

      {keys.length > 0 && (
        <>
          <div className="tb__keys">
            {keys.map((key) => (
              <button
                key={key.t}
                className="tb__key"
                onClick={() => removeKeyframe(clip.id, key.t)}
                title={t('Remove this keyframe', 'حذف این کی‌فریم')}
              >
                <span dir="ltr">{key.t.toFixed(2)}s</span>
                <X size={11} />
              </button>
            ))}
          </div>
          <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => clearKeyframes(clip.id)}>
            {t('Clear all keyframes', 'حذف همه‌ی کی‌فریم‌ها')}
          </button>
        </>
      )}
    </div>
  )
}

function PanelTransition({
  clip, hasNeighbour, existing, onApply,
}: {
  clip: Clip
  hasNeighbour: boolean
  existing: { id: string; type: string; duration: number } | null
  onApply: (type: string, duration: number) => void
}) {
  const { t, lang } = useI18n()
  const i = lang === 'fa' ? 1 : 0
  const { updateTransition, removeTransition } = useEditor()
  const [duration, setDuration] = useState(existing?.duration ?? 0.5)

  if (!hasNeighbour && !existing) {
    return (
      <p className="ce-hint">
        {t(
          'A transition needs a clip immediately after this one.',
          'برای ترنزیشن باید بلافاصله بعد از این کلیپ، کلیپ دیگری باشد.'
        )}
      </p>
    )
  }

  return (
    <div className="tb__stack">
      <Field label={t('Duration', 'مدت')} value={`${duration.toFixed(2)}s`}>
        <Slider
          min={0.1}
          max={Math.max(0.3, Math.min(2, clip.duration * 0.9))}
          step={0.05}
          value={duration}
          onChange={(v) => {
            setDuration(v)
            if (existing) updateTransition(existing.id, { duration: v })
          }}
        />
      </Field>

      <div className="tb__transitions">
        {TRANSITIONS.map((transition) => (
          <button
            key={transition.id}
            className={`tb__transition ${existing?.type === transition.id ? 'is-active' : ''}`}
            onClick={() =>
              existing ? updateTransition(existing.id, { type: transition.id }) : onApply(transition.id, duration)
            }
          >
            <span className="tb__transition-art" data-kind={transition.id} />
            <span>{transition.label[i]}</span>
          </button>
        ))}
      </div>

      {existing && (
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => removeTransition(existing.id)}>
          {t('Remove transition', 'حذف ترنزیشن')}
        </button>
      )}
    </div>
  )
}

const LOOKS: [id: string, label: [string, string], swatch: string][] = [
  ['none', ['Original', 'اصلی'], 'linear-gradient(135deg,#64748b,#94a3b8)'],
  ['warm', ['Warm', 'گرم'], 'linear-gradient(135deg,#f59e0b,#ef4444)'],
  ['cool', ['Cool', 'سرد'], 'linear-gradient(135deg,#38bdf8,#6366f1)'],
  ['cinematic', ['Cinematic', 'سینمایی'], 'linear-gradient(135deg,#0f172a,#14b8a6)'],
  ['vivid', ['Vivid', 'پرمایه'], 'linear-gradient(135deg,#ec4899,#f59e0b)'],
  ['bw', ['B & W', 'سیاه‌وسفید'], 'linear-gradient(135deg,#e2e8f0,#0f172a)'],
  ['sepia', ['Sepia', 'سپیا'], 'linear-gradient(135deg,#d6b48a,#7c5a3a)'],
  ['vintage', ['Vintage', 'قدیمی'], 'linear-gradient(135deg,#c4b5fd,#f0abfc)'],
  ['matte', ['Matte', 'مات'], 'linear-gradient(135deg,#475569,#cbd5e1)'],
  ['night', ['Night', 'شب'], 'linear-gradient(135deg,#1e293b,#3b82f6)'],
]

function PanelFilters({ current, onPick }: { current: string; onPick: (id: string) => void }) {
  const { lang } = useI18n()
  const i = lang === 'fa' ? 1 : 0
  return (
    <div className="tb__transitions">
      {LOOKS.map(([id, label, swatch]) => (
        <button
          key={id}
          className={`tb__transition ${current === id ? 'is-active' : ''}`}
          onClick={() => onPick(id)}
        >
          <span className="tb__transition-art" style={{ background: swatch }} />
          <span>{label[i]}</span>
        </button>
      ))}
    </div>
  )
}

function PanelAdjust({
  adjust, onChange,
}: {
  adjust: ClipProps['adjust']
  onChange: (adjust: ClipProps['adjust']) => void
}) {
  const { t } = useI18n()
  const rows: [keyof typeof adjust, string, number, number, number][] = [
    ['brightness', t('Brightness', 'روشنایی'), -0.5, 0.5, 0],
    ['contrast', t('Contrast', 'کنتراست'), 0.5, 2, 1],
    ['saturation', t('Saturation', 'اشباع'), 0, 3, 1],
    ['temperature', t('Temperature', 'دمای رنگ'), -1, 1, 0],
    ['sharpen', t('Sharpen', 'وضوح'), 0, 1, 0],
    ['vignette', t('Vignette', 'وینیت'), 0, 1, 0],
  ]
  return (
    <div className="tb__grid">
      {rows.map(([key, label, min, max, base]) => (
        <Field key={key} label={label} value={adjust[key].toFixed(2)}>
          <Slider
            min={min}
            max={max}
            step={0.01}
            value={adjust[key]}
            onChange={(v) => onChange({ ...adjust, [key]: v })}
            marks={{ [base]: '' }}
          />
        </Field>
      ))}
    </div>
  )
}

const ANIMATIONS: [id: string, label: [string, string]][] = [
  ['none', ['None', 'بدون']],
  ['fade', ['Fade', 'محو']],
  ['zoomIn', ['Zoom in', 'زوم به داخل']],
  ['zoomOut', ['Zoom out', 'زوم به بیرون']],
]

function PanelAnimate({
  animIn, animOut, duration, onChange,
}: {
  animIn: string
  animOut: string
  duration: number
  onChange: (patch: { animIn?: string; animOut?: string; animDuration?: number }) => void
}) {
  const { t, lang } = useI18n()
  const i = lang === 'fa' ? 1 : 0
  return (
    <div className="tb__stack">
      <div className="tb__row">
        <Field label={t('In', 'ورود')}>
          <Segmented
            value={animIn}
            onChange={(v) => onChange({ animIn: String(v) })}
            options={ANIMATIONS.map(([id, label]) => ({ value: id, label: label[i] }))}
          />
        </Field>
        <Field label={t('Out', 'خروج')}>
          <Segmented
            value={animOut}
            onChange={(v) => onChange({ animOut: String(v) })}
            options={ANIMATIONS.map(([id, label]) => ({ value: id, label: label[i] }))}
          />
        </Field>
      </div>
      <Field label={t('Animation length', 'مدت انیمیشن')} value={`${duration.toFixed(1)}s`}>
        <Slider min={0.2} max={2} step={0.1} value={duration} onChange={(v) => onChange({ animDuration: v })} />
      </Field>
    </div>
  )
}

/** A collapsible inspector section (0.9.33): grouped, iconed, calm. */
function Sec({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  const [open, setOpen] = useState(true)
  return (
    <div className="insp-sec">
      <button onClick={() => setOpen((v) => !v)}>
        {icon} {title} <ChevronLeft size={14} style={{ marginInlineStart: 'auto', transform: open ? 'rotate(-90deg)' : 'rotate(90deg)', transition: 'transform .15s' }} />
      </button>
      {open && <div>{children}</div>}
    </div>
  )
}

function PanelAudio({
  clip, denoise, enhanceVoice, duck, onChange,
}: {
  clip: Clip
  denoise: number
  enhanceVoice: boolean
  duck: boolean
  onChange: (patch: { denoise?: number; enhanceVoice?: boolean; duck?: boolean }) => void
}) {
  const { t } = useI18n()

  /**
   * Audio extraction — lift this clip's audio onto the audio lane, aligned
   * under the picture it came from, the way a desktop NLE does it. Pure FFmpeg
   * on the backend, so it always works; the lifted file lands in the user's
   * exports folder and the timeline clip points at it.
   */
  const extractAudio = async () => {
    if (!clip.src) {
      message.warning(t('Only clips with a media file have audio to extract.', 'فقط کلیپی که فایل رسانه دارد صدا برای استخراج دارد.'))
      return
    }
    const hide = message.loading(t('Extracting audio…', 'در حال استخراج صدا…'), 0)
    try {
      const res = await fetch(`${backendOrigin}/api/audio/extract`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: clip.src }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.detail ?? res.statusText)
      const state = useEditor.getState()
      let lane = state.tracks.find((x) => x.kind === 'audio')
      if (!lane) {
        state.addTrack('audio')
        lane = useEditor.getState().tracks.find((x) => x.kind === 'audio')
      }
      state.addClip({
        trackId: lane?.id ?? 'a1',
        start: clip.start,
        duration: Math.max(0.5, data.duration),
        offset: 0,
        sourceDuration: Math.max(0.5, data.duration),
        src: data.path,
        label: t('extracted audio', 'صدای استخراج‌شده'),
        color: '#10B981',
      })
      message.success(t('Audio extracted onto the audio lane', 'صدا استخراج شد و روی خط صوتی نشست'))
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      hide()
    }
  }

  /**
   * Stem separation with Demucs (MIT, on-demand): vocals / drums / bass / other
   * into the exports folder. Absent engine is an honest 409 pointing at Settings.
   */
  const splitStems = async () => {
    if (!clip.src) return
    try {
      const res = await fetch(`${backendOrigin}/api/audio/stems/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: clip.src }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.detail ?? res.statusText)
      const hide = message.loading(t('Demucs is splitting the mix…', 'Demucs در حال جداسازی اجزاست…'), 0)
      const poll = window.setInterval(async () => {
        const p = await fetch(`${backendOrigin}/api/tasks/${data.id}`).then((r) => r.json())
        if (p.status === 'running') return
        window.clearInterval(poll)
        hide()
        if (p.status === 'done') {
          const stems = Object.keys(p.result?.stems ?? {}).join('، ')
          message.success(t(`Stems ready: ${stems}`, `اجزا آماده شد: ${stems}`))
        } else {
          message.error(p.error || t('stem separation failed', 'جداسازی ناموفق بود'))
        }
      }, 2000)
    } catch (err) {
      message.error((err as Error).message)
    }
  }

  return (
    <div className="tb__stack">
      <Sec icon={<AudioLines size={14} />} title={t('Extract & stems', 'استخراج و اجزا')}>
      <div className="tb__row">
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void extractAudio()}>
          <AudioLines size={13} /> {t('Audio extraction', 'استخراج صدا')}
        </button>
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void splitStems()}>
          <Layers size={13} /> {t('Split stems (Demucs)', 'جداسازی اجزا (Demucs)')}
        </button>
      </div>
      <p className="ce-hint">
        {t(
          'Extraction lifts this clip\'s audio onto the audio lane (FFmpeg, always). Stems split the mix into vocals/drums/bass/other with Demucs — fetch it in Settings.',
          'استخراج، صدای این کلیپ را روی خط صوتی می‌برد (FFmpeg، همیشه). جداسازی اجزا میکس را به صدا/درام/بیس/دیگر تقسیم می‌کند با Demucs — از تنظیمات بگیرش.'
        )}
      </p>
      </Sec>
      <Sec icon={<Volume2 size={14} />} title={t('Clean & duck', 'پاک‌سازی و داکینگ')}>
      <Field label={t('Noise reduction', 'نویزگیری')} value={`${Math.round(denoise * 100)}%`}>
        <Slider min={0} max={1} step={0.05} value={denoise} onChange={(v) => onChange({ denoise: v })} />
      </Field>
      <Segmented
        value={enhanceVoice ? 'on' : 'off'}
        onChange={(v) => onChange({ enhanceVoice: v === 'on' })}
        options={[
          { value: 'off', label: t('Voice enhance off', 'بهبود صدا خاموش') },
          { value: 'on', label: t('Voice enhance on', 'بهبود صدا روشن') },
        ]}
      />
      <p className="ce-hint">
        {t(
          'Voice enhance applies a high-pass, presence boost, compression and -16 LUFS normalisation.',
          'بهبود صدا شامل حذف بم‌های مزاحم، تقویت وضوح، فشرده‌سازی و نرمال‌سازی روی -۱۶ است.'
        )}
      </p>

      <Segmented
        value={duck ? 'on' : 'off'}
        onChange={(v) => onChange({ duck: v === 'on' })}
        options={[
          { value: 'off', label: t('Ducking off', 'داکینگ خاموش') },
          { value: 'on', label: t('Duck under voice', 'کم شدن زیر صدا') },
        ]}
      />
      <p className="ce-hint">
        {t(
          'Mark the music this way and it drops on every word of the voice and comes back in the gaps — the export uses a sidechain compressor, the monitor approximates it.',
          'موسیقی را این‌طور علامت بزن تا روی هر کلمه‌ی گوینده پایین بیاید و در سکوت‌ها برگردد — خروجی از فشرده‌ساز زنجیره‌ای استفاده می‌کند و مانیتور تقریب آن را نشان می‌دهد.'
        )}
      </p>
      </Sec>
    </div>
  )
}

function PanelText({
  clipId, text, props, onText, onProps,
}: {
  clipId: string
  text: string
  props: ClipProps
  onText: (value: string) => void
  onProps: (patch: Partial<ClipProps>) => void
}) {
  const { t, lang } = useI18n()
  const i = lang === 'fa' ? 1 : 0
  const styles: [ClipProps['textStyle'], [string, string]][] = [
    ['clean', ['Clean', 'ساده']],
    ['boxed', ['Boxed', 'کادردار']],
    ['outline', ['Outline', 'خط دور']],
    ['shadow', ['Shadow', 'سایه']],
  ]
  const places: [ClipProps['position'], [string, string]][] = [
    ['top', ['Top', 'بالا']],
    ['middle', ['Middle', 'وسط']],
    ['bottom', ['Bottom', 'پایین']],
  ]
  /**
   * The title pack, fetched once per panel.
   *
   * It comes from the backend rather than a list here because that is where
   * `validate()` runs: a preset that animated a channel the exporter cannot
   * reproduce would be refused there, and a duplicated list would drift.
   */
  const [pack, setPack] = useState<TitlePreset[]>([])
  useEffect(() => {
    titlesApi.pack().then((r) => setPack(r.presets)).catch(() => setPack([]))
  }, [])
  const apply = (preset: TitlePreset) => {
    onProps({ ...preset.props })
    // Keyframes live on the clip, not in its props, and go through the store so
    // the whole preset lands as one undoable step — the same door auto-reframe
    // uses, which is what makes Ctrl+Z take the title back in one press.
    if (preset.keyframes.length) {
      useEditor.getState().setClipKeyframes(clipId, preset.keyframes.map((k) => ({ ...k })))
    }
    message.success(lang === 'fa' ? preset.fa : preset.en)
  }

  return (
    <div className="tb__stack">
      {pack.length > 0 && (
        <Field label={t('Title pack', 'پک تایتل')}>
          <div className="tb__presets" data-testid="title-pack">
            {(['entrance', 'hold', 'caption'] as const).map((category) => (
              <div key={category} className="tb__presets-row">
                <span className="tb__presets-label">
                  {category === 'entrance'
                    ? t('Entrance', 'ورود')
                    : category === 'hold'
                      ? t('While it is on screen', 'تا وقتی روی تصویر است')
                      : t('Captions', 'زیرنویس')}
                </span>
                <div className="tb__presets-chips">
                  {pack.filter((preset) => preset.category === category).map((preset) => (
                    <button
                      key={preset.id}
                      type="button"
                      className="tb__preset"
                      data-testid={`title-preset-${preset.id}`}
                      onClick={() => apply(preset)}
                    >
                      {lang === 'fa' ? preset.fa : preset.en}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
          <p className="ce-hint" style={{ marginTop: 6 }}>
            {t(
              'Every one of these animates only the channels the export can reproduce — so what you see here is what the file will contain.',
              'همه‌ی این‌ها فقط کانال‌هایی را متحرک می‌کنند که خروجی می‌تواند بازتولید کند — پس آنچه اینجا می‌بینی همان است که در فایل خواهد بود.'
            )}
          </p>
        </Field>
      )}
      <Field label={t('Text', 'متن')}>
        <Input.TextArea
          value={text}
          autoSize={{ minRows: 1, maxRows: 3 }}
          onChange={(e) => onText(e.target.value)}
          placeholder={t('Type something…', 'چیزی بنویس…')}
        />
      </Field>
      <div className="tb__row">
        <Field label={t('Size', 'اندازه')} value={String(props.fontSize)}>
          <Slider min={18} max={140} step={1} value={props.fontSize} onChange={(v) => onProps({ fontSize: v })} />
        </Field>
        <Field label={t('Position', 'موقعیت')}>
          <Segmented
            value={props.position}
            onChange={(v) => onProps({ position: v as ClipProps['position'] })}
            options={places.map(([id, label]) => ({ value: id, label: label[i] }))}
          />
        </Field>
      </div>
      <div className="tb__row">
        <Field label={t('Style', 'سبک')}>
          <Segmented
            value={props.textStyle}
            onChange={(v) => onProps({ textStyle: v as ClipProps['textStyle'] })}
            options={styles.map(([id, label]) => ({ value: id, label: label[i] }))}
          />
        </Field>
        <Field label={t('Colour', 'رنگ')}>
          <div className="tb__colors">
            <ColorPicker
              value={props.color}
              onChangeComplete={(c) => onProps({ color: c.toHexString() })}
              size="small"
            />
            <ColorPicker
              value={props.highlight}
              onChangeComplete={(c) => onProps({ highlight: c.toHexString() })}
              size="small"
            />
            <span className="ce-hint">{t('text / highlight', 'متن / تأکید')}</span>
          </div>
        </Field>
      </div>
      <Segmented
        value={props.animateWords ? 'on' : 'off'}
        onChange={(v) => onProps({ animateWords: v === 'on' })}
        options={[
          { value: 'off', label: t('Static', 'ثابت') },
          { value: 'on', label: t('Word-by-word highlight', 'تأکید کلمه‌به‌کلمه') },
        ]}
      />
    </div>
  )
}

function PanelRatio() {
  const { t } = useI18n()
  const { aspect, setAspect } = useEditor()
  return (
    <div className="tb__stack">
      <Segmented
        value={aspect}
        onChange={(value) => setAspect(value as typeof aspect)}
        options={[
          { value: 'auto', label: t('Auto', 'خودکار') },
          { value: '9:16', label: '9:16' },
          { value: '1:1', label: '1:1' },
          { value: '4:5', label: '4:5' },
          { value: '16:9', label: '16:9' },
        ]}
      />
      <span className="ce-hint">
        {t(
          'This is the shape of the monitor and the default for export. Auto follows the first video clip.',
          'شکل مانیتور و پیش‌فرض خروجی همین است. حالت خودکار از اولین ویدیو پیروی می‌کند.'
        )}
      </span>
    </div>
  )
}

/**
 * Veed-style caption review: every word chip wears its confidence (amber when
 * the recogniser was unsure), one click edits it in place, and the LLM
 * proof-read / translate / SRT doors live here. Timings never move from text
 * edits — patchCaption rewrites text only.
 */
function PanelCaptions({ clip }: { clip: Clip }) {
  const { t } = useI18n()
  const [editIdx, setEditIdx] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const words = clip.words ?? []
  const patch = useEditor((s) => s.patchCaption)

  const setCueText = async (fn: (cues: { start: number; end: number; text: string }[]) => Promise<{ cues: { start: number; end: number; text: string; words?: { start: number; end: number; text: string; prob?: number }[] }[]; changed?: number; provider: string | null }>, label: string) => {
    const cue = [{ start: clip.start, end: clip.start + clip.duration, text: clip.text ?? '' }]
    const hide = message.loading(label, 0)
    try {
      const out = await fn(cue)
      const first = out.cues[0]
      if (first) {
        useEditor.getState().setText(clip.id, first.text)
        message.success(out.provider ? `${label} · ${out.provider}` : label)
      }
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      hide()
    }
  }

  return (
    <div className="tb__stack">
      <div className="tb__row" style={{ flexWrap: 'wrap' }}>
        {words.length === 0 && <span className="ce-hint">{t('No word timings on this caption.', 'این کپشن تایمینگ کلمه ندارد.')}</span>}
        {words.map((w, i2) => (
          <button
            key={i2}
            className="badge"
            style={{
              cursor: 'pointer',
              color: (w.prob ?? 1) < 0.5 ? 'var(--warning)' : undefined,
              borderColor: (w.prob ?? 1) < 0.5 ? 'var(--warning)' : undefined,
            }}
            title={`${((w.prob ?? 1) * 100).toFixed(0)}%`}
            onClick={() => { setEditIdx(i2); setDraft(w.text) }}
          >
            {w.text}
          </button>
        ))}
      </div>
      <div className="tb__row" style={{ flexWrap: 'wrap' }}>
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void setCueText((c) => captionsApi.refine(c), t('Proof-read', 'غلط‌گیری'))}>
          {t('Proof-read (local AI)', 'غلط‌گیری (هوش محلی)')}
        </button>
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void setCueText((c) => captionsApi.translate(c, t('English', 'انگلیسی')), t('Translate', 'ترجمه'))}>
          {t('Translate', 'ترجمه')}
        </button>
        <button
          className="ce-btn ce-btn--ghost ce-btn--sm"
          onClick={() => {
            const cues = useEditor.getState().clips
              .filter((c) => c.text !== undefined && c.src == null)
              .map((c) => ({ start: c.start, end: c.start + c.duration, text: c.text ?? '' }))
            void captionsApi.srtExport('~/CuttingEdge/exports/captions.srt', cues)
              .then(() => message.success(t('SRT saved to exports', 'SRT در exports ذخیره شد')))
              .catch((e) => message.error((e as Error).message))
          }}
        >
          {t('Export SRT', 'خروجی SRT')}
        </button>
        <button
          className="ce-btn ce-btn--ghost ce-btn--sm"
          onClick={() => {
            const all = useEditor.getState().clips
              .filter((c) => c.text !== undefined && c.src == null)
              .map((c) => ({ start: c.start, end: c.start + c.duration, text: c.text ?? '' }))
            fetch(`${backendOrigin}/api/captions/chapters`, {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ cues: all }),
            }).then((r) => r.json()).then((out) => {
              const state = useEditor.getState()
              for (const ch of out.chapters ?? []) {
                state.addCaptions([{ start: ch.start, end: Math.min(ch.start + 2.5, ch.end), text: ch.title }])
              }
              message.success(`${(out.chapters ?? []).length} ${t('chapters added', 'چپتر اضافه شد')}`)
            }).catch((e) => message.error((e as Error).message))
          }}
        >
          {t('Chapters', 'چپترها')}
        </button>
        <button
          className="ce-btn ce-btn--ghost ce-btn--sm"
          onClick={() => {
            const all = useEditor.getState().clips
              .filter((c) => c.text !== undefined && c.src == null)
              .map((c) => ({ start: c.start, end: c.start + c.duration, text: c.text ?? '' }))
            fetch(`${backendOrigin}/api/captions/hook-title`, {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ cues: all }),
            }).then((r) => r.json()).then((out) => {
              if (out.title) message.success(`${t('Hook', 'قلاب')}: ${out.title}`)
            }).catch((e) => message.error((e as Error).message))
          }}
        >
          {t('Hook title', 'تیتر قلاب')}
        </button>
      </div>
      <Modal
        open={editIdx !== null}
        onCancel={() => setEditIdx(null)}
        onOk={() => {
          if (editIdx === null) return
          const next = words.map((w, i2) => (i2 === editIdx ? { ...w, text: draft, prob: 1 } : w))
          patch(clip.id, next)
          setEditIdx(null)
        }}
        title={t('Edit word', 'ویرایش کلمه')}
      >
        <Input value={draft} onChange={(e) => setDraft(e.target.value)} autoFocus />
      </Modal>
    </div>
  )
}

/**
 * Translate & Dub door. Translate rewrites the text through the local-LLM
 * captions path; Dub sends the text to an out-of-process TTS provider and drops
 * the returned audio onto the audio lane. Without a provider the backend answers
 * a clear 409, so the button explains itself instead of sitting dead.
 */
function DubModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n()
  const [text, setText] = useState('')
  const [lang, setLang] = useState('en')
  const [busy, setBusy] = useState('')

  useEffect(() => {
    if (open) {
      const caps = useEditor.getState().clips.filter((c) => c.text !== undefined && c.src == null)
      setText(caps.map((c) => c.text).join(' ').slice(0, 500))
    }
  }, [open])

  const translate = async () => {
    if (!text.trim()) return
    setBusy('tr')
    try {
      const out = await captionsApi.translate([{ start: 0, end: 1, text }], lang)
      if (out.cues[0]) setText(out.cues[0].text)
      message.success(out.provider ? `${t('Translated', 'ترجمه شد')} · ${out.provider}` : t('Translated', 'ترجمه شد'))
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy('')
    }
  }

  const dub = async () => {
    if (!text.trim()) return
    setBusy('dub')
    try {
      const res = await fetch(`${backendOrigin}/api/providers/tts`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, lang }),
      })
      if (!res.ok) {
        const detail = (await res.json().catch(() => ({}))).detail
        throw new Error(detail ?? res.statusText)
      }
      const { path } = await res.json()
      const info = await renderApi.probe(path)
      const state = useEditor.getState()
      let lane = state.tracks.find((x) => x.kind === 'audio')
      if (!lane) {
        state.addTrack('audio')
        lane = useEditor.getState().tracks.find((x) => x.kind === 'audio')
      }
      const start = state.clips.filter((c) => c.trackId === lane!.id).reduce((a, c) => Math.max(a, c.start + c.duration), 0)
      state.addClip({
        trackId: lane!.id, start, duration: Math.max(0.5, info.duration), offset: 0,
        sourceDuration: Math.max(0.5, info.duration), src: path,
        label: t('Dub', 'دوبله'), color: '#EC4899',
      })
      message.success(t('Dub added to the audio lane', 'دوبله روی خط صوتی نشست'))
      onClose()
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy('')
    }
  }

  return (
    <Modal open={open} onCancel={onClose} footer={null} title={t('Translate & Dub', 'ترجمه و دوبله')}>
      <Input.TextArea rows={4} value={text} onChange={(e) => setText(e.target.value)} dir="auto" />
      <div className="ce-actions" style={{ marginTop: 10 }}>
        <Segmented
          value={lang}
          onChange={(v) => setLang(String(v))}
          options={[{ value: 'en', label: 'English' }, { value: 'fa', label: 'فارسی' }, { value: 'ar', label: 'عربی' }]}
        />
        <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled={busy !== ''} onClick={() => void translate()}>
          {busy === 'tr' ? t('Translating…', 'در حال ترجمه…') : t('Translate', 'ترجمه')}
        </button>
        <button className="ce-btn ce-btn--sm" disabled={busy !== ''} onClick={() => void dub()}>
          {busy === 'dub' ? t('Dubbing…', 'در حال دوبله…') : t('Dub (TTS provider)', 'دوبله (provider TTS)')}
        </button>
      </div>
    </Modal>
  )
}
