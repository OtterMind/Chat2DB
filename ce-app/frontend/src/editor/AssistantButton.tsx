import { useEffect, useRef, useState } from 'react'
import {
  Sparkles, X, CornerDownLeft, Undo2, Loader2, Check, Bot, Maximize2, Minimize2, Send, Cpu,
} from 'lucide-react'
import { message } from 'antd'
import { assistantApi, type AssistantPlan, type AssistantStep, type ChatMessage } from '../api/assistant'
import { applyPlan } from './applyPlan'
import { useEditor } from './model'
import { useI18n } from '../i18n'

const SUGGESTIONS: [en: string, fa: string][] = [
  ['Remove the silence', 'سکوت‌ها را حذف کن'],
  ['Split at every scene change', 'در هر تغییر نما برش بزن'],
  ['Add fade transitions between all clips', 'بین همه کلیپ‌ها ترنزیشن محو بگذار'],
  ['Make it 1.5x faster', 'یک‌ونیم برابر سریع‌ترش کن'],
  ['Which part is the strongest?', 'کدام بخش قوی‌تر است؟'],
  ['Export for shorts', 'برای شورتس خروجی بگیر'],
]

const PROVIDERS = ['auto', 'off', 'ollama', 'openai', 'gemini', 'anthropic'] as const
const PROVIDER_KEY = 'ce.assistant.provider'

interface Bubble {
  id: number
  role: 'user' | 'assistant'
  text: string
  /** Which model answered. Shown always — an answer with no source is a rumour. */
  provider?: string
  steps?: AssistantStep[]
  /** True while the answer is still arriving, word by word. */
  streaming?: boolean
}

/**
 * The editing assistant, as a conversation.
 *
 * Two things about it are not negotiable, and both are older than this screen:
 *
 * * **It never edits directly.** An editing request comes back as a plan from a
 *   fixed whitelist, described in the user's own language, applied only on Apply
 *   and undoable in one step. A free-form prompt has no objective score — "did
 *   it understand me?" is not measurable, and a number pretending to answer that
 *   would be theatre (`docs/CuttingEdge/BRAIN_DESIGN.md` §7).
 * * **It never hides where the answer came from.** Every reply carries its
 *   provider — `ollama:qwen2.5`, `openai:gpt-4o-mini`, or `offline` — and the
 *   steps are what actually happened, with the milliseconds. The animation is
 *   for the reading; the numbers are the truth.
 *
 * The class and test-id names (`.ai-fab`, `.ai-panel__input`,
 * `assistant-dryrun/apply/cancel`) are a contract: `scripts/playback-test.mjs`
 * drives the panel through them and asserts that Cancel changes nothing.
 */
