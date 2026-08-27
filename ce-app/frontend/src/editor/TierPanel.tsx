import { useEffect, useMemo, useState } from 'react'
import { Modal, message } from 'antd'
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts'
import { Activity, Wand2, Send } from 'lucide-react'
import { useEditor } from './model'
import { useI18n } from '../i18n'
import { backendOrigin } from '../api/runtime'
import { boardApi, type Arc, type HookScore, type Marker, type HookVariant } from '../api/board'
import { transcriptApi } from '../api/transcript'
import { agentApi } from '../api/agent'
import { analyzeApi } from '../api/analyze'

/**
 * The Tier 1–3 door: emotional arc + hook score + markers, transcript-first
 * editing (seek / remove fillers / jump-cut), the cut inspector, and the agent
 * command box — all in one Hybrid panel so the new senses live with the edit.
 *
 * Every number is fetched from the backend's measured endpoints and drawn with
 * the existing design tokens; no new glow, no new glass.
 */
export default function TierPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n()
  const { clips, selectedId, setPlayhead, keepRanges, removeClip, setProps, splitAtBeats } = useEditor()

  const target = clips.find((c) => c.id === selectedId && c.src) ?? clips.find((c) => c.src)
  const words = useMemo(
    () => clips.find((c) => c.words?.length)?.words ?? [],
    [clips]
  )

  const [arc, setArc] = useState<Arc | null>(null)
  const [hook, setHook] = useState<HookScore | null>(null)
  const [markers, setMarkers] = useState<Marker[]>([])
  const [explain, setExplain] = useState<{ total: number; headline: string } | null>(null)
  const [variants, setVariants] = useState<HookVariant[]>([])
  const [intensity, setIntensity] = useState(0.5)
  const [command, setCommand] = useState('')
  const [busy, setBusy] = useState('')

  const load = async () => {
    if (!target?.src) return
    setBusy('load')
    try {
      const [a, h, m, lab] = await Promise.all([
        boardApi.arc(target.src, 2).catch(() => null),
        boardApi.hook(target.src).catch(() => null),
        boardApi.markers(target.src).catch(() => null),
        boardApi.hookLab(target.src, intensity).catch(() => null),
      ])
      setArc(a)
      setHook(h)
      setMarkers(m?.markers ?? [])
      setVariants(lab?.variants ?? [])
    } finally {
      setBusy('')
    }
  }
  useEffect(() => { if (open && target?.src) void load() }, [open, target?.src]) // eslint-disable-line react-hooks/exhaustive-deps

  // Re-run just the Hook Lab when the intensity dial moves (debounced).
  useEffect(() => {
    const src = target?.src
    if (!open || !src) return
    const id = setTimeout(() => {
      boardApi.hookLab(src, intensity).then((l) => setVariants(l.variants)).catch(() => undefined)
    }, 250)
    return () => clearTimeout(id)
  }, [intensity, open, target?.src])

  const inspect = async () => {
    const clip = clips.find((c) => c.id === selectedId)
    if (!clip) { message.info(t('Select a clip to inspect.', 'یک کلیپ انتخاب کن.')); return }
    const out = await boardApi.explainCut({ start: clip.offset, end: clip.offset + clip.duration })
    setExplain({ total: out.total, headline: out.headline })
  }

  /** Jump-cut: fillers + dead silence, applied as one undoable ripple. */
  const jumpcut = async () => {
    if (!target?.src) return
    setBusy('jump')
    try {
      const sil = await analyzeApi.silence(target.src)
      const out = await transcriptApi.jumpcut({
        words: words as unknown as Record<string, unknown>[],
        silences: sil.silences,
        remove_fillers: true, remove_silence: true,
      })
      const parts = keepRanges(target.id, out.keep)
      message.success(
        t(`Jump cut removed ${out.removed.toFixed(1)}s — ${parts} segments left`,
          `جامپ‌کات ${out.removed.toFixed(1)} ثانیه حذف کرد — ${parts} قطعه ماند`)
      )
    } catch (err) { message.error((err as Error).message) } finally { setBusy('') }
  }

  const removeFillers = async () => {
    if (!words.length) { message.info(t('Transcribe first (no words on the timeline).', 'اول رونوشت بگیر.')); return }
    const out = await transcriptApi.fillers(words as unknown as Record<string, unknown>[])
    if (!out.cuts.length) { message.info(t('No filler words found.', 'تپقی پیدا نشد.')); return }
    // keep = everything except the filler ranges
    const keep = invert(out.cuts, words[words.length - 1]?.end ?? 0)
    if (target) keepRanges(target.id, keep)
    message.success(t(`Removed ${out.count} fillers`, `${out.count} تپق حذف شد`))
  }

  const runCommand = async () => {
    if (!command.trim()) return
    setBusy('agent')
    try {
      const parsed = await agentApi.nl(command)
      if (!parsed.action) { message.warning(parsed.note ?? t('Could not parse.', 'قابل فهم نبود.')); return }
      const checked = await agentApi.call(parsed.action, parsed.params)
      if (!checked.ok || !checked.action) { message.warning(checked.error ?? 'no action'); return }
      applyAction(checked.action, checked.params ?? {})
    } catch (err) { message.error((err as Error).message) } finally { setBusy('') }
  }

  const applyAction = (action: string, params: Record<string, unknown>) => {
    if (action === 'remove_clips_shorter_than' && target) {
      const min = Number(params.min_duration ?? 2)
      const short = clips.filter((c) => c.src && c.duration < min)
      short.forEach((c) => removeClip(c.id))
      message.success(t(`Removed ${short.length} short clips`, `${short.length} کلیپ کوتاه حذف شد`))
    } else if (action === 'set_speed' && selectedId) {
      setProps(selectedId, { speed: Number(params.speed ?? 1) })
      message.success(t(`Speed set to ${params.speed}x`, `سرعت ${params.speed}x شد`))
    } else if (action === 'cut_on_beat' && selectedId) {
      const n = splitAtBeats(selectedId)
      message.success(t(`Split on ${n} beats`, `روی ${n} ضرب برید`))
    } else if (action === 'remove_silence' && target) {
      void jumpcut()
    } else {
      message.info(t('Parsed; apply it from the matching tool.', 'فهمیده شد؛ از ابزار مربوطه اعمال کن.'))
    }
  }

  return (
    <Modal open={open} onCancel={onClose} width={680}
      title={<span><Activity size={14} style={{ verticalAlign: -2 }} /> {t('Signal & transcript', 'سیگنال و رونوشت')}</span>}
      footer={null}>
      {hook && (
        <div className="ce-badges" style={{ marginBottom: 8 }}>
          <span className="ce-badge" style={{ color: hook.color, borderColor: hook.color }}>
            {t('hook', 'قلاب')} {hook.score}/100 {hook.label}
          </span>
          {hook.reasons.slice(0, 2).map((r) => <span className="ce-badge" key={r}>{r}</span>)}
        </div>
      )}
      {arc?.points?.length ? (
        <div style={{ height: 120 }} dir="ltr">
          <ResponsiveContainer>
            <LineChart data={arc.points} onClick={(e) => {
              const p = (e as { activePayload?: { payload?: { t?: number } }[] })
                ?.activePayload?.[0]?.payload
              if (p?.t != null) setPlayhead(p.t)
            }}>
              <XAxis dataKey="t" hide />
              <YAxis domain={[0, 1]} hide />
              <Tooltip formatter={(v) => Number(v).toFixed(2)} />
              <Line type="monotone" dataKey="score" stroke="var(--ce-neon-cyan)" dot={false} strokeWidth={1.5} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      ) : <p className="ce-hint">{t('No measurable signal on this clip.', 'سیگنال قابل‌سنجشی روی این کلیپ نیست.')}</p>}
      {markers.length > 0 && (
        <div className="ce-badges" style={{ margin: '8px 0' }}>
          {markers.slice(0, 10).map((m, i) => (
            <button key={i} className="ce-badge" style={{ cursor: 'pointer' }}
              onClick={() => setPlayhead(m.t)} title={`${m.type} ${m.conf}`}>
              {m.type} {m.t.toFixed(1)}s
            </button>
          ))}
        </div>
      )}

      <label className="ce-hint" style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '8px 0' }}>
        {t('intensity', 'شدت')}
        <input type="range" min={0} max={1} step={0.05} value={intensity}
          onChange={(e) => setIntensity(Number(e.target.value))} style={{ flex: 1 }} />
        <span className="mono">{intensity.toFixed(2)}</span>
      </label>
      {variants.length > 0 && (
        <div className="ce-badges" style={{ margin: '0 0 8px' }}>
          {variants.map((v) => (
            <button key={v.kind} className="ce-badge" style={{ cursor: 'pointer' }}
              title={v.reasons.join(' · ')} onClick={() => setPlayhead(v.start)}>
              {v.kind} · {v.hook}
            </button>
          ))}
        </div>
      )}

      <div className="ce-actions" style={{ margin: '10px 0' }}>
        <button className="ce-btn ce-btn--sm" disabled={busy !== ''} onClick={() => void jumpcut()}>
          <Wand2 size={13} /> {busy === 'jump' ? t('Cutting…', 'در حال برش…') : t('Jump cut (fillers + silence)', 'جامپ‌کات (تپق + سکوت)')}
        </button>
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void removeFillers()}>
          {t('Remove fillers', 'حذف تپق‌ها')}
        </button>
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void inspect()}>
          {t('Why this cut?', 'چرا این برش؟')}
        </button>
      </div>
      {explain && (
        <p className="ce-hint">{t('score', 'امتیاز')} <span className="mono">{explain.total.toFixed(2)}</span> — {explain.headline}</p>
      )}

      {words.length > 0 ? (
        <div style={{ maxHeight: 120, overflow: 'auto', display: 'flex', flexWrap: 'wrap', gap: 4, margin: '8px 0' }}>
          {words.map((w, i) => (
            <button key={i} className="ce-badge" style={{ cursor: 'pointer' }} onClick={() => setPlayhead(w.start)}>
              {w.text}
            </button>
          ))}
        </div>
      ) : (
        <p className="ce-hint">{t('No transcript on the timeline yet — transcribe to edit by text.', 'هنوز رونوشتی نیست؛ برای ویرایش متن‌محور رونوشت بگیر.')}</p>
      )}

      <div className="ce-actions" style={{ marginTop: 10 }}>
        <input className="ce-input" style={{ flex: 1 }} value={command}
          placeholder={t('Ask the agent: “remove clips shorter than 2s”', 'از ایجنت بخواه: «کلیپ‌های زیر ۲ ثانیه را حذف کن»')}
          onChange={(e) => setCommand(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') void runCommand() }} />
        <button className="ce-btn ce-btn--sm" disabled={busy !== ''} onClick={() => void runCommand()}>
          <Send size={13} /> {t('Run', 'اجرا')}
        </button>
      </div>
      <span className="ce-hint" style={{ display: 'block', marginTop: 6 }} dir="ltr">
        {backendOrigin ? '' : ''}{t('The brain is also exposed as an agent protocol at /api/agent.', 'مغز به‌صورت پروتکل ایجنت در /api/agent هم در دسترس است.')}
      </span>
    </Modal>
  )
}

/** Complement of cut ranges over [0, duration] → keep ranges. */
function invert(cuts: { start: number; end: number }[], duration: number) {
  const keep: { start: number; end: number }[] = []
  let cursor = 0
  for (const c of cuts) {
    if (c.start > cursor) keep.push({ start: cursor, end: c.start })
    cursor = Math.max(cursor, c.end)
  }
  if (cursor < duration) keep.push({ start: cursor, end: duration })
  return keep
}
