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
  /** Tier 2: bundle the finished edit into a publish folder (video+captions+thumb+meta+OTIO). */
  exportPack: async (q: {
    video: string
    destination: string
    timeline?: unknown
    cues?: { start: number; end: number; text: string }[]
    meta?: Record<string, unknown>
    chapters?: { t: number; title: string }[]
    name?: string
  }) => (await api.post('/render/export-pack', q, { timeout: 120_000 })).data as {
    dir: string
    files: string[]
    count: number
  },
}

/** Streamed through the API so seeking works from a file:// page. */
export function mediaUrl(path: string) {
  return `${backendOrigin}/api/media/file?path=${encodeURIComponent(path)}`
}

export interface ProxyState {
  source: string
  status: 'idle' | 'building' | 'ready' | 'failed' | 'skipped'
  proxy: string | null
  error: string | null
}

/** Editing proxies: a small, seek-friendly copy used only for the preview. */
export const proxyApi = {
  start: async (path: string): Promise<ProxyState> => (await api.post('/media/proxy', { path })).data,
  status: async (path: string): Promise<ProxyState> =>
    (await api.get('/media/proxy', { params: { path } })).data,
}

export interface Peaks {
  duration: number
  points: number
  peaks: number[]
}

/** Waveform envelope for the timeline. */
export const peaksApi = {
  get: async (path: string, points = 600): Promise<Peaks> =>
    (await api.get('/media/peaks', { params: { path, points } })).data,
}

/** One frame of a file, for the timeline film strip. */
export function thumbUrl(path: string, at: number, height = 96) {
  return `${backendOrigin}/api/media/thumb?path=${encodeURIComponent(path)}&t=${at.toFixed(1)}&h=${height}`
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
