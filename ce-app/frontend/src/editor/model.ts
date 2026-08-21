/**
 * Edit model for the timeline.
 *
 * Everything the editor does is a pure transformation of this state, and every
 * mutation is pushed through `commit()` so undo/redo comes for free. Media is
 * never touched until export — trimming a clip only changes numbers here.
 */
import { create } from 'zustand'

export type TrackKind = 'video' | 'audio' | 'text'

export interface Clip {
  id: string
  trackId: string
  /** position on the timeline, seconds */
  start: number
  /** visible length, seconds */
  duration: number
  /** where the visible part starts inside the source media, seconds */
  offset: number
  /** full length of the source media, seconds (bounds trimming) */
  sourceDuration: number
  label: string
  color: string
}

export interface Track {
  id: string
  kind: TrackKind
  name: string
  muted: boolean
  locked: boolean
}

interface Snapshot {
  tracks: Track[]
  clips: Clip[]
}

interface EditorState extends Snapshot {
  selectedId: string | null
  playhead: number
  pxPerSecond: number
  playing: boolean
  snapping: boolean
  past: Snapshot[]
  future: Snapshot[]

  commit: (mutate: (s: Snapshot) => void) => void
  undo: () => void
  redo: () => void

  select: (id: string | null) => void
  setPlayhead: (t: number) => void
  setZoom: (pxPerSecond: number) => void
  togglePlay: (playing?: boolean) => void
  toggleSnapping: () => void

  moveClip: (id: string, start: number, trackId?: string) => void
  trimClip: (id: string, edge: 'start' | 'end', seconds: number) => void
  splitAtPlayhead: () => void
  removeSelected: () => void
  duplicateSelected: () => void
  addTrack: (kind: TrackKind) => void
  toggleMute: (trackId: string) => void
  toggleLock: (trackId: string) => void
}

const uid = () => Math.random().toString(36).slice(2, 10)
const clone = (s: Snapshot): Snapshot => ({
  tracks: s.tracks.map((t) => ({ ...t })),
  clips: s.clips.map((c) => ({ ...c })),
})

export const MIN_CLIP = 0.2
export const TIMELINE_MAX = 600

/** A small demo arrangement so the editor is explorable before media import. */
function seed(): Snapshot {
  const v = { id: 'v1', kind: 'video' as const, name: 'ویدیو ۱', muted: false, locked: false }
  const a = { id: 'a1', kind: 'audio' as const, name: 'صدا', muted: false, locked: false }
  const t = { id: 't1', kind: 'text' as const, name: 'متن', muted: false, locked: false }
  return {
    tracks: [v, a, t],
    clips: [
      { id: uid(), trackId: 'v1', start: 0, duration: 6.5, offset: 0, sourceDuration: 40, label: 'اینترو', color: '#6366F1' },
      { id: uid(), trackId: 'v1', start: 7.2, duration: 9, offset: 3, sourceDuration: 60, label: 'مصاحبه', color: '#8B5CF6' },
      { id: uid(), trackId: 'v1', start: 17, duration: 5, offset: 0, sourceDuration: 20, label: 'بی‌رول', color: '#0EA5E9' },
      { id: uid(), trackId: 'a1', start: 0, duration: 22, offset: 0, sourceDuration: 180, label: 'موسیقی پس‌زمینه', color: '#10B981' },
      { id: uid(), trackId: 't1', start: 1.2, duration: 3.4, offset: 0, sourceDuration: 3.4, label: 'عنوان', color: '#F59E0B' },
    ],
  }
}

