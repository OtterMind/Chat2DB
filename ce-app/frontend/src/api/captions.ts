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

/**
 * Whisper is minutes, not seconds, on a machine without CUDA — and the user's
 * machine is exactly that (`cublas64_12.dll is not found`, 0.5.3). The client's
 * 30 s default would abandon a transcription that was going perfectly well, so
 * this call carries its own budget, like the AI calls do.
 */
const TRANSCRIBE = { timeout: 20 * 60_000 }

export const captionsApi = {
  transcribe: async (path: string, language?: string): Promise<Transcription> =>
    (await api.post('/captions/transcribe', { path, language }, TRANSCRIBE)).data,
  status: async (): Promise<{ available: boolean; reason?: string }> =>
    (await api.get('/captions/status')).data,
}
