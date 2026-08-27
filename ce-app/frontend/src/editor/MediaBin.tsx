import { useMemo, useState } from 'react'
import { FolderOpen, Film, AudioLines, Type, Clapperboard, Layers, Activity } from 'lucide-react'
import { Modal, message } from 'antd'
import { useEditor, formatTimecode } from './model'
import { useI18n } from '../i18n'
import { backendOrigin } from '../api/runtime'
import { multicamApi, type MulticamAlign, type MulticamPlan } from '../api/multicam'
import TierPanel from './TierPanel'
import { renderApi } from '../api/render'

/**
 * B3 — the multi-cam door.
 *
 * Two phones on tripods never start together, so the first thing this modal does
 * is *measure* the gap between the angles (audio cross-correlation) and show it
 * with the confidence that came out of the same measurement. Only then does it
 * propose a switch plan, and applying it is the user's decision — the plan is
 * listed segment by segment with the reason it exists, not silently dropped on
 * the timeline.
 */
function MulticamModal({ open, onClose, sources }: {
  open: boolean
  onClose: () => void
  sources: { src: string; label: string }[]
}) {
  const { t } = useI18n()
  const [picked, setPicked] = useState<string[]>([])
  const [align, setAlign] = useState<MulticamAlign | null>(null)
  const [plan, setPlan] = useState<MulticamPlan | null>(null)
  const [mode, setMode] = useState<'balanced' | 'speech' | 'crowd'>('balanced')
  const [dwell, setDwell] = useState(1.2)
  const [busy, setBusy] = useState('')

  const toggle = (src: string) =>
    setPicked((prev) => (prev.includes(src) ? prev.filter((p) => p !== src) : [...prev, src]))

  const doAlign = async () => {
    setBusy('align')
    try {
      const out = await multicamApi.align(picked)
      setAlign(out)
      setPlan(null)
      if (!out.ok && out.notes[0]) message.warning(out.notes[0])
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy('')
    }
  }

  const doPlan = async () => {
    setBusy('plan')
    try {
      setPlan(await multicamApi.plan(picked, align?.offsets ?? [], mode, dwell))
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy('')
    }
  }

  /** Put the plan on the timeline: one clip per segment, on the first video lane. */
  const apply = async () => {
    if (!plan?.segments.length) return
    setBusy('apply')
    try {
      const editor = useEditor.getState()
      const lane = editor.tracks.find((x) => x.kind === 'video')?.id ?? 'v1'
      const start = editor.clips
        .filter((c) => c.trackId === lane)
        .reduce((acc, c) => Math.max(acc, c.start + c.duration), 0)
      const durations = new Map<string, { duration: number; width: number; height: number }>()
      let cursor = start
      for (const segment of plan.segments) {
        let info = durations.get(segment.src)
        if (!info) {
          const probed = await renderApi.probe(segment.src)
          info = { duration: probed.duration, width: probed.width, height: probed.height }
          durations.set(segment.src, info)
        }
        editor.addClip({
          trackId: lane,
          start: cursor,
          duration: segment.end - segment.start,
          offset: Math.min(segment.offset, Math.max(0, info.duration - (segment.end - segment.start))),
          sourceDuration: Math.max(0.5, info.duration),
          width: info.width || undefined,
          height: info.height || undefined,
          src: segment.src,
          label: `A${segment.angle + 1} · ${segment.src.split(/[\\/]/).pop() ?? ''}`.slice(0, 32),
          color: ['#3B82F6', '#A855F7', '#10F0A0', '#FFB800'][segment.angle % 4],
        })
        cursor += segment.end - segment.start
      }
      message.success(
        t(`${plan.segments.length} segments on the timeline`, `${plan.segments.length} قطعه روی تایم‌لاین نشست`)
      )
      onClose()
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy('')
    }
  }

  return (
    <Modal open={open} onCancel={onClose} width={620} title={t('Multi-cam', 'چنددوربینی')}
      footer={
        <span className="ce-actions">
          <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled={picked.length < 2 || busy !== ''} onClick={() => void doAlign()}>
            {busy === 'align' ? t('Measuring…', 'در حال سنجش…') : t('Line the angles up', 'هم‌ترازی زاویه‌ها')}
          </button>
          <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled={!align || busy !== ''} onClick={() => void doPlan()}>
            {busy === 'plan' ? t('Planning…', 'در حال ساخت پلن…') : t('Build the switch plan', 'ساخت پلن سوئیچ')}
          </button>
          <button className="ce-btn ce-btn--sm" disabled={!plan?.segments.length || busy !== ''} onClick={() => void apply()}>
            {busy === 'apply' ? t('Applying…', 'در حال اعمال…') : t('Put it on the timeline', 'روی تایم‌لاین بگذار')}
          </button>
        </span>
      }>
      <p className="ce-hint">
        {t(
          'Pick two or more angles of the same moment. The offset between them is measured from their audio; the switch follows whoever is most alive — the talker, the crowd, or a balance of both — and never cuts faster than the dwell.',
          'دو زاویه یا بیشتر از یک لحظه را انتخاب کن. اختلاف زمانی‌شان از صدایشان سنجیده می‌شود؛ سوئیچ دنبال زنده‌ترین زاویه می‌رود — گوینده، جمعیت، یا ترکیبی از هر دو — و هرگز تندتر از مکث تعیین‌شده کات نمی‌زند.'
        )}
      </p>
      <div className="ce-kv" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
        {sources.map((row) => (
          <button key={row.src} className="ce-rowline" style={{ textAlign: 'start' }} onClick={() => toggle(row.src)}>
            <span className={`ce-dot ${picked.includes(row.src) ? 'ce-dot--done' : 'ce-dot--off'}`} />
            <strong>{row.label}</strong>
            <span className="ce-hint" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }} dir="ltr">{row.src}</span>
          </button>
        ))}
        {sources.length < 2 && (
          <span className="ce-hint">{t('The timeline holds fewer than two sources.', 'روی تایم‌لاین کمتر از دو منبع هست.')}</span>
        )}
      </div>

      {align && (
        <div className="ce-kv" style={{ marginTop: 12, flexDirection: 'column', alignItems: 'stretch' }}>
          <span className="ce-hint">
            {t('method', 'روش')}: <Num2>{align.method}</Num2>
          </span>
          {align.offsets.map((offset, index) => (
            <span key={align.angles[index]?.path ?? index} dir="ltr">
              A{index + 1} <Num2>{offset >= 0 ? '+' : ''}{offset.toFixed(2)}s</Num2>{' '}
              {t('match', 'تطابق')} <Num2>{align.confidence[index].toFixed(2)}</Num2>
            </span>
          ))}
          {align.notes.map((note) => <span key={note} className="ce-hint">{note}</span>)}
        </div>
      )}

      <div className="ce-actions" style={{ marginTop: 12 }}>
        <select className="ce-input" value={mode} onChange={(e) => setMode(e.target.value as typeof mode)} style={{ width: 'auto' }}>
          <option value="balanced">{t('balanced', 'متعادل')}</option>
          <option value="speech">{t('who talks', 'هرکس حرف می‌زند')}</option>
          <option value="crowd">{t('the crowd', 'واکنش جمعیت')}</option>
        </select>
        <label className="ce-hint" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {t('dwell', 'حداقل مکث')}
          <input className="ce-input" type="number" min={0.4} max={10} step={0.1} value={dwell}
            onChange={(e) => setDwell(Number(e.target.value))} style={{ width: 74 }} />
          s
        </label>
      </div>

      {plan && (
        <div className="ce-kv" style={{ marginTop: 12, flexDirection: 'column', alignItems: 'stretch' }}>
          {plan.notes.map((note) => <span key={note} className="ce-hint">{note}</span>)}
          {plan.segments.slice(0, 12).map((segment) => (
            <span key={`${segment.start}-${segment.angle}`} dir="ltr">
              {formatTimecode(segment.start)} → {formatTimecode(segment.end)} · A{segment.angle + 1}
            </span>
          ))}
          {plan.segments.length > 12 && (
            <span className="ce-hint">{t('and more', 'و بیشتر')}… {plan.segments.length - 12}</span>
          )}
        </div>
      )}
    </Modal>
  )
}

