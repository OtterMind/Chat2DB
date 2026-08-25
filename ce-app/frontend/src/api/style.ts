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

/**
 * The one thing a frame cannot say: what the video is *for*.
 *
 * Every field is optional, and an unanswered one changes nothing — the rebuild
 * with no answers is the rebuild that shipped before. What these do is rebalance
 * measurements that are already taken (speech ranges, motion, audio activity,
 * the footage's own shot changes) and set a length that is not the reference's.
 */
export interface IntentAnswers {
  kind?: string
  goal?: string
  focus?: string
  energy?: string
  /** Where it will be watched — Instagram, TikTok, YouTube, LinkedIn, own site. */
  platform?: string
  /** Who is watching — customers, students, colleagues, fans, anyone. */
  audience?: string
  /** Subtitle language, or 'none', or whatever the reference had. */
  captions?: string
  /** What must not appear. Checked where it can be, named where it cannot. */
  restrictions?: string[]
  /** The soundtrack: the reference's own track, only mine, or none. */
  music?: string
  language?: string
  /** Phrases that must survive the cut, comma separated. */
  keep?: string
  /** Phrases that should not carry a clip. */
  avoid?: string
  /** The length the finished edit should have, in seconds. */
  seconds?: number
}

/** One question the brain asked itself, answered with the number behind it. */
export interface BrainQA {
  id: string
  q: { fa: string; en: string }
  a: { fa: string; en: string }
  value: string
  why: { fa: string; en: string }
}

/** One way to start the edit, carrying the intent payload the rebuild knows. */
export interface BrainOption {
  id: string
  title: { fa: string; en: string }
  intent: IntentAnswers
  traits: { fa: string[]; en: string[] }
  why: { fa: string; en: string }
}

export interface BrainReport {
  reference_qa: BrainQA[]
  footage_qa: BrainQA[]
  footage_signals: Record<string, number | string | boolean> | null
  options: BrainOption[]
  /** Slow the single best moment to half speed. */
  slowmo?: boolean
  notes?: string
}

export interface QuestionOption {
  id: string
  en: string
  fa: string
}

export interface Questions {
  options: Record<string, QuestionOption[]>
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
    /** What the answers changed, in words — an answer with no visible effect is not trusted twice. */
    intentSaid?: string[]
    /** How much of the user's own file the edit drew from. It was 14.4 % once. */
    sourceSpanUsed?: number
    intent?: IntentAnswers
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
  /** Hand-authored rhythms for a fresh gallery. */
  starters: async (): Promise<{ starters: StyleTemplate[] }> => (await api.get('/style/starters')).data,
  importTemplate: async (template: unknown, name?: string): Promise<{ saved: string }> =>
    (await api.post('/style/templates/import', { template, name })).data,
  /**
   * The intake questionnaire, from the same module that holds the weights behind
   * it, so a question and its effect cannot drift apart.
   */
  questions: async (): Promise<Questions> => (await api.get('/style/questions')).data,
  /** The brain interrogates itself: Q&A for reference and footage, plus a menu
   *  of genuinely different ways to start the edit. */
  brain: async (template: Record<string, unknown> | null, footage?: string | null): Promise<BrainReport> =>
    (await api.post('/style/brain', { template, footage: footage ?? null }, { timeout: 10 * 60_000 })).data,
  remove: async (name: string): Promise<void> => {
    await api.delete(`/style/templates/${encodeURIComponent(name)}`)
  },
  /** Rebuild the user's footage. Minutes, when the template asks for captions. */
  apply: async (
    path: string,
    template: string,
    name = 'Styled edit',
    music?: string | null,
    watch?: Watcher,
    intent?: IntentAnswers
  ): Promise<StyledEdit> => {
    const started = (
      await api.post('/style/apply/start', { path, template, name, music, intent: intent ?? null })
    ).data as TaskState
    const follow = followTask(started.id, watch?.onProgress ?? (() => undefined))
    watch?.onStart?.(follow.cancel)
    return (await follow.promise).result as StyledEdit
  },
}
