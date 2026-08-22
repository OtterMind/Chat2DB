/**
 * Preview effects.
 *
 * The compositor (`core/engine/compose.py`) is the single source of truth for
 * what an effect really does; this module is its CSS shadow, so the monitor
 * shows the same edit the export will produce. Where CSS cannot reproduce a
 * filter exactly (unsharp, xfade geometry) the preview is a close approximation
 * and the export stays authoritative — that is stated in the UI, not hidden.
 */
import type { CSSProperties } from 'react'
import { propsOf, sampleChannel, type Clip, type ClipProps } from './model'

/** CSS twin of LOOKS in compose.py. `tint` is painted as a blended overlay. */
export const LOOKS_CSS: Record<string, { filter: string; tint?: string; blend?: string }> = {
  none: { filter: '' },
  warm: { filter: 'saturate(1.06)', tint: 'rgba(255, 150, 60, .16)', blend: 'soft-light' },
  cool: { filter: 'saturate(1.02)', tint: 'rgba(60, 150, 255, .18)', blend: 'soft-light' },
  cinematic: { filter: 'contrast(1.08) saturate(.92)', tint: 'rgba(0, 60, 80, .18)', blend: 'soft-light' },
  vivid: { filter: 'saturate(1.45) contrast(1.12) brightness(1.02)' },
  bw: { filter: 'grayscale(1) contrast(1.12)' },
  sepia: { filter: 'sepia(1)' },
  vintage: { filter: 'sepia(.35) saturate(.9) contrast(.95)', tint: 'rgba(255, 200, 150, .14)', blend: 'soft-light' },
  matte: { filter: 'contrast(.9) saturate(.95) brightness(1.03)' },
  night: { filter: 'brightness(.94) contrast(1.1)', tint: 'rgba(40, 80, 200, .22)', blend: 'soft-light' },
}

const clamp = (v: number, min: number, max: number) => Math.min(max, Math.max(min, v))

/** Progress of an in/out animation at this point in the clip, 0–1 (1 = settled). */
function animationProgress(props: ClipProps, local: number, duration: number) {
  const d = clamp(props.animDuration, 0.1, Math.max(0.1, duration / 2))
  const entering = props.animIn !== 'none' && local < d ? local / d : null
  const leaveStart = duration - d
  const leaving = props.animOut !== 'none' && local > leaveStart ? (local - leaveStart) / d : null
  return { d, entering, leaving }
}

export interface LayerStyle {
  /** Applied to the media element itself. */
  media: CSSProperties
  /** Optional colour wash (looks and temperature) painted over the media. */
  tint: CSSProperties | null
  /** Optional vignette. */
  vignette: CSSProperties | null
  /** Effects that only exist in the export, so the monitor can say so. */
  notes: string[]
}

/**
 * Everything a clip's props do to the picture, as CSS.
 *
 * `local` is the time inside the clip, which is what the animations and fades
 * are keyed to.
 */
