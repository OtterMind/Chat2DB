import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Scissors, Copy, Trash2, Gauge, Volume2, VolumeX, Crop, Move, Droplets, Snowflake,
  Rewind, AudioLines, Sparkles, SlidersHorizontal, Music4, Type, Layers,
  Wand2, Repeat, Ratio, ChevronLeft, RotateCw, Film, Blend, Undo2, Redo2, MoveHorizontal,
} from 'lucide-react'
import { Slider, Segmented, Input, ColorPicker, message } from 'antd'
import { captionsApi } from '../api/captions'
import { useEditor, propsOf, MIN_CLIP, type Clip, type ClipProps } from './model'
import { useI18n } from '../i18n'
import { TRANSITIONS } from './transitions'
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
  | 'ratio'
  | 'soon'

interface Tool {
  id: string
  icon: ReactNode
  label: [en: string, fa: string]
  run?: () => void
  panel?: PanelId
  disabled?: boolean
  soon?: boolean
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
}: {
  onImport: () => void
  onRemoveSilence?: () => void
  onSplitScenes?: () => void
}) {
  const { t, lang } = useI18n()
  const navigate = useNavigate()
  const i = lang === 'fa' ? 1 : 0
  const [soonLabel, setSoonLabel] = useState('')

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
  ]

  const clip = clips.find((c) => c.id === selectedId) ?? null
  const props = clip ? propsOf(clip) : null

  /** Transcribe the clip under the playhead and lay captions on the text lane. */
  const generateCaptions = async () => {
    const state = useEditor.getState()
    const source =
      state.clips.find((c) => c.id === state.selectedId && c.src) ??
      state.clips.filter((c) => c.src).sort((a, b) => a.start - b.start)[0]
    if (!source?.src) {
      message.warning(t('Import media first.', 'اول یک فایل اضافه کن.'))
      return
    }
    const hide = message.loading(t('Transcribing…', 'در حال رونویسی…'), 0)
    try {
      const result = await captionsApi.transcribe(source.src)
      const count = state.addCaptions(result.cues, source.start - source.offset)
      message.success(
        t(`${count} captions added (${result.language})`, `${count} زیرنویس اضافه شد (${result.language})`)
      )
    } catch (err) {
      const detail = (err as { response?: { data?: { detail?: string }; status?: number } }).response
      message.error(
        detail?.status === 503
          ? t(
              'Speech recognition is not available in this build.',
              'تشخیص گفتار در این نسخه نصب نشده است.'
            )
          : detail?.data?.detail ?? (err as Error).message
      )
    } finally {
      hide()
    }
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
    { id: 'effects', icon: <Sparkles {...ICON} />, label: ['Effects', 'جلوه‌ها'], soon: true },
    { id: 'filters', icon: <Wand2 {...ICON} />, label: ['Filters', 'فیلترها'], panel: 'filters' },
    { id: 'adjust', icon: <SlidersHorizontal {...ICON} />, label: ['Adjust', 'تنظیم رنگ'], panel: 'adjust' },
    { id: 'ratio', icon: <Ratio {...ICON} />, label: ['Ratio', 'نسبت تصویر'], panel: 'ratio' },
    // Everything that was taken off the home screen: these act on footage, so
    // they belong next to the footage.
    ...FEATURES.filter((feature) => feature.place === 'editor').map<Tool>((feature) => {
      // Two of them already exist here for real; the rest either open their own
      // screen or admit they are not built yet.
      const local: Record<string, (() => void) | undefined> = {
        subtitles: () => void generateCaptions(),
        silence: onRemoveSilence,
      }
      const run = local[feature.id]
      return {
        id: `f-${feature.id}`,
        icon: feature.icon,
        label: feature.label,
        soon: !run && feature.badge === 'soon',
        run: run ?? (() => navigate(feature.route)),
      }
    }),
    ...(onSplitScenes
      ? [{ id: 'scenes', icon: <Film {...ICON} />, label: ['Split scenes', 'تقسیم نما'] as [string, string], run: onSplitScenes }]
      : []),
  ]

  const clipTools: Tool[] = [
    { id: 'split', icon: <Scissors {...ICON} />, label: ['Split', 'برش'], run: splitAtPlayhead },
    { id: 'timing', icon: <MoveHorizontal {...ICON} />, label: ['Trim & slip', 'تریم و لغزش'], panel: 'timing' },
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
      run: () => clip && props && setProps(clip.id, { muted: !props.muted }),
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
      ? [{ id: 'edittext', icon: <Type {...ICON} />, label: ['Edit text', 'ویرایش متن'] as [string, string], panel: 'text' as PanelId }]
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
                denoise={props.denoise}
                enhanceVoice={props.enhanceVoice}
                onChange={(patch) => setProps(clip.id, patch)}
              />
            )}
            {panel === 'text' && clip && props && (
              <PanelText
                text={clip.text ?? ''}
                props={props}
                onText={(value) => useEditor.getState().setText(clip.id, value)}
                onProps={(patch) => setProps(clip.id, patch)}
              />
            )}
            {panel === 'ratio' && <PanelRatio />}
            {panel === 'soon' && (
              <p className="ce-hint">
                {soonLabel} — {t('arriving in the next phase of the editor.', 'در فاز بعدی ویرایشگر اضافه می‌شود.')}
              </p>
            )}
          </div>
        </div>
      )}

      <div className="tb__rail">
        {tools.map((tool) => (
          <button
            key={tool.id}
            className={`tb__tool ${tool.soon ? 'is-soon' : ''}`}
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

function PanelAudio({
  denoise, enhanceVoice, onChange,
}: {
  denoise: number
  enhanceVoice: boolean
  onChange: (patch: { denoise?: number; enhanceVoice?: boolean }) => void
}) {
  const { t } = useI18n()
  return (
    <div className="tb__stack">
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
    </div>
  )
}

function PanelText({
  text, props, onText, onProps,
}: {
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
  return (
    <div className="tb__stack">
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
