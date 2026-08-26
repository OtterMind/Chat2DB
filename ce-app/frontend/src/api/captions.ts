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
  /** 'off' | 'aligned' | 'no-engine' | 'error: ...' — whether word alignment ran */
  alignment?: string
}

/**
 * Whisper is minutes, not seconds, on a machine without CUDA — and the user's
 * machine is exactly that (`cublas64_12.dll is not found`, 0.5.3). The client's
 * 30 s default would abandon a transcription that was going perfectly well, so
 * this call carries its own budget, like the AI calls do.
 */
const TRANSCRIBE = { timeout: 20 * 60_000 }

export const captionsApi = {
  transcribe: async (path: string, language?: string, align = false): Promise<Transcription> =>
    (await api.post('/captions/transcribe', { path, language, align }, TRANSCRIBE)).data,
  status: async (): Promise<{ available: boolean; reason?: string }> =>
    (await api.get('/captions/status')).data,
  /** Is whisperX word-level alignment fetched? Honest, so the button can say. */
  alignStatus: async (): Promise<{ available: boolean; aligner: string; note: string }> =>
    (await api.get('/captions/align-status')).data,
}