export function layerStyle(clip: Clip, local: number, extraOpacity = 1): LayerStyle {
  const props = propsOf(clip)
  const notes: string[] = []

  /* colour ---------------------------------------------------------------- */
  const look = LOOKS_CSS[props.filter] ?? LOOKS_CSS.none
  const { brightness, contrast, saturation, temperature, sharpen, vignette } = props.adjust
  const filters = [
    look.filter,
    Math.abs(brightness) > 0.001 ? `brightness(${(1 + brightness).toFixed(3)})` : '',
    Math.abs(contrast - 1) > 0.001 ? `contrast(${contrast.toFixed(3)})` : '',
    Math.abs(saturation - 1) > 0.001 ? `saturate(${saturation.toFixed(3)})` : '',
  ]
    .filter(Boolean)
    .join(' ')
  if (sharpen > 0.001) notes.push('sharpen')

  /* geometry -------------------------------------------------------------- */
  const { left, top, right, bottom } = props.crop
  const keptW = Math.max(0.05, 1 - left - right)
  const keptH = Math.max(0.05, 1 - top - bottom)
  const cropped = left + top + right + bottom > 0.001
  // The export crops and then fits the remainder to the canvas; mirror that so
  // the framing the user sees is the framing they get.
  const cropScale = cropped ? Math.min(1 / keptW, 1 / keptH) : 1
  const cropShiftX = cropped ? ((right - left) / 2 / keptW) * 100 : 0
  const cropShiftY = cropped ? ((bottom - top) / 2 / keptH) * 100 : 0

  // Keyframes win over the static value wherever they define one.
  const animated = {
    x: sampleChannel(clip, 'x', local),
    y: sampleChannel(clip, 'y', local),
    scale: sampleChannel(clip, 'scale', local),
    rotate: sampleChannel(clip, 'rotate', local),
  }
  const x = animated.x ?? props.transform.x
  const y = animated.y ?? props.transform.y
  const scale = animated.scale ?? props.transform.scale
  const rotate = animated.rotate ?? props.transform.rotate

  /* animations and fades -------------------------------------------------- */
  const duration = Math.max(0.05, clip.duration)
  const { entering, leaving } = animationProgress(props, local, duration)
  let animScale = 1
  let animOpacity = 1
  if (entering !== null) {
    if (props.animIn === 'fade') animOpacity *= entering
    if (props.animIn === 'zoomIn') animScale *= 1.18 - 0.18 * entering
    if (props.animIn === 'zoomOut') animScale *= 1 + 0.18 * (1 - entering)
  }
  if (leaving !== null) {
    if (props.animOut === 'fade') animOpacity *= 1 - leaving
    if (props.animOut === 'zoomIn') animScale *= 1 + 0.18 * leaving
    if (props.animOut === 'zoomOut') animScale *= 1.18 - 0.18 * (1 - leaving)
  }
  if (props.fadeIn > 0 && local < props.fadeIn) animOpacity *= clamp(local / props.fadeIn, 0, 1)
  if (props.fadeOut > 0 && local > duration - props.fadeOut) {
    animOpacity *= clamp((duration - local) / props.fadeOut, 0, 1)
  }

  if (props.reversed) notes.push('reverse')

  const transform = [
    `translate(${(x * 100).toFixed(2)}%, ${(y * 100).toFixed(2)}%)`,
    `scale(${(scale * animScale).toFixed(4)})`,
    `rotate(${rotate.toFixed(2)}deg)`,
    cropped ? `scale(${cropScale.toFixed(4)}) translate(${cropShiftX.toFixed(2)}%, ${cropShiftY.toFixed(2)}%)` : '',
  ]
    .filter(Boolean)
    .join(' ')

  const media: CSSProperties = {
    opacity: clamp(props.opacity * animOpacity * extraOpacity, 0, 1),
    transform,
    filter: filters || undefined,
    clipPath: cropped
      ? `inset(${(top * 100).toFixed(2)}% ${(right * 100).toFixed(2)}% ${(bottom * 100).toFixed(2)}% ${(left * 100).toFixed(2)}%)`
      : undefined,
  }

  const warmth = Math.abs(temperature) > 0.001
  const tintColour = warmth
    ? temperature > 0
      ? `rgba(255, 140, 40, ${(Math.abs(temperature) * 0.35).toFixed(3)})`
      : `rgba(40, 140, 255, ${(Math.abs(temperature) * 0.35).toFixed(3)})`
    : look.tint

  const tint: CSSProperties | null = tintColour
    ? {
        background: warmth && look.tint ? `linear-gradient(${tintColour}, ${tintColour}), ${look.tint}` : tintColour,
        mixBlendMode: (look.blend as CSSProperties['mixBlendMode']) ?? 'soft-light',
        opacity: media.opacity as number,
      }
    : null

  const vignetteStyle: CSSProperties | null =
    vignette > 0.001
      ? {
          background: `radial-gradient(ellipse at center, transparent ${(60 - vignette * 25).toFixed(0)}%, rgba(0,0,0,${(
            vignette * 0.85
          ).toFixed(2)}) 100%)`,
          opacity: media.opacity as number,
        }
      : null

  return { media, tint, vignette: vignetteStyle, notes }
}

