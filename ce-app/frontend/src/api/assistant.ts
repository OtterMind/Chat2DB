import api from './client'
import { backendOrigin } from './runtime'

export interface AssistantPlan {
  ops: { op: string; [key: string]: unknown }[]
  explanation: string
  source: string
  warnings: string[]
  /** The dry run: what will happen, in both languages, before anything happens. */
  preview: { op: string; en: string; fa: string }[]
}

/** One thing the assistant did, with how long it took. Not a progress bar. */
export interface AssistantStep {
  en: string
  fa: string
  ms: number
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface ChatTurn {
  reply: string
  /** A dry run, when the turn was an editing request. Nothing has happened yet. */
  plan: AssistantPlan | null
  /** `ollama:qwen2.5`, `openai:gpt-4o-mini`, … or `offline`. Never hidden. */
  provider: string
  steps: AssistantStep[]
  seconds: number
}

/** One event in the streamed turn. The screen never has to guess which it is. */
export interface StreamEvent {
  kind: 'step' | 'delta' | 'done' | 'error'
  /** `step`: what happened, in both languages, and how long it took. */
  en?: string
  fa?: string
  ms?: number
  /** `delta`: more of the answer. */
  text?: string
  /** `done`: the finished turn. */
  reply?: string
  plan?: AssistantPlan | null
  provider?: string
  seconds?: number
  /** `error`: the stream ended badly, and this is why. */
  message?: string
}

export interface ProviderState {
  ready: boolean
  installed?: boolean
  enabled?: boolean
  models?: string[]
  model: string
}

export const assistantApi = {
  plan: async (prompt: string, timeline: unknown, selectedClipId: string | null): Promise<AssistantPlan> =>
    (await api.post('/assistant/plan', { prompt, timeline, selected_clip_id: selectedClipId })).data,
  capabilities: async (): Promise<{ provider: string | null; offlineRules: boolean }> =>
    (await api.get('/assistant/capabilities')).data,
  /**
   * One turn of the conversation.
   *
   * The client owns the history: a backend that remembered the conversation
   * would be a backend that loses half of it on restart. `provider` is the
   * user's choice from Settings, and the answer always says which one answered.
   */
  chat: async (
    messages: ChatMessage[],
    timeline: unknown,
    selectedClipId: string | null,
    language: 'en' | 'fa',
    provider: string
  ): Promise<ChatTurn> =>
    (
      await api.post('/assistant/chat', {
        messages,
        timeline,
        selected_clip_id: selectedClipId,
        language,
        provider,
      })
    ).data,
  providers: async (): Promise<{
    choices: string[]
    available: Record<string, ProviderState>
    /** The stored choice, so the panel and Settings cannot disagree. */
    selected: string
  }> => (await api.get('/assistant/providers')).data,
  /**
   * Remember which model answers.
   *
   * Server-side rather than per-panel, because the same brain is reachable from
   * Settings and a choice that only one door remembers is two settings.
   */
  setProvider: async (provider: string): Promise<{ provider: string }> =>
    (await api.post('/assistant/provider', { provider })).data,
  /**
   * The same turn, delivered as it happens.
   *
   * Raw `fetch` rather than the axios client, which carries a 30 s budget — the
   * exact number that produced `timeout of 30000ms exceeded` on a machine where a
   * model was thinking (STATE.md §4.13). A stream has no deadline; it ends.
   *
   * NDJSON, one event per line. A line that will not parse is skipped: half a
   * chunk is not worth losing the rest of a sentence.
   */
  chatStream: async (
    messages: ChatMessage[],
    timeline: unknown,
    selectedClipId: string | null,
    language: 'en' | 'fa',
    provider: string,
    intent: Record<string, unknown> | null,
    onEvent: (event: StreamEvent) => void
  ): Promise<void> => {
    const response = await fetch(`${backendOrigin}/api/assistant/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        messages,
        timeline,
        selected_clip_id: selectedClipId,
        language,
        provider,
        intent,
      }),
    })
    if (!response.ok || !response.body) {
      throw new Error(`the assistant did not answer (${response.status})`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        if (!line.trim()) continue
        try {
          onEvent(JSON.parse(line) as StreamEvent)
        } catch {
          /* a torn line: skip it, keep reading */
        }
      }
    }
  },
}
