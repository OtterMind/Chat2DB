import api from './client'

/** Scanning a long recording for faces is minutes of work, not seconds. */
const SCAN = { timeout: 10 * 60_000 }

export interface ReframePlan {
  scale: number
  keyframes: { t: number; x: number }[]
  facesFound: number
  samples: number
  coverage: number
  fallback: boolean
  reason: string
}

/**
 * Auto-reframe.
 *
 * The backend answers with a camera path expressed as ordinary `x` keyframes,
 * so the move lands on the timeline where it can be seen, corrected and undone
 * — not baked into a file.
 */
export const reframeApi = {
  plan: async (path: string, width: number, height: number): Promise<ReframePlan> =>
    (await api.post('/reframe/plan', { path, width, height }, SCAN)).data,
}
