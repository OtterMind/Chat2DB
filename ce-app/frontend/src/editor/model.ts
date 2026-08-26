/**
 * Edit model for the timeline.
 *
 * Everything the editor does is a pure transformation of this state, and every
 * mutation is pushed through `commit()` so undo/redo comes for free. Media is
 * never touched until export — trimming a clip only changes numbers here.
 */
import { create } from 'zustand'

export type TrackKind = 'video' | 'audio' | 'text'

/** Everything an effect can change without touching the source file. */
export interface ClipProps {
  /** Playback rate; 0.25–4. The source window grows with it. */
  speed: number
  /** Linear gain, 0–2. */
  volume: number
  opacity: number
  muted: boolean
  reversed: boolean
  /** Fractions of the frame removed from each edge, 0–0.45. */
  crop: { left: number; top: number; right: number; bottom: number }
  /** Position as a fraction of the canvas, scale as a multiplier, rotation in degrees. */
  transform: { x: number; y: number; scale: number; rotate: number }
  fadeIn: number
  fadeOut: number
  /** Colour grade. */
  adjust: {
    brightness: number
    contrast: number
    saturation: number
    temperature: number
    sharpen: number
    vignette: number
  }
  /** Named look; see LOOKS in the compositor. */
  filter: string
  animIn: string
  animOut: string
  animDuration: number
  /** Spectral noise reduction, 0–1. */
  denoise: number
  enhanceVoice: boolean
  /** Music that steps aside for the voice (sidechain compression at export). */
  duck: boolean
  /** Text-clip styling. */
  fontSize: number
  color: string
  highlight: string
  position: 'top' | 'middle' | 'bottom'
  textStyle: 'clean' | 'boxed' | 'outline' | 'shadow'
  animateWords: boolean
}

export const DEFAULT_PROPS: ClipProps = {
  speed: 1,
  volume: 1,
  opacity: 1,
  muted: false,
  reversed: false,
  crop: { left: 0, top: 0, right: 0, bottom: 0 },
  transform: { x: 0, y: 0, scale: 1, rotate: 0 },
  fadeIn: 0,
  fadeOut: 0,
  adjust: { brightness: 0, contrast: 1, saturation: 1, temperature: 0, sharpen: 0, vignette: 0 },
  filter: 'none',
  animIn: 'none',
  animOut: 'none',
  animDuration: 0.6,
  denoise: 0,
  enhanceVoice: false,
  duck: false,
  fontSize: 54,
  color: '#FFFFFF',
  highlight: '#6366F1',
  position: 'bottom',
  textStyle: 'clean',
  animateWords: false,
}

/**
 * A keyframe: what the clip should look like at one moment inside itself.
 *
 * Only channels FFmpeg can genuinely animate are here, because a keyframe the
 * export cannot reproduce is a lie told twice — once in the monitor and once in
 * the finished file.
 */
export interface Keyframe {
  /** Seconds from the start of the clip. */
  t: number
  x?: number
  y?: number
  scale?: number
  rotate?: number
  volume?: number
}

/**
 * Opacity is deliberately absent: FFmpeg cannot vary alpha over time without a
 * per-pixel `geq` pass that would slow every export, and the clip already has
 * fade in/out plus the in/out animations for that. Better one honest gap than a
 * keyframe that renders differently from the monitor.
 */
export const KEYFRAME_CHANNELS = ['x', 'y', 'scale', 'rotate', 'volume'] as const
export type KeyframeChannel = (typeof KEYFRAME_CHANNELS)[number]

/** Cross-clip transition, always between two neighbours on the same lane. */
export interface Transition {
  id: string
  trackId: string
  fromClipId: string
  toClipId: string
  /** An FFmpeg xfade name — the engine supports every one of them. */
  type: string
  duration: number
}

export interface Clip {
  id: string
  trackId: string
  /** Absolute path of the media this clip shows; null for placeholders. */
  src?: string | null
  props?: Partial<ClipProps>
  /** Text clips carry their content instead of a media path. */
  text?: string
  words?: { start: number; end: number; text: string; prob?: number }[]
  /** Animation over time; empty or missing means the clip is static. */
  keyframes?: Keyframe[]
  /** Clips that move together. */
  groupId?: string | null
  /** position on the timeline, seconds */
  start: number
  /** visible length, seconds */
  duration: number
  /** where the visible part starts inside the source media, seconds */
  offset: number
  /** full length of the source media, seconds (bounds trimming) */
  sourceDuration: number
  /** Pixel size of the source, when known — the canvas follows it in Auto. */
  width?: number
  height?: number
  /**
   * A small copy used by the preview only. The export always reads `src`, so a
   * proxy can never lower the quality of the finished file.
   */
  proxy?: string | null
  label: string
  color: string
}

