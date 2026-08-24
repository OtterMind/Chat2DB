import api from './client'

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
  providers: async (): Promise<{ choices: string[]; available: Record<string, ProviderState> }> =>
    (await api.get('/assistant/providers')).data,
}
