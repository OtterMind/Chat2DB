import api from './client'

export interface StyleShot {
  start: number
  duration: number
  motion: 'static' | 'push' | 'pull' | 'pan' | 'handheld'
  energy: number
}

export interface StyleTemplate {
  name: string
  source: string
  duration: number
  aspect: string
  shots: StyleShot[]
  bpm: number
  beats: number[]
  cuts_on_beat: number
  mean_shot: number
  median_shot: number
  shortest_shot: number
  motion_mix: Record<string, number>
  look: Record<string, number>
  speech_ratio: number
  captions: Record<string, unknown>
  hook: Record<string, number | null>
  transitions: Record<string, unknown>
  unknown: string[]
}

export interface TemplateSummary {
  name: string
  shots: number
  duration: number
  bpm: number
  aspect: string
  updatedAt: number
}

export interface StyledEdit {
  name: string
  aspect: string
  template: string
  timeline: { tracks: unknown[]; clips: unknown[]; transitions: unknown[] }
  summary: {
    shots: number
    duration: number
    fromHighlights: number
    motion: string[]
    captions: number
    bpm: number
    applied: string[]
    skipped: string[]
  }
}

export const styleApi = {
  analyse: async (path: string, name?: string): Promise<StyleTemplate> =>
    (await api.post('/style/analyze', { path, name, save: true })).data,
  templates: async (): Promise<{ templates: TemplateSummary[] }> => (await api.get('/style/templates')).data,
  remove: async (name: string): Promise<void> => {
    await api.delete(`/style/templates/${encodeURIComponent(name)}`)
  },
  apply: async (
    path: string,
    template: string,
    name = 'Styled edit',
    music?: string | null
  ): Promise<StyledEdit> => (await api.post('/style/apply', { path, template, name, music })).data,
}