export interface Track {
  id: string
  kind: TrackKind
  name: string
  /** Silences the lane. On a video lane it silences the sound only. */
  muted: boolean
  /** Hides the picture of a video or text lane. Sound is unaffected. */
  hidden?: boolean
  locked: boolean
}

interface Snapshot {
  tracks: Track[]
  clips: Clip[]
  transitions: Transition[]
}

interface EditorState extends Snapshot {
  /** Project identity and save state. */
  projectName: string
  dirty: boolean
  lastSavedAt: number | null
  selectedId: string | null
  playhead: number
  pxPerSecond: number
  playing: boolean
  snapping: boolean
  /**
   * Project canvas ratio. 'auto' follows the first video clip, which is what
   * makes a phone video fill the monitor instead of sitting in a wide letterbox.
   */
  aspect: 'auto' | '9:16' | '1:1' | '4:5' | '16:9'
  /**
   * What this video is for, as answered on the Style Match card.
   *
   * It travels with the project rather than living in a component, so the
   * assistant in the editor can answer "which part is the strongest?" about a
   * lesson when it is a lesson. Not part of `Snapshot`: it is a statement about
   * the footage, not an edit, so Ctrl+Z must not take it back.
   */
  intent: Record<string, unknown> | null
  /** Beat grid of the music, in timeline seconds — drawn on the ruler. */
  beats: number[]
  bpm: number
  /**
   * Which tool panel the rail shows. It lives here, not inside the toolbar,
   * because the timeline has to be able to open one — clicking the junction
   * diamond between two clips must show the transition chooser straight away.
   */
  panel: string | null
  past: Snapshot[]
  future: Snapshot[]

  commit: (mutate: (s: Snapshot) => void) => void
  undo: () => void
  redo: () => void

  select: (id: string | null) => void
  setPlayhead: (t: number) => void
  setZoom: (pxPerSecond: number) => void
  zoomToFit: (viewportPx: number) => void
  togglePlay: (playing?: boolean) => void
  toggleSnapping: () => void
  setPanel: (panel: string | null) => void
  setAspect: (aspect: EditorState['aspect']) => void
  setIntent: (intent: Record<string, unknown> | null) => void
  setBeats: (beats: number[], bpm: number) => void
  /** Split a clip at every beat that falls inside it. */
  splitAtBeats: (id: string) => number
  /** Width / height of the canvas as a number, resolving 'auto'. */
  canvasRatio: () => number
  /** The canvas in pixels, for anything that has to think in real coordinates. */
  canvasSize: () => { width: number; height: number }
  /** Replace a clip's camera path in one undoable step (auto-reframe). */
  setClipKeyframes: (id: string, keyframes: Keyframe[], scale?: number) => void
  /** Furthest point any clip reaches — where playback stops. */
  contentEnd: () => number

