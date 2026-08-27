import api from './client'

export interface ArcPoint { t: number; score: number }
export interface Arc { fps: number; points: ArcPoint[]; terms: string[]; duration: number }

export interface HookScore {
  score: number
  label: string
  color: string
  window: { start: number; end: number }
  reasons: string[]
}

export interface ClipCard {
  id: string
  start: number
  end: number
  score: number
  hook: number
  hookLabel: string
  reason: string
  thumb: number
}

export interface Marker { t: number; type: 'spike' | 'rep' | 'crowd'; conf: number }

export interface HookVariant {
  kind: string
  label: string
  params: Record<string, unknown>
  start: number
  end: number
  hook: number
  hookLabel: string
  reasons: string[]
}

export interface ExplainCut {
  total: number
  terms: Record<string, number>
  weights: Record<string, number>
  skipped: string[]
  headline: string
}

const LONG = { timeout: 300_000 }

export const boardApi = {
  arc: async (path: string, fps = 2): Promise<Arc> =>
    (await api.post('/board/arc', { path, fps }, LONG)).data,
  hook: async (path: string, start = 0, end = 3): Promise<HookScore> =>
    (await api.post('/board/hook', { path, start, end }, LONG)).data,
  propose: async (path: string, n = 8, persona: 'sport' | 'vlog' | 'gym' = 'sport') =>
    (await api.post('/board/propose', { path, n, persona }, LONG)).data as { persona: string; cards: ClipCard[]; count: number },
  markers: async (path: string, fps = 4): Promise<{ fps: number; markers: Marker[]; count: number }> =>
    (await api.post('/board/markers', { path, fps }, LONG)).data,
  explainCut: async (q: { start: number; end: number; duration?: number; beats?: number[]; speech?: number[][] }) =>
    (await api.post('/board/explain-cut', q)).data as ExplainCut,
  hookLab: async (path: string, intensity = 0.5, window = 3) =>
    (await api.post('/board/hook-lab', { path, intensity, window }, LONG)).data as {
      base: number | null
      intensity: number
      variants: HookVariant[]
    },
}