/** The mono badge, without importing the whole Page shell into the editor. */
function Num2({ children }: { children: React.ReactNode }) {
  return <span className="mono">{children}</span>
}

/**
 * The library pane: what the project already holds, one row per source file.
 * A real NLE's media bin, minus the weight: rows are derived from the timeline
 * itself, so the bin can never disagree with the edit.
 */
export default function MediaBin({ onImport }: { onImport: () => void }) {
  const { t } = useI18n()
  const [board, setBoard] = useState<number[]>([])
  const [boardPath, setBoardPath] = useState('')
  const [multicam, setMulticam] = useState(false)
  const [tiers, setTiers] = useState(false)

  /** B7: the ten most informative frames of the first video clip. */
  const storyboard = async () => {
    const src = useEditor.getState().clips.find((c) => c.src)?.src
    if (!src) { message.warning(t('Import media first.', 'اول یک فایل اضافه کن.')); return }
    try {
      const out = await fetch(`${backendOrigin}/api/media/storyboard`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: src, count: 10 }),
      }).then((r) => r.json())
      setBoardPath(src)
      setBoard(out.times ?? [])
    } catch (err) { message.error((err as Error).message) }
  }
  const { clips, select } = useEditor()

  const rows = useMemo(() => {
    const bySrc = new Map<string, { src: string; label: string; color: string; dur: number; id: string; kind: string }>()
    for (const c of clips) {
      if (!c.src) continue
      const row = bySrc.get(c.src)
      if (row) row.dur += c.duration
      else bySrc.set(c.src, { src: c.src, label: c.label, color: c.color, dur: c.duration, id: c.id, kind: c.src.match(/\.(mp3|m4a|wav|aac|ogg)$/i) ? 'audio' : 'video' })
    }
    return [...bySrc.values()]
  }, [clips])

  const videos = rows.filter((r) => r.kind === 'video')

  return (
    <aside className="ed__bin" aria-label={t('Library', 'کتابخانه')}>
      <div className="ed__bin-head">
        <strong>{t('Library', 'کتابخانه')}</strong>
        <span style={{ display: 'flex', gap: 6 }}>
          <button className="ed__btn ed__btn--sm" onClick={() => void storyboard()} title={t('Storyboard of the first clip', 'استوری‌برد کلیپ اول')}>
            <Clapperboard size={13} />
          </button>
          <button className="ed__btn ed__btn--sm" onClick={() => setMulticam(true)}
            title={t('Multi-cam switcher', 'سوئیچر چنددوربینی')}>
            <Layers size={13} />
          </button>
          <button className="ed__btn ed__btn--sm" onClick={() => setTiers(true)}
            title={t('Signal & transcript (tiers 1-3)', 'سیگنال و رونوشت (لایه‌های ۱-۳)')}>
            <Activity size={13} />
          </button>
          <button className="ed__btn ed__btn--sm" onClick={onImport} title={t('Import media', 'افزودن رسانه')}>
            <FolderOpen size={13} /> {t('Add', 'افزودن')}
          </button>
        </span>
      </div>
      <MulticamModal open={multicam} onClose={() => setMulticam(false)}
        sources={videos.map((v) => ({ src: v.src, label: v.label }))} />
      <TierPanel open={tiers} onClose={() => setTiers(false)} />
      <Modal open={board.length > 0} onCancel={() => setBoard([])} footer={null} title={t('Storyboard', 'استوری‌برد')}>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {board.map((time) => (
            <img key={time} width={120} style={{ borderRadius: 8, border: '1px solid var(--ce-border)' }}
              src={`${backendOrigin}/api/media/thumb?path=${encodeURIComponent(boardPath)}&t=${time}&h=96`} alt="" />
          ))}
        </div>
      </Modal>
      {rows.length === 0 && (
        <p className="ce-hint" style={{ padding: '10px 12px' }}>
          {t('Nothing here yet — add a file and it appears in the bin.', 'هنوز خالی است — فایلی اضافه کن تا اینجا دیده شود.')}
        </p>
      )}
      {rows.map((r) => (
        <button key={r.src} className="ed__binrow" onClick={() => select(r.id)} title={r.src}>
          {r.kind === 'video' ? (
            <img
              className="ed__binthumb"
              src={`${backendOrigin}/api/media/thumb?path=${encodeURIComponent(r.src)}&t=0.5&h=56`}
              alt=""
              loading="lazy"
            />
          ) : (
            <span className="ed__binthumb ed__binthumb--audio">
              {r.kind === 'audio' ? <AudioLines size={16} /> : <Film size={16} />}
            </span>
          )}
          <span className="ed__binmeta">
            <strong>{r.label}</strong>
            <span className="mono" dir="ltr">{formatTimecode(r.dur)}</span>
          </span>
          <span className="ed__bindot" style={{ background: r.color }} />
        </button>
      ))}
      <p className="ce-hint" style={{ padding: '8px 12px' }}>
        <Type size={11} /> {t('Text clips live on the timeline, not in the bin.', 'کلیپ‌های متن روی تایم‌لاین‌اند، نه در بین.')}
      </p>
    </aside>
  )
}