  moveClip: (id: string, start: number, trackId?: string) => void
  setProps: (id: string, patch: Partial<ClipProps>) => void
  resetProps: (id: string) => void
  freezeFrame: (id: string, atSeconds?: number) => void
  addTransition: (fromClipId: string, type?: string, duration?: number) => string | null
  updateTransition: (id: string, patch: Partial<Pick<Transition, 'type' | 'duration'>>) => void
  removeTransition: (id: string) => void
  neighbourOf: (clipId: string) => Clip | null
  trimClip: (id: string, edge: 'start' | 'end', seconds: number) => void
  /** Trim an edge and close the gap: everything later on the lane moves with it. */
  rippleTrim: (id: string, edge: 'start' | 'end', seconds: number) => void
  /** Move the cut between two neighbours; the pair keeps its total length. */
  rollEdit: (id: string, seconds: number) => void
  /** Slide the source window inside a clip without moving the clip. */
  slipClip: (id: string, deltaSeconds: number) => void
  /** Delete a clip and pull the rest of the lane back over the hole. */
  rippleDelete: (id: string) => void
  splitAtPlayhead: () => void
  removeSelected: () => void
  duplicateSelected: () => void
  addClip: (clip: Omit<Clip, 'id'>) => string
  addTextClip: (text: string, options?: { start?: number; duration?: number; trackId?: string }) => string
  setText: (id: string, text: string) => void
  /** Replace a caption clip's words (and line text) as one undoable step. */
  patchCaption: (id: string, words: { start: number; end: number; text: string; prob?: number }[]) => void
  /** Add or update the keyframe at `t` (seconds inside the clip). */
  setKeyframe: (id: string, t: number, patch: Partial<Omit<Keyframe, 't'>>) => void
  removeKeyframe: (id: string, t: number) => void
  clearKeyframes: (id: string) => void
  /** Attach a finished editing proxy to every clip that came from this file. */
  setProxy: (src: string, proxy: string) => void
  addCaptions: (cues: { start: number; end: number; text: string; words?: { start: number; end: number; text: string; prob?: number }[] }[], offset?: number) => number
  clearTimeline: () => void
  /** Replace a clip with the given source-time windows, closing the gaps. */
  keepRanges: (id: string, ranges: { start: number; end: number }[]) => number
  /** Cut a clip at the given source-time offsets (scene changes). */
  splitAtSourceTimes: (id: string, times: number[]) => number
  addTrack: (kind: TrackKind) => void
  toggleMute: (trackId: string) => void
  toggleHidden: (trackId: string) => void
  toggleLock: (trackId: string) => void
  setProjectName: (name: string) => void
  markSaved: (at?: number) => void
  loadSnapshot: (
    snapshot: Partial<Snapshot> & { intent?: Record<string, unknown> | null },
    name?: string
  ) => void
  toDocument: () => Snapshot
}

const uid = () => Math.random().toString(36).slice(2, 10)
const clone = (s: Snapshot): Snapshot => ({
  tracks: s.tracks.map((t) => ({ ...t })),
  clips: s.clips.map((c) => ({ ...c, props: c.props ? { ...c.props } : undefined })),
  transitions: s.transitions.map((t) => ({ ...t })),
})

/**
 * The value of one animated channel at a moment inside the clip.
 *
 * Between two keyframes the value moves linearly; before the first and after the
 * last it holds. Linear is deliberate: it is what the FFmpeg expressions in the
 * compositor reproduce exactly, so the monitor and the export cannot disagree.
 */
export function sampleChannel(clip: Clip, channel: KeyframeChannel, local: number): number | null {
  const frames = (clip.keyframes ?? []).filter((k) => k[channel] !== undefined)
  if (frames.length === 0) return null
  if (frames.length === 1 || local <= frames[0].t) return frames[0][channel] as number
  const last = frames[frames.length - 1]
  if (local >= last.t) return last[channel] as number
  for (let i = 0; i < frames.length - 1; i++) {
    const a = frames[i]
    const b = frames[i + 1]
    if (local >= a.t && local <= b.t) {
      const span = Math.max(0.0001, b.t - a.t)
      const ratio = (local - a.t) / span
      return (a[channel] as number) + ((b[channel] as number) - (a[channel] as number)) * ratio
    }
  }
  return last[channel] as number
}

/** Merged view of a clip's effect settings. */
export function propsOf(clip: Clip): ClipProps {
  return {
    ...DEFAULT_PROPS,
    ...clip.props,
    crop: { ...DEFAULT_PROPS.crop, ...clip.props?.crop },
    transform: { ...DEFAULT_PROPS.transform, ...clip.props?.transform },
    adjust: { ...DEFAULT_PROPS.adjust, ...clip.props?.adjust },
  }
}

export const MIN_CLIP = 0.2
export const TIMELINE_MAX = 600

/** An empty project: three lanes, no content. */
function seed(): Snapshot {
  return {
    transitions: [],
    tracks: [
      { id: 'v1', kind: 'video', name: 'Video 1', muted: false, locked: false },
      { id: 'a1', kind: 'audio', name: 'Audio', muted: false, locked: false },
      { id: 't1', kind: 'text', name: 'Text', muted: false, locked: false },
    ],
    clips: [],
  }
}

