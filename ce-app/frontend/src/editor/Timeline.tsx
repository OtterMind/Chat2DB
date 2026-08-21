import { useCallback, useEffect, useRef, useState } from 'react'
import { Volume2, VolumeX, Lock, Unlock, Video, Music4, Type } from 'lucide-react'
import { formatTimecode, snapTarget, useEditor, type Clip, type TrackKind, MIN_CLIP } from './model'
import { useI18n } from '../i18n'

const TRACK_ICON: Record<TrackKind, typeof Video> = { video: Video, audio: Music4, text: Type }
const HEADER_W = 148

type DragState =
  | { mode: 'move'; id: string; grabOffset: number; originTrack: string }
  | { mode: 'trim-start' | 'trim-end'; id: string }
  | { mode: 'scrub' }
  | null

export default function Timeline() {
  const {
    tracks, clips, selectedId, playhead, pxPerSecond, snapping,
    select, setPlayhead, moveClip, trimClip, toggleMute, toggleLock,
  } = useEditor()

  const { t } = useI18n()
  const laneRef = useRef<HTMLDivElement>(null)
  const [drag, setDrag] = useState<DragState>(null)
  const [guide, setGuide] = useState<number | null>(null)

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

  useEffect(() => {
    if (!drag) return

    const onMove = (e: PointerEvent) => {
      const time = xToTime(e.clientX)

      if (drag.mode === 'scrub') {
        setPlayhead(time)
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
  }, [drag, clips, magnets, moveClip, pxPerSecond, setPlayhead, snapping, trimClip, xToTime])

  // ruler ticks: keep roughly one label per 90px
  const step = [0.5, 1, 2, 5, 10, 15, 30, 60, 120].find((s) => s * pxPerSecond >= 90) ?? 300
  const contentSeconds = Math.max(45, ...clips.map((c) => c.start + c.duration + 10))
  const width = contentSeconds * pxPerSecond

  return (
    <div className="tl">
      <div className="tl__headers" style={{ width: HEADER_W }}>
        <div className="tl__corner" />
        {tracks.map((track) => {
          const Icon = TRACK_ICON[track.kind]
          return (
            <div key={track.id} className="tl__header">
              <Icon size={15} />
              <span className="tl__header-name" dir="auto">{track.name}</span>
              <button className="tl__hbtn" onClick={() => toggleMute(track.id)} title={t('Mute', 'بی‌صدا')}>
                {track.muted ? <VolumeX size={14} /> : <Volume2 size={14} />}
              </button>
              <button className="tl__hbtn" onClick={() => toggleLock(track.id)} title={t('Lock', 'قفل')}>
                {track.locked ? <Lock size={14} /> : <Unlock size={14} />}
              </button>
            </div>
          )
        })}
      </div>

      <div className="tl__scroll">
        <div className="tl__lanes" ref={laneRef} style={{ width }} dir="ltr">
          <div
            className="tl__ruler"
            onPointerDown={(e) => {
              e.preventDefault()
              setDrag({ mode: 'scrub' })
              setPlayhead(xToTime(e.clientX))
            }}
          >
            {Array.from({ length: Math.ceil(contentSeconds / step) + 1 }, (_, i) => i * step).map((t) => (
              <span key={t} className="tl__tick" style={{ left: t * pxPerSecond }}>
                {formatTimecode(t)}
              </span>
            ))}
          </div>

          {tracks.map((track) => (
            <div key={track.id} className={`tl__lane ${track.locked ? 'is-locked' : ''}`} data-track-id={track.id}>
              {clips
                .filter((c) => c.trackId === track.id)
                .map((clip) => (
                  <ClipView
                    key={clip.id}
                    clip={clip}
                    pxPerSecond={pxPerSecond}
                    selected={clip.id === selectedId}
                    onSelect={() => select(clip.id)}
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

          <div className="tl__playhead" style={{ left: playhead * pxPerSecond }}>
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

function ClipView({
  clip, pxPerSecond, selected, onSelect, onDragStart, xToTime,
}: {
  clip: Clip
  pxPerSecond: number
  selected: boolean
  onSelect: () => void
  onDragStart: (mode: 'move' | 'trim-start' | 'trim-end', grabTime: number) => void
  xToTime: (clientX: number) => number
}) {
  return (
    <div
      className={`tl__clip ${selected ? 'is-selected' : ''}`}
      style={{
        left: clip.start * pxPerSecond,
        width: Math.max(12, clip.duration * pxPerSecond),
        background: `linear-gradient(150deg, ${clip.color}, ${clip.color}bb)`,
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
      <span className="tl__handle" data-handle="start" />
      <span className="tl__clip-label" dir="auto">{clip.label}</span>
      <span className="tl__clip-dur">{formatTimecode(clip.duration)}</span>
      <span className="tl__handle tl__handle--end" data-handle="end" />
    </div>
  )
}
