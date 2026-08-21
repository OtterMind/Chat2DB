import api from './client'
import { backendOrigin } from './runtime'

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

export type Quality = 'high' | 'balanced' | 'fast'

export const renderApi = {
  start: async (
    name: string,
    timeline: unknown,
    options?: { quality?: Quality; output?: string | null }
  ): Promise<RenderState> =>
    (await api.post('/render', {
      name,
      timeline,
      quality: options?.quality ?? 'balanced',
      output: options?.output ?? null,
    })).data,
  get: async (id: string): Promise<RenderState> => (await api.get(`/render/${id}`)).data,
  probe: async (path: string): Promise<MediaInfo> => (await api.post('/render/probe', { path })).data,
}

/** Streamed through the API so seeking works from a file:// page. */
export function mediaUrl(path: string) {
  return `${backendOrigin}/api/media/file?path=${encodeURIComponent(path)}`
}

/** Native save dialog in the desktop app; null in the browser preview. */
export function saveDialog(suggestedName: string): Promise<string | null> | null {
  const bridge = (window as unknown as { cuttingEdge?: { saveDialog?: (n: string) => Promise<string | null> } })
    .cuttingEdge
  return bridge?.saveDialog ? bridge.saveDialog(suggestedName) : null
}

/** Native picker in the desktop app; null in the browser preview. */
export function pickMedia(): Promise<string[]> | null {
  const bridge = (window as unknown as { cuttingEdge?: { pickMedia?: () => Promise<string[]> } }).cuttingEdge
  return bridge?.pickMedia ? bridge.pickMedia() : null
}
