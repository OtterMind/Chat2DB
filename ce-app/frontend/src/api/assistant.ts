import api from './client'

export interface AssistantPlan {
  ops: { op: string; [key: string]: unknown }[]
  explanation: string
  source: string
  warnings: string[]
}

export const assistantApi = {
  plan: async (prompt: string, timeline: unknown, selectedClipId: string | null): Promise<AssistantPlan> =>
    (await api.post('/assistant/plan', { prompt, timeline, selected_clip_id: selectedClipId })).data,
  capabilities: async (): Promise<{ provider: string | null; offlineRules: boolean }> =>
    (await api.get('/assistant/capabilities')).data,
}
