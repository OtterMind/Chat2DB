import api from './client'

export interface RenderState {
  id: string
  status: 'running' | 'done' | 'failed'
  progress: number
  output: string
  error: string | null
  duration: number
}

export interface MediaInfo {
  path: string
  duration: number
  width: number
  height: number
  fps: number
  has_audio: boolean
  has_video: boolean
}

export const renderApi = {
  start: async (name: string, timeline: unknown): Promise<RenderState> =>
    (await api.post('/render', { name, timeline })).data,
  get: async (id: string): Promise<RenderState> => (await api.get(`/render/${id}`)).data,
  probe: async (path: string): Promise<MediaInfo> => (await api.post('/render/probe', { path })).data,
}

/** Native picker in the desktop app; null in the browser preview. */
export function pickMedia(): Promise<string[]> | null {
  const bridge = (window as unknown as { cuttingEdge?: { pickMedia?: () => Promise<string[]> } }).cuttingEdge
  return bridge?.pickMedia ? bridge.pickMedia() : null
}