export const useEditor = create<EditorState>((set, get) => ({
  ...seed(),
  selectedId: null,
  playhead: 0,
  pxPerSecond: 42,
  playing: false,
  snapping: true,
  past: [],
  future: [],

  commit: (mutate) =>
    set((state) => {
      const before = clone(state)
      const next = clone(state)
      mutate(next)
      return {
        ...next,
        past: [...state.past, before].slice(-60),
        future: [],
      }
    }),

  undo: () =>
    set((state) => {
      const previous = state.past[state.past.length - 1]
      if (!previous) return state
      return {
        ...previous,
        past: state.past.slice(0, -1),
        future: [clone(state), ...state.future].slice(0, 60),
        selectedId: null,
      }
    }),

  redo: () =>
    set((state) => {
      const next = state.future[0]
      if (!next) return state
      return {
        ...next,
        past: [...state.past, clone(state)],
        future: state.future.slice(1),
        selectedId: null,
      }
    }),

  select: (selectedId) => set({ selectedId }),
  setPlayhead: (t) => set({ playhead: Math.max(0, Math.min(TIMELINE_MAX, t)) }),
  setZoom: (pxPerSecond) => set({ pxPerSecond: Math.max(8, Math.min(220, pxPerSecond)) }),
  togglePlay: (playing) => set((s) => ({ playing: playing ?? !s.playing })),
  toggleSnapping: () => set((s) => ({ snapping: !s.snapping })),

  moveClip: (id, start, trackId) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      const track = s.tracks.find((t) => t.id === (trackId ?? clip.trackId))
      if (!track || track.locked) return
      clip.start = Math.max(0, start)
      if (trackId) clip.trackId = trackId
    }),

  trimClip: (id, edge, seconds) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      if (edge === 'start') {
        const delta = seconds - clip.start
        const newDuration = clip.duration - delta
        const newOffset = clip.offset + delta
        if (newDuration < MIN_CLIP || newOffset < 0) return
        clip.start = Math.max(0, seconds)
        clip.duration = newDuration
        clip.offset = newOffset
      } else {
        const newDuration = seconds - clip.start
        if (newDuration < MIN_CLIP) return
        if (clip.offset + newDuration > clip.sourceDuration) return
        clip.duration = newDuration
      }
    }),

  splitAtPlayhead: () => {
    const { playhead, selectedId } = get()
    get().commit((s) => {
      const target =
        s.clips.find((c) => c.id === selectedId && playhead > c.start && playhead < c.start + c.duration) ??
        s.clips.find((c) => playhead > c.start && playhead < c.start + c.duration)
      if (!target) return
      const left = playhead - target.start
      if (left < MIN_CLIP || target.duration - left < MIN_CLIP) return
      const right: Clip = {
        ...target,
        id: uid(),
        start: playhead,
        duration: target.duration - left,
        offset: target.offset + left,
      }
      target.duration = left
      s.clips.push(right)
    })
  },

  removeSelected: () => {
    const id = get().selectedId
    if (!id) return
    get().commit((s) => {
      s.clips = s.clips.filter((c) => c.id !== id)
    })
    set({ selectedId: null })
  },

  duplicateSelected: () => {
    const id = get().selectedId
    if (!id) return
    get().commit((s) => {
      const src = s.clips.find((c) => c.id === id)
      if (!src) return
      s.clips.push({ ...src, id: uid(), start: src.start + src.duration + 0.1 })
    })
  },

  addTrack: (kind) =>
    get().commit((s) => {
      const count = s.tracks.filter((t) => t.kind === kind).length + 1
      const name = kind === 'video' ? `ویدیو ${count}` : kind === 'audio' ? `صدا ${count}` : `متن ${count}`
      s.tracks.push({ id: uid(), kind, name, muted: false, locked: false })
    }),

  toggleMute: (trackId) =>
    get().commit((s) => {
      const t = s.tracks.find((x) => x.id === trackId)
      if (t) t.muted = !t.muted
    }),

  toggleLock: (trackId) =>
    get().commit((s) => {
      const t = s.tracks.find((x) => x.id === trackId)
      if (t) t.locked = !t.locked
    }),
}))

/** Nearest magnet point (other clip edges + playhead), or null when too far. */
export function snapTarget(value: number, candidates: number[], pxPerSecond: number, thresholdPx = 7) {
  let best: number | null = null
  let bestDelta = Infinity
  for (const c of candidates) {
    const delta = Math.abs(c - value)
    if (delta * pxPerSecond <= thresholdPx && delta < bestDelta) {
      best = c
      bestDelta = delta
    }
  }
  return best
}

export function formatTimecode(seconds: number, withFrames = false) {
  const s = Math.max(0, seconds)
  const m = Math.floor(s / 60)
  const sec = Math.floor(s % 60)
  const base = `${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`
  if (!withFrames) return base
  const frames = Math.floor((s % 1) * 30)
  return `${base}:${frames.toString().padStart(2, '0')}`
}
