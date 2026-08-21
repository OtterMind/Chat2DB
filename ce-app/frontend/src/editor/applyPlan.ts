import { useEditor, propsOf, type Clip } from './model'
import { analyzeApi } from '../api/analyze'

export interface Operation {
  op: string
  clipId?: string | null
  [key: string]: unknown
}

export interface ApplyResult {
  applied: string[]
  skipped: string[]
  exportOverride?: { width?: number; height?: number; fps?: number; quality?: string }
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))
const num = (value: unknown, fallback: number) => (typeof value === 'number' && Number.isFinite(value) ? value : fallback)

/**
 * Executes an assistant plan against the edit model.
 *
 * Every operation is validated here rather than trusted: unknown names are
 * skipped, ids are checked, numbers are clamped. The assistant proposes; this
 * function is the only thing that can actually change the timeline, and the whole
 * plan lands as normal undoable steps.
 */
export async function applyPlan(ops: Operation[], selectedId: string | null): Promise<ApplyResult> {
  const result: ApplyResult = { applied: [], skipped: [] }
  const editor = useEditor.getState()

  const pick = (op: Operation): Clip | null => {
    const state = useEditor.getState()
    const byId = op.clipId ? state.clips.find((c) => c.id === op.clipId) : null
    if (byId) return byId
    const selected = selectedId ? state.clips.find((c) => c.id === selectedId) : null
    if (selected) return selected
    return [...state.clips].filter((c) => c.src).sort((a, b) => a.start - b.start)[0] ?? state.clips[0] ?? null
  }

  for (const op of ops) {
    const clip = pick(op)
    const state = useEditor.getState()

    switch (op.op) {
      case 'setSpeed': {
        if (!clip) break
        state.setProps(clip.id, { speed: clamp(num(op.speed, 1), 0.25, 4) })
        result.applied.push(`speed ${clamp(num(op.speed, 1), 0.25, 4)}×`)
        break
      }
      case 'setVolume': {
        if (!clip) break
        state.setProps(clip.id, { volume: clamp(num(op.volume, 1), 0, 2) })
        result.applied.push('volume')
        break
      }
      case 'mute': {
        if (!clip) break
        state.setProps(clip.id, { muted: op.muted !== false })
        result.applied.push('mute')
        break
      }
      case 'setOpacity': {
        if (!clip) break
        state.setProps(clip.id, { opacity: clamp(num(op.opacity, 1), 0, 1) })
        result.applied.push('opacity')
        break
      }
      case 'fade': {
        if (!clip) break
        state.setProps(clip.id, {
          fadeIn: clamp(num(op.fadeIn, propsOf(clip).fadeIn), 0, clip.duration / 2),
          fadeOut: clamp(num(op.fadeOut, propsOf(clip).fadeOut), 0, clip.duration / 2),
        })
        result.applied.push('fade')
        break
      }
      case 'reverse': {
        if (!clip) break
        state.setProps(clip.id, { reversed: op.reversed !== false })
        result.applied.push('reverse')
        break
      }
      case 'crop': {
        if (!clip) break
        const current = propsOf(clip).crop
        state.setProps(clip.id, {
          crop: {
            left: clamp(num(op.left, current.left), 0, 0.45),
            top: clamp(num(op.top, current.top), 0, 0.45),
            right: clamp(num(op.right, current.right), 0, 0.45),
            bottom: clamp(num(op.bottom, current.bottom), 0, 0.45),
          },
        })
        result.applied.push('crop')
        break
      }
      case 'transform': {
        if (!clip) break
        const current = propsOf(clip).transform
        state.setProps(clip.id, {
          transform: {
            scale: clamp(num(op.scale, current.scale), 0.1, 3),
            rotate: clamp(num(op.rotate, current.rotate), -180, 180),
            x: clamp(num(op.x, current.x), -0.5, 0.5),
            y: clamp(num(op.y, current.y), -0.5, 0.5),
          },
        })
        result.applied.push('transform')
        break
      }
      case 'splitAt': {
        const at = num(op.at, state.playhead)
        state.setPlayhead(at)
        state.splitAtPlayhead()
        result.applied.push('split')
        break
      }
      case 'duplicateClip': {
        if (!clip) break
        state.select(clip.id)
        state.duplicateSelected()
        result.applied.push('duplicate')
        break
      }
      case 'deleteClip': {
        if (!clip) break
        state.select(clip.id)
        state.removeSelected()
        result.applied.push('delete')
        break
      }
      case 'addTransition': {
        if (!clip) break
        const created = state.addTransition(clip.id, String(op.type ?? 'fade'), clamp(num(op.duration, 0.5), 0.1, 2))
        created ? result.applied.push('transition') : result.skipped.push('transition (no neighbour)')
        break
      }
      case 'addTransitionsEverywhere': {
        const lanes = new Set(state.clips.map((c) => c.trackId))
        let count = 0
        for (const lane of lanes) {
          const ordered = state.clips.filter((c) => c.trackId === lane).sort((a, b) => a.start - b.start)
          for (const candidate of ordered) {
            const fresh = useEditor.getState()
            if (!fresh.neighbourOf(candidate.id)) continue
            if (fresh.transitions.some((t) => t.fromClipId === candidate.id)) continue
            if (fresh.addTransition(candidate.id, String(op.type ?? 'fade'), clamp(num(op.duration, 0.5), 0.1, 2))) {
              count += 1
            }
          }
        }
        count ? result.applied.push(`${count} transitions`) : result.skipped.push('transitions')
        break
      }
      case 'removeTransition': {
        if (!clip) break
        const existing = state.transitions.find((t) => t.fromClipId === clip.id)
        if (existing) {
          state.removeTransition(existing.id)
          result.applied.push('transition removed')
        } else result.skipped.push('no transition')
        break
      }
      case 'removeSilence': {
        if (!clip?.src) {
          result.skipped.push('remove silence (no media)')
          break
        }
        const analysis = await analyzeApi.silence(clip.src)
        if (analysis.speech.length) {
          state.keepRanges(clip.id, analysis.speech)
          result.applied.push(`${analysis.silences.length} silences removed`)
        } else result.skipped.push('no speech found')
        break
      }
      case 'splitScenes': {
        if (!clip?.src) {
          result.skipped.push('split scenes (no media)')
          break
        }
        const { scenes } = await analyzeApi.scenes(clip.src)
        const cuts = state.splitAtSourceTimes(clip.id, scenes)
        cuts ? result.applied.push(`${cuts} scene cuts`) : result.skipped.push('no scene changes')
        break
      }
      case 'trimTo': {
        const target = num(op.seconds, 0)
        if (target <= 0) break
        const fresh = useEditor.getState()
        const total = Math.max(...fresh.clips.map((c) => c.start + c.duration), 0)
        if (total <= target) {
          result.skipped.push('already short enough')
          break
        }
        // Speed the whole timeline up rather than deleting content the user kept.
        const factor = clamp(total / target, 1, 4)
        for (const c of fresh.clips) fresh.setProps(c.id, { speed: propsOf(c).speed * factor })
        result.applied.push(`trimmed to ~${target}s`)
        break
      }
      case 'setExport': {
        result.exportOverride = {
          width: typeof op.width === 'number' ? op.width : undefined,
          height: typeof op.height === 'number' ? op.height : undefined,
          fps: typeof op.fps === 'number' ? op.fps : undefined,
          quality: typeof op.quality === 'string' ? op.quality : undefined,
        }
        result.applied.push('export format')
        break
      }
      default:
        result.skipped.push(op.op)
    }
  }

  void editor
  return result
}
