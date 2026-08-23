import api from './client'

export interface EngineState {
  name: string
  installed: boolean
  running: boolean
  models: string[]
  path?: string | null
  download?: string | null
  selected: string
  enabled: boolean
}

export interface EngineTest {
  ok: boolean
  detail?: string
  model?: string
  seconds?: number
  answer?: string
  cues?: number
  language?: string
}

export const aiApi = {
  status: async (): Promise<{ ollama: EngineState; whisper: EngineState }> =>
    (await api.get('/ai/status')).data,
  test: async (): Promise<{ ollama: EngineTest; whisper: EngineTest }> => (await api.post('/ai/test')).data,
  pullModel: async (model: string): Promise<{ model: string; seconds: number }> =>
    (await api.post('/ai/ollama/pull', { model })).data,
  downloadWhisper: async (size: string): Promise<{ model: string; seconds: number }> =>
    (await api.post('/ai/whisper/download', { size })).data,
}
