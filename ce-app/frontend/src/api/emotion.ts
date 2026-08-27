import api from './client'

/** One row of "what can weigh in, and is it there" — shown, never assumed. */
export interface EmotionSource {
  id: string
  name: string
  active: boolean
  licence: string
  detail: string
}

export interface EmotionStatus {
  enabled: boolean
  maxWeight: number
  sources: EmotionSource[]
  faceModel: string
  faceAvailable: boolean
}

export interface EmotionPeak {
  t: number
  joy: number
  crowd: number
  voiced: number
  whoosh: number
  speech: number
}

export interface EmotionPreview {
  duration: number
  frames: number
  meanJoy: number
  peaks: EmotionPeak[]
  sources: EmotionSource[]
}

export const emotionApi = {
  status: async (): Promise<EmotionStatus> => (await api.get('/emotion/status')).data,
  enable: async (enabled: boolean): Promise<{ enabled: boolean }> =>
    (await api.post('/emotion/enable', { enabled })).data,
  /** The numbers, in the open: which moments read as a reaction, and why. */
  preview: async (path: string, count = 12): Promise<EmotionPreview> =>
    (await api.post('/emotion/preview', { path, count }, { timeout: 300_000 })).data,
  fetchFaceModel: async (): Promise<{ path: string }> =>
    (await api.post('/emotion/face-model/fetch', {}, { timeout: 300_000 })).data,
}
