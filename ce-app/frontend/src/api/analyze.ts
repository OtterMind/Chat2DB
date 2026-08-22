import api from './client'

export interface TimeRange {
  start: number
  end: number
}

export interface SilenceResult {
  duration: number
  silences: TimeRange[]
  /** The inverse of `silences` — the parts worth keeping. */
  speech: TimeRange[]
}

export interface BeatResult {
  bpm: number
  beats: number[]
  confidence: number
}

export const analyzeApi = {
  beats: async (path: string): Promise<BeatResult> => (await api.post('/analyze/beats', { path })).data,
  silence: async (path: string, options?: { noise_db?: number; min_silence?: number }): Promise<SilenceResult> =>
    (await api.post('/analyze/silence', { path, ...options })).data,
  scenes: async (path: string): Promise<{ scenes: number[] }> =>
    (await api.post('/analyze/scenes', { path })).data,
}
