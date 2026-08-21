import { useEffect, useRef, useState } from 'react'
import { Sparkles, X, CornerDownLeft, Undo2, Loader2 } from 'lucide-react'
import { message } from 'antd'
import { assistantApi } from '../api/assistant'
import { applyPlan } from './applyPlan'
import { useEditor } from './model'
import { useI18n } from '../i18n'

const SUGGESTIONS: [en: string, fa: string][] = [
  ['Remove the silence', 'سکوت‌ها را حذف کن'],
  ['Split at every scene change', 'در هر تغییر نما برش بزن'],
  ['Add fade transitions between all clips', 'بین همه کلیپ‌ها ترنزیشن محو بگذار'],
  ['Make it 1.5x faster', 'یک‌ونیم برابر سریع‌ترش کن'],
  ['Trim to 30 seconds', 'به ۳۰ ثانیه کوتاهش کن'],
  ['Export for shorts', 'برای شورتس خروجی بگیر'],
]

/**
 * The editing assistant.
 *
 * It never edits directly: the backend turns a sentence into operations from a
 * fixed whitelist, and `applyPlan` validates and applies them as ordinary
 * undoable steps — so anything it does can be reverted with one click.
 */
export default function AssistantButton() {
  const { t, lang } = useI18n()
  const i = lang === 'fa' ? 1 : 0
  const [open, setOpen] = useState(false)
  const [prompt, setPrompt] = useState('')
  const [busy, setBusy] = useState(false)
  const [log, setLog] = useState<{ text: string; ok: boolean }[]>([])
  const [provider, setProvider] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const { selectedId, undo } = useEditor()

  useEffect(() => {
    if (!open) return
    inputRef.current?.focus()
    assistantApi
      .capabilities()
      .then((c) => setProvider(c.provider))
      .catch(() => setProvider(null))
  }, [open])

  const run = async (text: string) => {
    if (!text.trim() || busy) return
    setBusy(true)
    try {
      const state = useEditor.getState()
      const plan = await assistantApi.plan(
        text,
        { tracks: state.tracks, clips: state.clips, transitions: state.transitions },
        selectedId
      )

      if (!plan.ops.length) {
        setLog((l) => [{ text: plan.explanation || t('Nothing to do', 'کاری برای انجام نبود'), ok: false }, ...l])
        return
      }

      const outcome = await applyPlan(plan.ops, selectedId)
      const summary = [
        outcome.applied.length ? `✓ ${outcome.applied.join(', ')}` : '',
        outcome.skipped.length ? `• ${t('skipped', 'رد شد')}: ${outcome.skipped.join(', ')}` : '',
      ]
        .filter(Boolean)
        .join('  ')

      setLog((l) => [{ text: summary || plan.explanation, ok: outcome.applied.length > 0 }, ...l])
      if (outcome.applied.length) message.success(t('Applied', 'اعمال شد'))
      setPrompt('')
    } catch (err) {
      setLog((l) => [{ text: (err as Error).message, ok: false }, ...l])
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <button
        className={`ai-fab ${open ? 'is-open' : ''}`}
        onClick={() => setOpen((v) => !v)}
        title={t('Editing assistant', 'دستیار تدوین')}
        aria-label={t('Editing assistant', 'دستیار تدوین')}
      >
        {open ? <X size={20} /> : <Sparkles size={20} />}
      </button>

      {open && (
        <div className="ai-panel">
          <div className="ai-panel__head">
            <strong>{t('Editing assistant', 'دستیار تدوین')}</strong>
            <span className="ce-badge ce-badge--muted">
              {provider ? provider : t('offline rules', 'قواعد آفلاین')}
            </span>
          </div>

          <div className="ai-panel__input">
            <input
              ref={inputRef}
              value={prompt}
              disabled={busy}
              placeholder={t('Tell me what to change…', 'بگو چه تغییری بدهم…')}
              onChange={(e) => setPrompt(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && run(prompt)}
            />
            <button className="ce-btn ce-btn--sm" disabled={busy} onClick={() => run(prompt)}>
              {busy ? <Loader2 size={15} className="ce-spin" /> : <CornerDownLeft size={15} />}
            </button>
          </div>

          <div className="ai-panel__chips">
            {SUGGESTIONS.map((s) => (
              <button key={s[0]} className="ai-chip" disabled={busy} onClick={() => run(s[i])}>
                {s[i]}
              </button>
            ))}
          </div>

          {log.length > 0 && (
            <div className="ai-panel__log">
              {log.slice(0, 5).map((entry, index) => (
                <p key={index} className={entry.ok ? 'is-ok' : 'is-warn'}>
                  {entry.text}
                </p>
              ))}
              <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => undo()}>
                <Undo2 size={14} /> {t('Undo last step', 'واگرد آخرین مرحله')}
              </button>
            </div>
          )}

          <p className="ce-hint">
            {t(
              'Every change is a normal undoable step — nothing is written to your files.',
              'هر تغییر یک مرحله‌ی قابل واگرد است و چیزی روی فایل‌های تو نوشته نمی‌شود.'
            )}
          </p>
        </div>
      )}
    </>
  )
}
