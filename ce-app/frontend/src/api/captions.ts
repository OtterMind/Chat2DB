import api from './client'

export interface CaptionWord {
  start: number
  end: number
  text: string
}

export interface CaptionCue {
  start: number
  end: number
  text: string
  words: CaptionWord[]
}

export interface Transcription {
  language: string
  duration: number
  text: string
  words: CaptionWord[]
  cues: CaptionCue[]
}

export const captionsApi = {
  transcribe: async (path: string, language?: string): Promise<Transcription> =>
    (await api.post('/captions/transcribe', { path, language })).data,
  status: async (): Promise<{ available: boolean; reason?: string }> =>
    (await api.get('/captions/status')).data,
}
