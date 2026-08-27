import api from './client'

export interface MulticamAngle {
  path: string
  duration: number
  has_audio: boolean
}

export interface MulticamAlign {
  ok: boolean
  method: string
  /** Seconds; positive means that angle started later than the first. */
  offsets: number[]
  /** Normalised correlation at the best lag: 1.0 = the same sound. */
  confidence: number[]
  notes: string[]
  angles: MulticamAngle[]
}

export interface MulticamSegment {
  start: number
  end: number
  angle: number
  src: string
  /** Where in that angle's own file the segment lives. */
  offset: number
}

export interface MulticamPlan {
  ok: boolean
  mode?: string
  dwell?: number
  segments: MulticamSegment[]
  switches?: number
  share?: number[]
  offsets?: number[]
  span?: { start: number; end: number }
  notes: string[]
}

export const multicamApi = {
  align: async (paths: string[]): Promise<MulticamAlign> =>
    (await api.post('/multicam/align', { paths }, { timeout: 300_000 })).data,
  plan: async (
    paths: string[],
    offsets: number[],
    mode: 'balanced' | 'speech' | 'crowd',
    dwell: number
  ): Promise<MulticamPlan> =>
    (await api.post('/multicam/plan', { paths, offsets, mode, dwell }, { timeout: 300_000 })).data,
}
