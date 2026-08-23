import api from './client'
import { followTask, type TaskState } from './tasks'

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
    /** Who planned the edit, what each planner scored, and who won. */
    brain?: {
      winner: string
      line: string
      scoreboard: { name: string; score: number; seconds: number; shots: number; note: string }[]
    }
  }
}

/** What a screen needs while it waits: which stage, how far, how long, and Stop. */
export interface Watcher {
  onProgress: (state: TaskState) => void
  onStart?: (cancel: () => void) => void
}

export const styleApi = {
  /**
   * Analyse a reference.
   *
   * A ten-minute reference measured **35.5 s** on the test machine — past the
   * client's 30 s budget, which is how `timeout of 30000ms exceeded` reaches a
   * user with a real file. So the work is a task now: this call starts it and
   * follows it, and the request itself is over in milliseconds.
   */
  analyse: async (path: string, name?: string, watch?: Watcher): Promise<StyleTemplate> => {
    const started = (await api.post('/style/analyze/start', { path, name, save: true })).data as TaskState
    const follow = followTask(started.id, watch?.onProgress ?? (() => undefined))
    watch?.onStart?.(follow.cancel)
    return (await follow.promise).result as StyleTemplate
  },
  templates: async (): Promise<{ templates: TemplateSummary[] }> => (await api.get('/style/templates')).data,
  remove: async (name: string): Promise<void> => {
    await api.delete(`/style/templates/${encodeURIComponent(name)}`)
  },
  /** Rebuild the user's footage. Minutes, when the template asks for captions. */
  apply: async (
    path: string,
    template: string,
    name = 'Styled edit',
    music?: string | null,
    watch?: Watcher
  ): Promise<StyledEdit> => {
    const started = (
      await api.post('/style/apply/start', { path, template, name, music })
    ).data as TaskState
    const follow = followTask(started.id, watch?.onProgress ?? (() => undefined))
    watch?.onStart?.(follow.cancel)
    return (await follow.promise).result as StyledEdit
  },
}
