import api from './client'

export interface VisionStatus {
  enabled: boolean
  running: boolean
  visionPulled: string | null
  pulled: string[]
  candidates: string[]
  ready: boolean
}

export interface VisionPreview {
  duration: number
  ready: boolean
  model: string | null
  /** Per-moment scores 0..1, or null when no vision model is listening. */
  scores: Record<string, number> | null
}

export const visionApi = {
  status: async (): Promise<VisionStatus> => (await api.get('/vision/status')).data,
  enable: async (enabled: boolean): Promise<{ enabled: boolean }> =>
    (await api.post('/vision/enable', { enabled })).data,
  preview: async (path: string, count = 6): Promise<VisionPreview> =>
    (await api.post('/vision/preview', { path, count }, { timeout: 300_000 })).data,
}
