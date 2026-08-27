import api from './client'

export interface AgentTool { name: string; description: string; inputSchema: unknown }

export const agentApi = {
  tools: async (): Promise<{ tools: AgentTool[]; count: number }> =>
    (await api.get('/agent/tools')).data,
  nl: async (command: string) =>
    (await api.post('/agent/nl', { command })).data as {
      action: string | null
      params: Record<string, unknown>
      note?: string
    },
  call: async (action: string, params: Record<string, unknown>) =>
    (await api.post('/agent/call', { action, params })).data as {
      ok: boolean
      action?: string
      params?: Record<string, unknown>
      error?: string
    },
}