export const useEditor = create<EditorState>((set, get) => ({
  ...seed(),
  projectName: 'Untitled',
  dirty: false,
  lastSavedAt: null,
  selectedId: null,
  playhead: 0,
  pxPerSecond: 42,
  playing: false,
  snapping: true,
  panel: null,
  aspect: 'auto',
  intent: null,
  beats: [],
  bpm: 0,
  past: [],
  future: [],

  commit: (mutate) =>
    set((state) => {
      const before = clone(state)
      const next = clone(state)
      mutate(next)
      return {
        ...next,
        dirty: true,
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
  zoomToFit: (viewportPx) => {
    const { clips } = get()
    const content = Math.max(5, ...clips.map((c) => c.start + c.duration))
    set({ pxPerSecond: Math.max(8, Math.min(220, (viewportPx - 40) / content)) })
  },
  togglePlay: (playing) =>
    set((s) => {
      const next = playing ?? !s.playing
      const end = s.clips.reduce((acc, c) => Math.max(acc, c.start + c.duration), 0)
      // Pressing play at the very end starts over instead of doing nothing.
      const rewind = next && !s.playing && s.playhead >= end - 0.05
      return { playing: next, playhead: rewind ? 0 : s.playhead }
    }),
  toggleSnapping: () => set((s) => ({ snapping: !s.snapping })),
  setPanel: (panel) => set({ panel }),
  setAspect: (aspect) => set({ aspect, dirty: true }),
  // Deliberately not `dirty`: a statement about the footage is not an unsaved edit,
  // and flagging it would nag the user to save a project they have not touched.
  setIntent: (intent) => set({ intent }),
  setBeats: (beats, bpm) => set({ beats, bpm }),

  splitAtBeats: (id) => {
    const state = get()
    const clip = state.clips.find((c) => c.id === id)
    if (!clip) return 0
    // Beats are timeline times; the split points are the ones strictly inside
    // the clip, far enough from both edges to leave a usable piece.
    const inside = state.beats
      .filter((t) => t > clip.start + MIN_CLIP && t < clip.start + clip.duration - MIN_CLIP)
      .sort((a, b) => a - b)
    if (inside.length === 0) return 0
    get().commit((s) => {
      const target = s.clips.find((c) => c.id === id)
      if (!target) return
      const bounds = [target.start, ...inside, target.start + target.duration]
      s.clips = s.clips.filter((c) => c.id !== id)
      for (let i = 0; i < bounds.length - 1; i++) {
        const start = bounds[i]
        s.clips.push({
          ...target,
          id: uid(),
          start,
          offset: target.offset + (start - target.start),
          duration: bounds[i + 1] - start,
        })
      }
    })
    return inside.length
  },
  canvasRatio: () => {
    const { aspect, clips, tracks } = get()
    const named: Record<string, number> = { '9:16': 9 / 16, '1:1': 1, '4:5': 4 / 5, '16:9': 16 / 9 }
    if (aspect !== 'auto') return named[aspect]
    const lanes = tracks.filter((t) => t.kind === 'video').map((t) => t.id)
    const first = clips
      .filter((c) => c.src && lanes.includes(c.trackId) && c.width && c.height)
      .sort((a, b) => a.start - b.start)[0]
    if (first?.width && first?.height) return first.width / first.height
    return 16 / 9
  },
  canvasSize: () => {
    // 1080 on the short edge: the same shape the export dialog offers, so a
    // camera path computed here lands correctly in the rendered file.
    const ratio = get().canvasRatio()
    return ratio < 1
      ? { width: 1080, height: Math.round(1080 / ratio) }
      : { width: Math.round(1080 * ratio), height: 1080 }
  },
  contentEnd: () => get().clips.reduce((end, c) => Math.max(end, c.start + c.duration), 0),

  setProps: (id, patch) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      const current = { ...DEFAULT_PROPS, ...clip.props }
      const next: ClipProps = {
        ...current,
        ...patch,
        crop: { ...current.crop, ...patch.crop },
        transform: { ...current.transform, ...patch.transform },
        adjust: { ...current.adjust, ...patch.adjust },
      }
      // Changing speed keeps the source window fixed and rescales the clip on
      // the timeline, which is what every NLE does.
      if (patch.speed && patch.speed !== current.speed && patch.speed > 0) {
        const sourceWindow = clip.duration * current.speed
        clip.duration = Math.max(MIN_CLIP, sourceWindow / patch.speed)
      }
      clip.props = next
    }),

  resetProps: (id) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (clip) clip.props = { ...DEFAULT_PROPS }
    }),

  freezeFrame: (id, atSeconds) => {
    const { playhead } = get()
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      const at = atSeconds ?? playhead
      const inside = Math.min(Math.max(at - clip.start, 0), clip.duration)
      const frozen: Clip = {
        ...clip,
        id: uid(),
        start: clip.start + inside,
        duration: 2,
        offset: clip.offset + inside,
        label: `${clip.label} · freeze`,
        props: { ...clip.props, speed: 0.0001 },
      }
      for (const other of s.clips) {
        if (other.trackId === clip.trackId && other.start >= frozen.start && other.id !== clip.id) {
          other.start += frozen.duration
        }
      }
      s.clips.push(frozen)
    })
  },

  neighbourOf: (clipId) => {
    const { clips } = get()
    const clip = clips.find((c) => c.id === clipId)
    if (!clip) return null
    return (
      clips
        .filter((c) => c.trackId === clip.trackId && c.start >= clip.start + clip.duration - 0.05)
        .sort((a, b) => a.start - b.start)[0] ?? null
    )
  },

  addTransition: (fromClipId, type = 'fade', duration = 0.5) => {
    const next = get().neighbourOf(fromClipId)
    if (!next) return null
    const from = get().clips.find((c) => c.id === fromClipId)!
    const maxDuration = Math.min(from.duration, next.duration) * 0.9
    const length = Math.min(duration, maxDuration)
    if (length < 0.1) return null

    const id = uid()
    get().commit((s) => {
      s.transitions = s.transitions.filter((t) => t.fromClipId !== fromClipId)
      s.transitions.push({ id, trackId: from.trackId, fromClipId, toClipId: next.id, type, duration: length })
      // Clips overlap during a transition, so everything after it moves earlier.
      for (const clip of s.clips) {
        if (clip.trackId === from.trackId && clip.start >= next.start) clip.start -= length
      }
    })
    return id
  },

  updateTransition: (id, patch) =>
    get().commit((s) => {
      const transition = s.transitions.find((t) => t.id === id)
      if (!transition) return
      if (patch.type) transition.type = patch.type
      if (patch.duration && patch.duration > 0) {
        const delta = patch.duration - transition.duration
        const target = s.clips.find((c) => c.id === transition.toClipId)
        if (target) {
          for (const clip of s.clips) {
            if (clip.trackId === transition.trackId && clip.start >= target.start) clip.start -= delta
          }
        }
        transition.duration = patch.duration
      }
    }),

  removeTransition: (id) =>
    get().commit((s) => {
      const transition = s.transitions.find((t) => t.id === id)
      if (!transition) return
      const target = s.clips.find((c) => c.id === transition.toClipId)
      if (target) {
        for (const clip of s.clips) {
          if (clip.trackId === transition.trackId && clip.start >= target.start) clip.start += transition.duration
        }
      }
      s.transitions = s.transitions.filter((t) => t.id !== id)
    }),

  moveClip: (id, start, trackId) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      const targetId = trackId ?? clip.trackId
      const track = s.tracks.find((t) => t.id === targetId)
      if (!track || track.locked) return

      const placed = resolvePlacement(s.clips, targetId, clip.id, start, clip.duration)
      if (placed === null) return // no room on that lane; leave the clip alone
      clip.start = placed
      clip.trackId = targetId
    }),

  trimClip: (id, edge, seconds) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      const laneMates = s.clips.filter((c) => c.trackId === clip.trackId && c.id !== clip.id)
      const previousEnd = Math.max(
        0,
        ...laneMates.filter((c) => c.start + c.duration <= clip.start + 0.001).map((c) => c.start + c.duration)
      )
      const nextStart = Math.min(
        Number.POSITIVE_INFINITY,
        ...laneMates.filter((c) => c.start >= clip.start + clip.duration - 0.001).map((c) => c.start)
      )

      if (edge === 'start') {
        seconds = Math.max(seconds, previousEnd)
        const delta = seconds - clip.start
        const newDuration = clip.duration - delta
        const newOffset = clip.offset + delta
        if (newDuration < MIN_CLIP || newOffset < 0) return
        clip.start = Math.max(0, seconds)
        clip.duration = newDuration
        clip.offset = newOffset
      } else {
        seconds = Math.min(seconds, nextStart)
        const newDuration = seconds - clip.start
        if (newDuration < MIN_CLIP) return
        if (clip.offset + newDuration > clip.sourceDuration) return
        clip.duration = newDuration
      }
    }),

  rippleTrim: (id, edge, seconds) => {
    const before = get().clips.find((c) => c.id === id)
    if (!before) return
    get().trimClip(id, edge, seconds)
    const after = get().clips.find((c) => c.id === id)
    if (!after) return
    // How much shorter (or longer) the clip became at each side.
    const shift = edge === 'end'
      ? after.start + after.duration - (before.start + before.duration)
      : after.start - before.start
    if (Math.abs(shift) < 0.0001) return
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)!
      if (edge === 'start') {
        // Pull the trimmed clip back to where it began and move the lane with it.
        clip.start = before.start
        for (const other of s.clips) {
          if (other.trackId === clip.trackId && other.id !== id && other.start > before.start) {
            other.start = Math.max(0, other.start - shift)
          }
        }
      } else {
        for (const other of s.clips) {
          if (other.trackId === clip.trackId && other.start >= before.start + before.duration) {
            other.start = Math.max(0, other.start + shift)
          }
        }
      }
    })
  },

  rollEdit: (id, seconds) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      const next = s.clips
        .filter((c) => c.trackId === clip.trackId && c.start >= clip.start + clip.duration - 0.001)
        .sort((a, b) => a.start - b.start)[0]
      if (!next) return
      const boundary = clip.start + clip.duration
      // Both sides must keep at least MIN_CLIP and stay inside their source.
      const lower = Math.max(
        clip.start + MIN_CLIP,
        next.start + next.duration - (next.sourceDuration - next.offset) - (next.start - boundary),
        boundary - (next.offset)
      )
      const upper = Math.min(
        next.start + next.duration - MIN_CLIP,
        clip.start + (clip.sourceDuration - clip.offset)
      )
      const target = Math.min(Math.max(seconds, lower), upper)
      const delta = target - boundary
      if (Math.abs(delta) < 0.0001) return
      clip.duration += delta
      next.start += delta
      next.offset = Math.max(0, next.offset + delta)
      next.duration -= delta
    }),

  slipClip: (id, deltaSeconds) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      // The window slides inside the source; the clip does not move on the lane.
      const maxOffset = Math.max(0, clip.sourceDuration - clip.duration)
      clip.offset = Math.min(maxOffset, Math.max(0, clip.offset + deltaSeconds))
    }),

  rippleDelete: (id) => {
    const target = get().clips.find((c) => c.id === id)
    if (!target) return
    get().commit((s) => {
      s.clips = s.clips.filter((c) => c.id !== id)
      s.transitions = s.transitions.filter((t) => t.fromClipId !== id && t.toClipId !== id)
      for (const other of s.clips) {
        if (other.trackId === target.trackId && other.start >= target.start) {
          other.start = Math.max(0, other.start - target.duration)
        }
      }
    })
    set({ selectedId: null })
  },

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
      s.transitions = s.transitions.filter((t) => t.fromClipId !== id && t.toClipId !== id)
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

  addClip: (clip) => {
    const id = uid()
    get().commit((s) => {
      if (!s.tracks.some((t) => t.id === clip.trackId)) {
        s.tracks.push({ id: clip.trackId, kind: 'video', name: clip.trackId, muted: false, locked: false })
      }
      s.clips.push({ ...clip, id })
    })
    return id
  },

  addTextClip: (text, options) => {
    const state = get()
    const lane =
      options?.trackId ??
      state.tracks.find((t) => t.kind === 'text')?.id ??
      (() => {
        state.addTrack('text')
        return get().tracks.filter((t) => t.kind === 'text').slice(-1)[0].id
      })()
    return get().addClip({
      trackId: lane,
      start: options?.start ?? state.playhead,
      duration: options?.duration ?? 3,
      offset: 0,
      sourceDuration: options?.duration ?? 3,
      src: null,
      text,
      label: text.slice(0, 24),
      color: '#A78BFA',
    })
  },

  setKeyframe: (id, t, patch) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      const at = Math.max(0, Math.round(Math.min(t, clip.duration) * 100) / 100)
      const frames = [...(clip.keyframes ?? [])]
      const existing = frames.find((k) => Math.abs(k.t - at) < 0.005)
      if (existing) Object.assign(existing, patch)
      else frames.push({ t: at, ...patch })
      clip.keyframes = frames.sort((a, b) => a.t - b.t)
    }),

  removeKeyframe: (id, t) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      clip.keyframes = (clip.keyframes ?? []).filter((k) => Math.abs(k.t - t) >= 0.005)
    }),

  setClipKeyframes: (id, keyframes, scale) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      clip.keyframes = [...keyframes].sort((a, b) => a.t - b.t)
      if (scale && scale > 0) {
        const current = { ...DEFAULT_PROPS, ...clip.props }
        clip.props = { ...current, transform: { ...current.transform, scale } }
      }
    }),
  clearKeyframes: (id) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (clip) clip.keyframes = []
    }),

  setProxy: (src, proxy) =>
    set((state) => ({
      clips: state.clips.map((clip) => (clip.src === src ? { ...clip, proxy } : clip)),
    })),

  patchCaption: (id, words) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      clip.words = words
      clip.text = words.map((w) => w.text).join(' ')
      clip.label = clip.text.slice(0, 24)
    }),

  setText: (id, text) =>
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      clip.text = text
      clip.label = text.slice(0, 24) || 'text'
    }),

  addCaptions: (cues, offset = 0) => {
    if (cues.length === 0) return 0
    let lane = get().tracks.find((t) => t.kind === 'text')?.id
    if (!lane) {
      get().addTrack('text')
      lane = get().tracks.filter((t) => t.kind === 'text').slice(-1)[0].id
    }
    get().commit((s) => {
      for (const cue of cues) {
        const duration = Math.max(0.4, cue.end - cue.start)
        s.clips.push({
          id: uid(),
          trackId: lane!,
          start: Math.max(0, cue.start + offset),
          duration,
          offset: 0,
          sourceDuration: duration,
          src: null,
          text: cue.text,
          // word timings are relative to the clip, which is what karaoke needs
          words: (cue.words ?? []).map((w) => ({
            start: Math.max(0, w.start - cue.start),
            end: Math.max(0.05, w.end - cue.start),
            text: w.text,
            prob: (w as { prob?: number }).prob,
          })),
          label: cue.text.slice(0, 24),
          color: '#0EA5E9',
          props: { animateWords: (cue.words?.length ?? 0) > 0 },
        })
      }
    })
    return cues.length
  },

  clearTimeline: () =>
    get().commit((s) => {
      s.clips = []
      s.transitions = []
    }),

  keepRanges: (id, ranges) => {
    let produced = 0
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      // Ranges arrive in source time; translate them into the clip's window.
      const windows = ranges
        .map((r) => ({
          start: Math.max(clip.offset, r.start),
          end: Math.min(clip.offset + clip.duration, r.end),
        }))
        .filter((r) => r.end - r.start >= MIN_CLIP)
      if (windows.length === 0) return

      s.clips = s.clips.filter((c) => c.id !== id)
      let cursor = clip.start
      for (const w of windows) {
        s.clips.push({
          ...clip,
          id: uid(),
          start: cursor,          // ripple: no gaps are left behind
          offset: w.start,
          duration: w.end - w.start,
        })
        cursor += w.end - w.start
      }
      produced = windows.length

      // Everything later on the same lane shifts by the time we removed.
      const removed = clip.duration - (cursor - clip.start)
      if (removed > 0) {
        for (const other of s.clips) {
          if (other.trackId === clip.trackId && other.start >= clip.start + clip.duration) {
            other.start = Math.max(0, other.start - removed)
          }
        }
      }
    })
    return produced
  },

  splitAtSourceTimes: (id, times) => {
    let cuts = 0
    get().commit((s) => {
      const clip = s.clips.find((c) => c.id === id)
      if (!clip) return
      const inside = times
        .filter((t) => t > clip.offset + MIN_CLIP && t < clip.offset + clip.duration - MIN_CLIP)
        .sort((a, b) => a - b)
      if (inside.length === 0) return

      s.clips = s.clips.filter((c) => c.id !== id)
      const bounds = [clip.offset, ...inside, clip.offset + clip.duration]
      let cursor = clip.start
      for (let i = 0; i < bounds.length - 1; i++) {
        const duration = bounds[i + 1] - bounds[i]
        s.clips.push({ ...clip, id: uid(), start: cursor, offset: bounds[i], duration })
        cursor += duration
      }
      cuts = inside.length
    })
    return cuts
  },

  addTrack: (kind) =>
    get().commit((s) => {
      const count = s.tracks.filter((t) => t.kind === kind).length + 1
      const name = kind === 'video' ? `Video ${count}` : kind === 'audio' ? `Audio ${count}` : `Text ${count}`
      s.tracks.push({ id: uid(), kind, name, muted: false, locked: false })
    }),

  toggleMute: (trackId) =>
    get().commit((s) => {
      const t = s.tracks.find((x) => x.id === trackId)
      if (t) t.muted = !t.muted
    }),

  toggleHidden: (trackId) =>
    get().commit((s) => {
      const t = s.tracks.find((x) => x.id === trackId)
      if (t) t.hidden = !t.hidden
    }),

  toggleLock: (trackId) =>
    get().commit((s) => {
      const t = s.tracks.find((x) => x.id === trackId)
      if (t) t.locked = !t.locked
    }),

  setProjectName: (projectName) => set({ projectName, dirty: true }),

  markSaved: (at) => set({ dirty: false, lastSavedAt: at ?? Date.now() }),

  /** Replace the whole timeline, e.g. when opening a project. */
  loadSnapshot: (snapshot, name) =>
    set((state) => ({
      tracks: (snapshot.tracks as Track[] | undefined) ?? state.tracks,
      clips: (snapshot.clips as Clip[] | undefined) ?? [],
      transitions: (snapshot.transitions as Transition[] | undefined) ?? [],
      intent: snapshot.intent ?? null,
      projectName: name ?? state.projectName,
      selectedId: null,
      playhead: 0,
      past: [],
      future: [],
      dirty: false,
      lastSavedAt: Date.now(),
    })),

  toDocument: () => {
    const { tracks, clips, transitions, intent } = get()
    // `intent` rides along so a reopened project still knows what it is for.
    return { tracks, clips, transitions, intent }
  },
}))