/**
 * A CSS stand-in for an FFmpeg `xfade`.
 *
 * `progress` runs 0→1 across the transition. The incoming clip is drawn on top,
 * so only its own reveal has to be described.
 */
export function transitionStyle(type: string, progress: number): CSSProperties {
  const p = clamp(progress, 0, 1)
  const pct = (v: number) => `${(v * 100).toFixed(2)}%`

  switch (type) {
    case 'wipeleft':
      return { clipPath: `inset(0 0 0 ${pct(1 - p)})` }
    case 'wiperight':
      return { clipPath: `inset(0 ${pct(1 - p)} 0 0)` }
    case 'wipeup':
      return { clipPath: `inset(${pct(1 - p)} 0 0 0)` }
    case 'wipedown':
      return { clipPath: `inset(0 0 ${pct(1 - p)} 0)` }
    case 'wipetl':
    case 'diagtl':
      return { clipPath: `polygon(0 0, ${pct(2 * p)} 0, 0 ${pct(2 * p)})` }
    case 'slideleft':
      return { transform: `translateX(${((1 - p) * 100).toFixed(2)}%)` }
    case 'slideright':
      return { transform: `translateX(${(-(1 - p) * 100).toFixed(2)}%)` }
    case 'slideup':
      return { transform: `translateY(${((1 - p) * 100).toFixed(2)}%)` }
    case 'slidedown':
      return { transform: `translateY(${(-(1 - p) * 100).toFixed(2)}%)` }
    case 'smoothleft':
      return { opacity: p, transform: `translateX(${((1 - p) * 25).toFixed(2)}%)` }
    case 'smoothright':
      return { opacity: p, transform: `translateX(${(-(1 - p) * 25).toFixed(2)}%)` }
    case 'circleopen':
    case 'circlecrop':
      return { clipPath: `circle(${pct(p * 0.75)} at 50% 50%)` }
    case 'circleclose':
      return { clipPath: `circle(${pct(0.75 - (1 - p) * 0.75)} at 50% 50%)`, opacity: Math.min(1, p * 1.4) }
    case 'rectcrop':
      return { clipPath: `inset(${pct((1 - p) / 2)} ${pct((1 - p) / 2)} ${pct((1 - p) / 2)} ${pct((1 - p) / 2)})` }
    case 'radial':
      return { opacity: p, clipPath: `circle(${pct(p)} at 50% 50%)` }
    case 'squeezeh':
      return { opacity: p, transform: `scaleY(${(0.2 + 0.8 * p).toFixed(3)})` }
    case 'zoomin':
      return { opacity: p, transform: `scale(${(0.75 + 0.25 * p).toFixed(3)})` }
    case 'distance':
      return { opacity: p, transform: `scale(${(1.25 - 0.25 * p).toFixed(3)})` }
    case 'pixelize':
      return { opacity: p, filter: `blur(${((1 - p) * 12).toFixed(1)}px)` }
    case 'hblur':
      return { opacity: p, filter: `blur(${((1 - p) * 9).toFixed(1)}px)` }
    case 'hlslice':
      return { clipPath: `inset(0 0 0 ${pct(1 - p)})`, filter: `blur(${((1 - p) * 3).toFixed(1)}px)` }
    case 'vuslice':
      return { clipPath: `inset(${pct(1 - p)} 0 0 0)`, filter: `blur(${((1 - p) * 3).toFixed(1)}px)` }
    default:
      // fade, dissolve, fadeblack, fadewhite and anything new: a plain mix
      return { opacity: p }
  }
}

/** The wash a fade-to-black / fade-to-white transition paints between clips. */
export function transitionWash(type: string, progress: number): CSSProperties | null {
  if (type !== 'fadeblack' && type !== 'fadewhite') return null
  const p = clamp(progress, 0, 1)
  return {
    background: type === 'fadeblack' ? '#000' : '#fff',
    opacity: 1 - Math.abs(p - 0.5) * 2,
  }
}
