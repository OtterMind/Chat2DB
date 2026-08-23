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

/**
 * Decoding a whole file: a ten-minute video is tens of seconds of FFmpeg, which
 * the 30 s default would cut off mid-scan. Measured: scene detection over a
 * 10-minute reference took 10.1 s, silence detection 3.5 s — comfortable now,
 * not comfortable on a two-hour source or a slower disk.
 */
const SCAN = { timeout: 10 * 60_000 }

export const analyzeApi = {
  beats: async (path: string): Promise<BeatResult> =>
    (await api.post('/analyze/beats', { path }, SCAN)).data,
  silence: async (path: string, options?: { noise_db?: number; min_silence?: number }): Promise<SilenceResult> =>
    (await api.post('/analyze/silence', { path, ...options }, SCAN)).data,
  scenes: async (path: string): Promise<{ scenes: number[] }> =>
    (await api.post('/analyze/scenes', { path }, SCAN)).data,
}
