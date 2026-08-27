import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Volume2, VolumeX, Lock, Unlock, Video, Music4, Type, Plus, Minus, Maximize, Crosshair, Eye, EyeOff,
} from 'lucide-react'
import { createPortal } from 'react-dom'
import { formatTimecode, snapTarget, useEditor, type Clip, type TrackKind, MIN_CLIP } from './model'
import { peaksApi, thumbUrl } from '../api/render'
import { useI18n } from '../i18n'

const TRACK_ICON: Record<TrackKind, typeof Video> = { video: Video, audio: Music4, text: Type }
const HEADER_W = 176

type DragState =
  | { mode: 'move'; id: string; grabOffset: number; originTrack: string }
  | { mode: 'trim-start' | 'trim-end'; id: string }
  | { mode: 'scrub' }
  /** Centred mode: the whole timeline is dragged under a fixed playhead. */
  | { mode: 'pan'; fromX: number; fromTime: number }
  | null

const CENTRED_KEY = 'ce.timeline.centred'

export default function Timeline() {
  const {
    tracks, clips, transitions, selectedId, playhead, pxPerSecond, snapping,
    select, setPlayhead, moveClip, trimClip, toggleMute, toggleHidden, toggleLock, neighbourOf,
    setZoom, zoomToFit, setPanel, playing, beats,
  } = useEditor()
  const scrollRef = useRef<HTMLDivElement>(null)

  // Right-click context menu: the pro-editor affordance the reviews asked for.
  const [menu, setMenu] = useState<{ x: number; y: number; id: string } | null>(null)
  useEffect(() => {
    if (!menu) return undefined
    const close = () => setMenu(null)
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setMenu(null)
    window.addEventListener('pointerdown', close)
    window.addEventListener('keydown', onKey)
    window.addEventListener('wheel', close)
    return () => {
      window.removeEventListener('pointerdown', close)
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('wheel', close)
    }
  }, [menu])

  const menuClip = menu ? clips.find((c) => c.id === menu.id) : undefined

  const { t } = useI18n()
  const laneRef = useRef<HTMLDivElement>(null)
  const [drag, setDrag] = useState<DragState>(null)
  const [guide, setGuide] = useState<number | null>(null)

  /*
   * Centred mode.
   *
   * The phone editors this app is compared with pin the playhead to the middle
   * of the screen and slide the timeline underneath it: the frame you are
   * looking at is always in the same place, and dragging the strip is the same
   * gesture as scrubbing. The classic behaviour (a marker that travels along a
   * still timeline) is one click away, because on a wide desktop screen some
   * people prefer it.
   */
  const [centred, setCentred] = useState(() => localStorage.getItem(CENTRED_KEY) !== 'off')
  const [pad, setPad] = useState(0)
  const programmatic = useRef(false)

  useEffect(() => {
    localStorage.setItem(CENTRED_KEY, centred ? 'on' : 'off')
  }, [centred])

  // Half the viewport of empty space on both sides, so second 0 and the last
  // frame can both reach the middle.
  useEffect(() => {
    const view = scrollRef.current
    if (!view) return
    const measure = () => setPad(centred ? view.clientWidth / 2 : 0)
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(view)
    return () => observer.disconnect()
  }, [centred])

  const xToTime = useCallback(
    (clientX: number) => {
      const rect = laneRef.current?.getBoundingClientRect()
      if (!rect) return 0
      // RTL-safe: the lane itself is LTR, so measure from its left edge.
      return Math.max(0, (clientX - rect.left + (laneRef.current?.scrollLeft ?? 0)) / pxPerSecond)
    },
    [pxPerSecond]
  )

  const magnets = useCallback(
    (excludeId?: string) => {
      const points = [0, playhead]
      for (const c of clips) {
        if (c.id === excludeId) continue
        points.push(c.start, c.start + c.duration)
      }
      return points
    },
    [clips, playhead]
  )

  // A6 (advisors): latest-ref pattern — the listener is created once per drag,
  // and reads the freshest state from a ref, so a guide/clip update mid-drag can
  // no longer tear the listener down.
  const liveRef = useRef({ clips, magnets, moveClip, pxPerSecond, setPlayhead, snapping, trimClip, xToTime })
  liveRef.current = { clips, magnets, moveClip, pxPerSecond, setPlayhead, snapping, trimClip, xToTime }

  useEffect(() => {
    if (!drag) return
    const { clips, magnets, moveClip, pxPerSecond, setPlayhead, snapping, trimClip, xToTime } = liveRef.current

    const onMove = (e: PointerEvent) => {
      const time = xToTime(e.clientX)

      if (drag.mode === 'scrub') {
        setPlayhead(time)
        return
      }

      if (drag.mode === 'pan') {
        // Dragging the strip to the left moves time forward, like a filmstrip.
        setPlayhead(drag.fromTime - (e.clientX - drag.fromX) / pxPerSecond)
        return
      }

      const clip = clips.find((c) => c.id === drag.id)
      if (!clip) return

      if (drag.mode === 'move') {
        let start = Math.max(0, time - drag.grabOffset)
        if (snapping) {
          const snapped =
            snapTarget(start, magnets(clip.id), pxPerSecond) ??
            (() => {
              const end = snapTarget(start + clip.duration, magnets(clip.id), pxPerSecond)
              return end === null ? null : end - clip.duration
            })()
          if (snapped !== null && snapped >= 0) {
            setGuide(snapped === start ? start : snapped)
            start = snapped
          } else setGuide(null)
        }
        // dropping onto another lane
        const lanes = laneRef.current?.querySelectorAll('[data-track-id]')
        let targetTrack = drag.originTrack
        lanes?.forEach((lane) => {
          const r = lane.getBoundingClientRect()
          if (e.clientY >= r.top && e.clientY <= r.bottom) {
            targetTrack = (lane as HTMLElement).dataset.trackId ?? targetTrack
          }
        })
        moveClip(clip.id, start, targetTrack)
        return
      }

      const raw = snapping ? (snapTarget(time, magnets(clip.id), pxPerSecond) ?? time) : time
      setGuide(raw !== time ? raw : null)
      if (drag.mode === 'trim-start') trimClip(clip.id, 'start', Math.min(raw, clip.start + clip.duration - MIN_CLIP))
      else trimClip(clip.id, 'end', Math.max(raw, clip.start + MIN_CLIP))
    }

    const onUp = () => {
      setDrag(null)
      setGuide(null)
    }

    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drag])

  // Centred mode: the scroll position *is* the playhead. Classic mode: keep the
  // marker inside the viewport, otherwise it walks off screen after a few seconds.
  useEffect(() => {
    const view = scrollRef.current
    if (!view) return

    if (centred) {
      const target = playhead * pxPerSecond
      if (Math.abs(view.scrollLeft - target) > 0.5) {
        programmatic.current = true
        view.scrollLeft = target
        requestAnimationFrame(() => {
          programmatic.current = false
        })
      }
      return
    }

    const x = playhead * pxPerSecond
    const left = view.scrollLeft
    const right = left + view.clientWidth - HEADER_W / 2
    if (x < left + 40 || x > right - 60) {
      view.scrollTo({ left: Math.max(0, x - view.clientWidth * 0.35), behavior: playing ? 'auto' : 'smooth' })
    }
  }, [playhead, pxPerSecond, playing, centred, pad])

  // …and scrolling by hand (wheel, trackpad, scrollbar) moves the playhead.
  useEffect(() => {
    const view = scrollRef.current
    if (!view || !centred) return
    const onScroll = () => {
      if (programmatic.current || drag?.mode === 'move') return
      const time = Math.max(0, view.scrollLeft / pxPerSecond)
      if (Math.abs(time - useEditor.getState().playhead) > 0.002) setPlayhead(time)
    }
    view.addEventListener('scroll', onScroll, { passive: true })
    return () => view.removeEventListener('scroll', onScroll)
  }, [centred, pxPerSecond, setPlayhead, drag])

  // ruler ticks: keep roughly one label per 90px
  const step = [0.5, 1, 2, 5, 10, 15, 30, 60, 120].find((s) => s * pxPerSecond >= 90) ?? 300
  const contentSeconds = Math.max(45, ...clips.map((c) => c.start + c.duration + 10))
  const width = contentSeconds * pxPerSecond

  /*
   * Zooming belongs to the timeline, not to a bar above it: the wheel with Ctrl
   * (or a trackpad pinch, which the browser reports the same way) zooms around
   * the pointer, so the frame under the cursor stays put.
   */
  useEffect(() => {
    const view = scrollRef.current
    if (!view) return
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return
      // Must be a non-passive listener, otherwise the browser zooms the page
      // underneath us and preventDefault is ignored.
      event.preventDefault()
      const anchor = xToTime(event.clientX)
      const next = Math.max(8, Math.min(220, pxPerSecond * Math.exp(-event.deltaY / 260)))
      setZoom(next)
      const rect = view.getBoundingClientRect()
      view.scrollLeft = Math.max(0, anchor * next - (event.clientX - rect.left))
    }
    // Two fingers on a touch screen do the same thing, which is how the phone
    // editors the user compares us with handle the timeline scale.
    const touches = new Map<number, { x: number; y: number }>()
    let pinchStart: { distance: number; zoom: number } | null = null
    const distance = () => {
      const [a, b] = [...touches.values()]
      return Math.hypot(a.x - b.x, a.y - b.y)
    }
    const onDown = (event: PointerEvent) => {
      if (event.pointerType !== 'touch') return
      touches.set(event.pointerId, { x: event.clientX, y: event.clientY })
      if (touches.size === 2) pinchStart = { distance: distance(), zoom: pxPerSecond }
    }
    const onMove = (event: PointerEvent) => {
      if (event.pointerType !== 'touch' || !touches.has(event.pointerId)) return
      touches.set(event.pointerId, { x: event.clientX, y: event.clientY })
      if (touches.size === 2 && pinchStart && pinchStart.distance > 0) {
        event.preventDefault()
        setZoom(Math.max(8, Math.min(220, (pinchStart.zoom * distance()) / pinchStart.distance)))
      }
    }
    const onUp = (event: PointerEvent) => {
      touches.delete(event.pointerId)
      if (touches.size < 2) pinchStart = null
    }

    view.addEventListener('wheel', onWheel, { passive: false })
    view.addEventListener('pointerdown', onDown)
    view.addEventListener('pointermove', onMove, { passive: false })
    view.addEventListener('pointerup', onUp)
    view.addEventListener('pointercancel', onUp)
    return () => {
      view.removeEventListener('wheel', onWheel)
      view.removeEventListener('pointerdown', onDown)
      view.removeEventListener('pointermove', onMove)
      view.removeEventListener('pointerup', onUp)
      view.removeEventListener('pointercancel', onUp)
    }
  }, [pxPerSecond, setZoom, xToTime])

  return (
    <div className="tl">
      <div className="tl__headers" style={{ width: HEADER_W }}>
        {/* The scale control sits in the corner of the timeline itself. */}
        <div className="tl__corner">
          <button
            className="tl__hbtn"
            onClick={() => setZoom(pxPerSecond / 1.4)}
            title={t('Shorter spacing (Ctrl + wheel)', 'فاصله‌ی کمتر (Ctrl و چرخ ماوس)')}
          >
            <Minus size={13} />
          </button>
          <span className="tl__cornerslider" title={`${Math.round(pxPerSecond)} px/s`}>
            {formatTimecode(Math.max(1, Math.round(90 / pxPerSecond)))}
          </span>
          <button
            className="tl__hbtn"
            onClick={() => setZoom(pxPerSecond * 1.4)}
            title={t('Wider spacing (Ctrl + wheel)', 'فاصله‌ی بیشتر (Ctrl و چرخ ماوس)')}
          >
            <Plus size={13} />
          </button>
          <button
            className="tl__hbtn"
            onClick={() => zoomToFit(scrollRef.current?.clientWidth ?? 800)}
            title={t('Fit the whole timeline', 'جا دادن کل تایم‌لاین')}
          >
            <Maximize size={12} />
          </button>
          <button
            className={`tl__hbtn ${centred ? 'is-on' : ''}`}
            onClick={() => setCentred((v) => !v)}
            title={
              centred
                ? t('Playhead pinned to the centre', 'نشانگر در وسط ثابت است')
                : t('Playhead travels along the timeline', 'نشانگر روی تایم‌لاین حرکت می‌کند')
            }
          >
            <Crosshair size={12} />
          </button>
        </div>
        {tracks.map((track) => {
          const Icon = TRACK_ICON[track.kind]
          return (
            <div key={track.id} className="tl__header">
              <Icon size={15} />
              <span className="tl__header-name" dir="auto">{track.name}</span>
              {track.kind !== 'text' && (
                <button
                  className={`tl__hbtn ${track.muted ? 'is-off' : ''}`}
                  onClick={() => toggleMute(track.id)}
                  title={track.muted ? t('Unmute this lane', 'صدادار کردن این لایه') : t('Mute this lane', 'بی‌صدا کردن این لایه')}
                >
                  {track.muted ? <VolumeX size={14} /> : <Volume2 size={14} />}
                </button>
              )}
              {track.kind !== 'audio' && (
                <button
                  className={`tl__hbtn ${track.hidden ? 'is-off' : ''}`}
                  onClick={() => toggleHidden(track.id)}
                  title={track.hidden ? t('Show this lane', 'نمایش این لایه') : t('Hide this lane', 'پنهان کردن این لایه')}
                >
                  {track.hidden ? <EyeOff size={14} /> : <Eye size={14} />}
                </button>
              )}
              <button className="tl__hbtn" onClick={() => toggleLock(track.id)} title={t('Lock', 'قفل')}>
                {track.locked ? <Lock size={14} /> : <Unlock size={14} />}
              </button>
            </div>
          )
        })}
      </div>

      <div className="tl__scroll" ref={scrollRef}>
        <div
          className="tl__lanes"
          ref={laneRef}
          style={{ width, marginInlineStart: pad, marginInlineEnd: pad }}
          dir="ltr"
        >
          <div
            className="tl__ruler"
            onPointerDown={(e) => {
              e.preventDefault()
              if (centred) {
                setDrag({ mode: 'pan', fromX: e.clientX, fromTime: playhead })
                return
              }
              setDrag({ mode: 'scrub' })
              setPlayhead(xToTime(e.clientX))
            }}
          >
            {Array.from({ length: Math.ceil(contentSeconds / step) + 1 }, (_, i) => i * step).map((t) => (
              <span key={t} className="tl__tick" style={{ left: t * pxPerSecond }}>
                {formatTimecode(t)}
              </span>
            ))}
            {/* Beat grid: every fourth line is brighter, so bars are readable. */}
            {beats.map((beat, index) => (
              <span
                key={`beat-${beat}`}
                className={`tl__beat ${index % 4 === 0 ? 'is-bar' : ''}`}
                style={{ left: beat * pxPerSecond }}
              />
            ))}
          </div>

          {tracks.map((track) => (
            <div
              key={track.id}
              className={`tl__lane ${track.locked ? 'is-locked' : ''}`}
              data-track-id={track.id}
              onPointerDown={(e) => {
                // Any lane click that is not on a clip/junction clears the
                // selection, so the global toolbar returns the moment the pointer
                // leaves the edited clip. (The beat-grid is a child, so test with
                // closest() rather than target===currentTarget.)
                if ((e.target as HTMLElement).closest('.tl__clip, .tl__junction')) return
                e.preventDefault()
                select(null)
                if (centred && e.target === e.currentTarget) {
                  setDrag({ mode: 'pan', fromX: e.clientX, fromTime: playhead })
                }
              }}
            >
              {/* A junction marker between neighbours, exactly where a
                  transition lives — click it to create or edit one. */}
              {clips
                .filter((c) => c.trackId === track.id && neighbourOf(c.id))
                .map((clip) => {
                  const existing = transitions.find((x) => x.fromClipId === clip.id)
                  return (
                    <button
                      key={`j-${clip.id}`}
                      className={`tl__junction ${existing ? 'is-set' : ''}`}
                      style={{ left: (clip.start + clip.duration) * pxPerSecond }}
                      title={existing ? existing.type : t('Add transition', 'افزودن ترنزیشن')}
                      onPointerDown={(e) => e.stopPropagation()}
                      onClick={() => {
                        // Select the left clip and open the transition chooser
                        // right away — the diamond is the transition control.
                        select(clip.id)
                        setPanel('transition')
                      }}
                    >
                      <span />
                    </button>
                  )
                })}

              {clips
                .filter((c) => c.trackId === track.id)
                .map((clip) => (
                  <ClipView
                    key={clip.id}
                    clip={clip}
                    pxPerSecond={pxPerSecond}
                    selected={clip.id === selectedId}
                    kind={track.kind}
                    onSelect={() => select(clip.id)}
                    onContextMenu={(e) => { select(clip.id); setMenu({ x: e.clientX, y: e.clientY, id: clip.id }) }}
                    onDragStart={(mode, grabTime) =>
                      setDrag(
                        mode === 'move'
                          ? { mode, id: clip.id, grabOffset: grabTime - clip.start, originTrack: clip.trackId }
                          : { mode, id: clip.id }
                      )
                    }
                    xToTime={xToTime}
                  />
                ))}
            </div>
          ))}

          {guide !== null && <div className="tl__guide" style={{ left: guide * pxPerSecond }} />}

      {menu && menuClip &&
        createPortal(
          <div
            className="tl__ctx"
            style={{ left: Math.min(menu.x, window.innerWidth - 190), top: Math.min(menu.y, window.innerHeight - 240) }}
            onPointerDown={(e) => e.stopPropagation()}
          >
            <button onClick={() => { useEditor.getState().splitAtPlayhead(); setMenu(null) }}>
              {t('Split at playhead', 'برش در playhead')}
            </button>
            <button onClick={() => { useEditor.getState().duplicateSelected(); setMenu(null) }}>
              {t('Duplicate', 'تکثیر')}
            </button>
            <button className="is-danger" onClick={() => { useEditor.getState().removeSelected(); setMenu(null) }}>
              {t('Delete', 'حذف')}
            </button>
          </div>,
          document.body,
        )}

          <div
            className={`tl__playhead ${centred ? 'is-centred' : ''}`}
            style={{ left: playhead * pxPerSecond }}
          >
            {/* the timecode rides the playhead — no eye travel to the transport */}
            <span className="tl__ph-bubble" dir="ltr">{formatTimecode(playhead, true)}</span>
            <span
              className="tl__playhead-grip"
              onPointerDown={(e) => {
                e.preventDefault()
                setDrag({ mode: 'scrub' })
              }}
            />
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Film strip.
 *
 * A clip that is a flat colour tells the user nothing; real frames are how every
 * editor makes a timeline readable. Frames are requested from the backend at
 * whole steps so zooming reuses the cache instead of re-encoding.
 */
function FilmStrip({ clip, width }: { clip: Clip; width: number }) {
  const frameWidth = 56
  const count = Math.max(1, Math.min(24, Math.round(width / frameWidth)))
  const frames = useMemo(() => {
    if (!clip.src) return []
    const step = clip.duration / count
    return Array.from({ length: count }, (_, i) => {
      const at = clip.offset + step * (i + 0.5)
      return { key: `${i}`, url: thumbUrl(clip.src as string, at, 96) }
    })
  }, [clip.src, clip.offset, clip.duration, count])

  if (frames.length === 0) return null
  return (
    <span className="tl__strip" aria-hidden>
      {frames.map((frame) => (
        <img
          key={frame.key}
          src={frame.url}
          alt=""
          loading="lazy"
          draggable={false}
          // A missing or unreadable file must not leave a broken-image icon
          // across the clip; the coloured block behind it is the fallback.
          onError={(event) => {
            ;(event.currentTarget as HTMLImageElement).style.visibility = 'hidden'
          }}
        />
      ))}
    </span>
  )
}

/**
 * Waveform.
 *
 * An audio lane made of flat rectangles gives no way to aim a cut at a word or a
 * downbeat. The envelope comes from the backend already bucketed, so a ten-minute
 * file costs a few kilobytes instead of a hundred megabytes of samples.
 */
function Waveform({ clip }: { clip: Clip }) {
  const [points, setPoints] = useState<number[] | null>(null)

  useEffect(() => {
    let cancelled = false
    if (!clip.src) return
    peaksApi
      .get(clip.src, 600)
      .then((data) => !cancelled && setPoints(data.peaks))
      .catch(() => !cancelled && setPoints(null))
    return () => {
      cancelled = true
    }
  }, [clip.src])

  if (!points || points.length === 0) return null

  // Only the part of the source this clip actually shows.
  const from = Math.floor((clip.offset / Math.max(0.001, clip.sourceDuration)) * points.length)
  const to = Math.ceil(((clip.offset + clip.duration) / Math.max(0.001, clip.sourceDuration)) * points.length)
  const window = points.slice(Math.max(0, from), Math.max(from + 1, to))
  const step = 100 / Math.max(1, window.length - 1)
  const top = window.map((v, i) => `${(i * step).toFixed(3)},${(50 - v * 46).toFixed(2)}`).join(' ')
  const bottom = window
    .map((v, i) => `${((window.length - 1 - i) * step).toFixed(3)},${(50 + v * 46).toFixed(2)}`)
    .join(' ')

  return (
    <svg className="tl__wave" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden>
      <polygon points={`${top} ${bottom}`} />
    </svg>
  )
}

function ClipView({
  clip, pxPerSecond, selected, onSelect, onDragStart, xToTime, kind, onContextMenu,
}: {
  clip: Clip
  pxPerSecond: number
  selected: boolean
  onSelect: () => void
  onContextMenu?: (e: { clientX: number; clientY: number }) => void
  onDragStart: (mode: 'move' | 'trim-start' | 'trim-end', grabTime: number) => void
  xToTime: (clientX: number) => number
  kind: TrackKind
}) {
  return (
    <div
      className={`tl__clip ${selected ? 'is-selected' : ''}`}
      style={{
        left: clip.start * pxPerSecond,
        width: Math.max(12, clip.duration * pxPerSecond),
        background: `linear-gradient(150deg, ${clip.color}, ${clip.color}bb)`,
      }}
      onContextMenu={(e) => {
        e.preventDefault()
        e.stopPropagation()
        onContextMenu?.(e)
      }}
      onPointerDown={(e) => {
        e.stopPropagation()
        onSelect()
        const t = (e.target as HTMLElement).dataset.handle
        if (t === 'start') onDragStart('trim-start', xToTime(e.clientX))
        else if (t === 'end') onDragStart('trim-end', xToTime(e.clientX))
        else onDragStart('move', xToTime(e.clientX))
      }}
    >
      {clip.src && kind === 'video' && (
        <FilmStrip clip={clip} width={Math.max(12, clip.duration * pxPerSecond)} />
      )}
      {clip.src && kind === 'audio' && <Waveform clip={clip} />}
      {selected &&
        (clip.keyframes ?? []).map((key) => (
          <span key={key.t} className="tl__key" style={{ left: key.t * pxPerSecond }} />
        ))}
      <span className="tl__handle" data-handle="start" />
      <span className="tl__clip-label" dir="auto">{clip.label}</span>
      <span className="tl__clip-dur">{formatTimecode(clip.duration)}</span>
      <span className="tl__handle tl__handle--end" data-handle="end" />
    </div>
  )
}