/** Nearest magnet point (other clip edges + playhead), or null when too far. */
/**
 * Where a clip may actually sit on a lane.
 *
 * Clips are not allowed to overlap: a drop is clamped into the free gap it was
 * aimed at, and if that gap is too small the clip stays where it was. Overlap is
 * what a transition means in this model, so allowing it by accident would make
 * the render ambiguous.
 */
export function resolvePlacement(
  clips: Clip[],
  trackId: string,
  clipId: string,
  desiredStart: number,
  duration: number
): number | null {
  const others = clips
    .filter((c) => c.trackId === trackId && c.id !== clipId)
    .sort((a, b) => a.start - b.start)

  const wanted = Math.max(0, desiredStart)
  const wantedEnd = wanted + duration

  const overlapping = others.find((c) => wanted < c.start + c.duration && wantedEnd > c.start)
  if (!overlapping) return wanted

  // Find the gap nearest to where the user aimed.
  const bounds: { from: number; to: number }[] = []
  let cursor = 0
  for (const other of others) {
    if (other.start - cursor >= duration) bounds.push({ from: cursor, to: other.start - duration })
    cursor = Math.max(cursor, other.start + other.duration)
  }
  bounds.push({ from: cursor, to: Number.POSITIVE_INFINITY })

  let best: number | null = null
  let bestDistance = Infinity
  for (const gap of bounds) {
    const candidate = Math.min(Math.max(wanted, gap.from), gap.to)
    const distance = Math.abs(candidate - wanted)
    if (distance < bestDistance) {
      bestDistance = distance
      best = candidate
    }
  }
  return best
}

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

// Handle so the headless suites can drive the timeline the way a user would,
// without clicking through the file picker. Exposed in production too: this is
// a single-user desktop app, and the official suites must be able to audit the
// final bundle, not only the dev server (0.9.31 debug decision).
;(window as unknown as { __ceEditor?: typeof useEditor }).__ceEditor = useEditor
