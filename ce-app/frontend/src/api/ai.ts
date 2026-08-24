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

/**
 * These calls are slow by nature: a 7B model answering on a CPU, or a speech
 * model being downloaded. The client's 30 s default turned a working machine
 * into "timeout of 30000ms exceeded", so every AI call carries its own budget.
 */
const SLOW = { timeout: 15 * 60 * 1000 }

export const aiApi = {
  status: async (): Promise<{ ollama: EngineState; whisper: EngineState }> =>
    (await api.get('/ai/status', { timeout: 20_000 })).data,
  test: async (): Promise<{ ollama: EngineTest; whisper: EngineTest }> =>
    (await api.post('/ai/test', {}, SLOW)).data,
  /** Long downloads run as tasks so the screen can show a bar. */
  startPull: async (model: string): Promise<{ id: string }> =>
    (await api.post('/ai/ollama/pull/start', { model })).data,
  startWhisper: async (size: string): Promise<{ id: string }> =>
    (await api.post('/ai/whisper/download/start', { size })).data,
  startCuda: async (): Promise<{ id: string }> => (await api.post('/ai/cuda/install', {})).data,
  catalogue: async (): Promise<{
    vramGb: number | null
    models: { name: string; job: string; gb: number; vramGb: number; why: string; installed: boolean; fits: boolean | null; note: string }[]
  }> => (await api.get('/ai/models', { timeout: 60_000 })).data,
  pullModel: async (model: string): Promise<{ model: string; seconds: number }> =>
    (await api.post('/ai/ollama/pull', { model }, SLOW)).data,
  downloadWhisper: async (size: string): Promise<{ model: string; seconds: number }> =>
    (await api.post('/ai/whisper/download', { size }, SLOW)).data,
  selectModel: async (model: string): Promise<{ model: string }> =>
    (await api.post('/ai/ollama/select', { model })).data,
}