export default function AssistantButton() {
  const { t, lang, dir } = useI18n()
  const [open, setOpen] = useState(false)
  const [full, setFull] = useState(false)
  const [prompt, setPrompt] = useState('')
  const [busy, setBusy] = useState(false)
  const [bubbles, setBubbles] = useState<Bubble[]>([])
  const [pending, setPending] = useState<AssistantPlan | null>(null)
  const [provider, setProvider] = useState<string>(
    () => localStorage.getItem(PROVIDER_KEY) ?? 'auto'
  )
  const [ready, setReady] = useState<Record<string, { ready: boolean; model: string }>>({})
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const nextId = useRef(1)

  const { selectedId, undo } = useEditor()

  useEffect(() => {
    if (!open) return
    inputRef.current?.focus()
    assistantApi
      .providers()
      .then((r) => setReady(r.available as never))
      .catch(() => setReady({}))
  }, [open])

  /** The newest line stays in view; a chat that scrolls away is a chat unread. */
  useEffect(() => {
    const node = listRef.current
    if (node) node.scrollTop = node.scrollHeight
  }, [bubbles, busy, pending])

  const push = (bubble: Omit<Bubble, 'id'>) =>
    setBubbles((list) => [...list, { ...bubble, id: nextId.current++ }])

  /**
   * One turn, read as it arrives.
   *
   * The steps appear as they happen and the words land as they are written,
   * because a bouncing dot is not evidence that anything is happening. The
   * bubble exists before the answer does, so the conversation never jumps.
   */
  const send = async (text: string) => {
    const said = text.trim()
    if (!said || busy) return
    setPrompt('')
    push({ role: 'user', text: said })

    const id = nextId.current++
    const collected: AssistantStep[] = []
    let written = ''
    const patch = (changes: Partial<Bubble>) =>
      setBubbles((list) => list.map((b) => (b.id === id ? { ...b, ...changes } : b)))

    setBubbles((list) => [...list, { id, role: 'assistant', text: '', streaming: true }])
    setBusy(true)
    try {
      const state = useEditor.getState()
      const history: ChatMessage[] = [
        ...bubbles.map((b) => ({ role: b.role, content: b.text })),
        { role: 'user', content: said },
      ]
      await assistantApi.chatStream(
        history,
        { tracks: state.tracks, clips: state.clips, transitions: state.transitions },
        selectedId,
        lang === 'fa' ? 'fa' : 'en',
        provider,
        (event) => {
          if (event.kind === 'step') {
            collected.push({ en: event.en ?? '', fa: event.fa ?? '', ms: event.ms ?? 0 })
            patch({ steps: [...collected] })
          } else if (event.kind === 'delta') {
            written += event.text ?? ''
            patch({ text: written })
          } else if (event.kind === 'done') {
            patch({
              text: event.reply || written,
              provider: event.provider,
              steps: [...collected],
              streaming: false,
            })
            if (event.plan) setPending(event.plan)
          } else if (event.kind === 'error') {
            patch({ text: event.message ?? t('The answer stopped', 'پاسخ نیمه‌کاره ماند'),
                    provider: 'offline', streaming: false })
          }
        }
      )
    } catch (err) {
      patch({ text: (err as Error).message, provider: 'offline', streaming: false })
    } finally {
      setBusy(false)
    }
  }

  const applyPending = async () => {
    if (!pending) return
    setBusy(true)
    try {
      const result = await applyPlan(pending.ops, selectedId)
      if (result.applied.length) {
        message.success(t(`${result.applied.length} change(s) applied — Ctrl+Z undoes them all`,
          `${result.applied.length} تغییر اعمال شد — با Ctrl+Z همه برمی‌گردند`))
      }
      for (const warning of pending.warnings) message.warning(warning)
      // An operation that could not run is not a silent no-op: the user asked for
      // it, so they are told which part did not happen.
      for (const skipped of result.skipped) message.warning(skipped)
      setPending(null)
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <button
        className="ai-fab"
        aria-label={t('Editing assistant', 'دستیار تدوین')}
        onClick={() => setOpen((v) => !v)}
      >
        <Sparkles size={20} />
      </button>

      {open && (
        <section
          className={`ai-chat ${full ? 'ai-chat--full' : ''}`}
          data-testid="assistant-panel"
          dir={dir}
        >
          <header className="ai-chat__head">
            <Bot size={16} />
            <strong>{t('Editing assistant', 'دستیار تدوین')}</strong>
            <label className="ai-chat__engine" title={t('Which model answers', 'کدام مدل جواب می‌دهد')}>
              <Cpu size={12} />
              <select
                value={provider}
                data-testid="assistant-provider"
                onChange={(event) => {
                  setProvider(event.target.value)
                  localStorage.setItem(PROVIDER_KEY, event.target.value)
                }}
              >
                {PROVIDERS.map((name) => (
                  <option key={name} value={name}>
                    {name === 'auto'
                      ? t('Automatic', 'خودکار')
                      : name === 'off'
                        ? t('Offline only', 'فقط آفلاین')
                        : name}
                    {ready[name]?.ready ? ' ●' : ''}
                  </option>
                ))}
              </select>
            </label>
            <button
              className="ai-chat__icon"
              data-testid="assistant-expand"
              title={full ? t('Small window', 'پنجرهٔ کوچک') : t('Full screen', 'تمام‌صفحه')}
              onClick={() => setFull((v) => !v)}
            >
              {full ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
            </button>
            <button className="ai-chat__icon" onClick={() => setOpen(false)} aria-label={t('Close', 'بستن')}>
              <X size={15} />
            </button>
          </header>

          <div className="ai-chat__body" ref={listRef}>
            {bubbles.length === 0 && (
              <p className="ai-chat__empty">
                {t(
                  'Ask in one sentence — “remove the silence”, “make it faster”, “which part is the strongest?”. An edit is shown to you first and applied only when you press Apply.',
                  'در یک جمله بپرس — «سکوت‌ها را حذف کن»، «تندترش کن»، «کدام بخش قوی‌تر است؟». هر تدوین اول به تو نشان داده می‌شود و فقط با «اعمال» انجام می‌شود.'
                )}
              </p>
            )}

            {bubbles.map((bubble) => (
              <article
                key={bubble.id}
                className={`ai-msg ai-msg--${bubble.role}`}
                data-testid={`assistant-msg-${bubble.role}`}
              >
                <div className="ai-msg__text">
                  {bubble.text}
                  {bubble.streaming && <span className="ai-caret" aria-hidden="true" />}
                </div>
                {bubble.provider && (
                  <footer className="ai-msg__src">
                    <span dir="ltr">{bubble.provider}</span>
                    {(bubble.steps ?? []).map((step, index) => (
                      <span key={index} title={`${step.ms} ms`}>
                        {lang === 'fa' ? step.fa : step.en}
                      </span>
                    ))}
                  </footer>
                )}
              </article>
            ))}

            {bubbles.some((bubble) => bubble.streaming && !bubble.text) && (
              <div className="ai-msg ai-msg--assistant ai-msg--thinking" data-testid="assistant-thinking">
                <span className="ai-dot" />
                <span className="ai-dot" />
                <span className="ai-dot" />
              </div>
            )}

            {pending && (
              <div className="ai-plan" data-testid="assistant-dryrun">
                <strong>{t('What will happen — nothing has yet', 'چه اتفاقی می‌افتد — هنوز هیچ‌چیز نشده')}</strong>
                <ul>
                  {pending.preview.map((line) => (
                    <li key={line.op + line.en}>{lang === 'fa' ? line.fa : line.en}</li>
                  ))}
                </ul>
                {pending.warnings.map((warning) => (
                  <p key={warning} className="ai-plan__warn">{warning}</p>
                ))}
                <div className="ai-plan__actions">
                  <button className="ce-btn ce-btn--sm" data-testid="assistant-apply" onClick={() => void applyPending()}>
                    <Check size={14} /> {t('Apply', 'اعمال')}
                  </button>
                  <button
                    className="ce-btn ce-btn--ghost ce-btn--sm"
                    data-testid="assistant-cancel"
                    onClick={() => setPending(null)}
                  >
                    {t('Cancel', 'انصراف')}
                  </button>
                  <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => undo()}>
                    <Undo2 size={14} /> {t('Undo', 'برگشت')}
                  </button>
                </div>
              </div>
            )}
          </div>

          <div className="ai-chat__sug">
            {SUGGESTIONS.map(([en, fa]) => (
              <button key={en} type="button" disabled={busy} onClick={() => void send(lang === 'fa' ? fa : en)}>
                {lang === 'fa' ? fa : en}
              </button>
            ))}
          </div>

          <div className="ai-panel__input">
            <input
              ref={inputRef}
              value={prompt}
              placeholder={t('Say what you want…', 'بگو چه می‌خواهی…')}
              onChange={(event) => setPrompt(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void send(prompt)
              }}
            />
            <button
              aria-label={t('Send', 'بفرست')}
              disabled={busy || !prompt.trim()}
              onClick={() => void send(prompt)}
            >
              {busy ? <Loader2 size={15} className="ce-spin" /> : <Send size={15} />}
            </button>
            <kbd><CornerDownLeft size={11} /></kbd>
          </div>
        </section>
      )}
    </>
  )
}
