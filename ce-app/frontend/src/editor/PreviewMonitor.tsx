import { useEffect, useMemo, useRef, useState } from 'react'
import { Volume2, VolumeX } from 'lucide-react'
import { Slider } from 'antd'
import { mediaUrl } from '../api/render'
import { useEditor, formatTimecode, propsOf, type Clip, type Transition } from './model'
import { layerStyle, transitionStyle, transitionWash } from './preview'
import { useI18n } from '../i18n'

/**
 * Program monitor.
 *
 * It shows the edit, not just the file: every per-clip effect (opacity,
 * transform, rotation, crop, look, grade, animations, freeze) is applied live as
 * CSS, transitions are cross-faded between two stacked layers, and text clips
 * are drawn on top. The compositor remains the source of truth — anything CSS
 * cannot reproduce is listed in a small "at export" hint instead of silently
 * looking wrong.
 *
 * A second, hidden element plays the audio lane so a music bed is audible.
 */
export default function PreviewMonitor() {
  const { t } = useI18n()
  const baseRef = useRef<HTMLVideoElement>(null)
  const topRef = useRef<HTMLVideoElement>(null)
  const audioRef = useRef<HTMLAudioElement>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [master, setMaster] = useState(1)
  const [muted, setMuted] = useState(false)

  const { clips, tracks, transitions, playhead, playing } = useEditor()

  /** Every video clip under the playhead, bottom lane first. */
  const stack = useMemo(() => {
    const lanes = tracks.filter((track) => track.kind === 'video' && !track.muted).map((track) => track.id)
    return clips
      .filter(
        (clip) =>
          clip.src && lanes.includes(clip.trackId) && playhead >= clip.start && playhead < clip.start + clip.duration
      )
      .sort((a, b) => lanes.indexOf(a.trackId) - lanes.indexOf(b.trackId) || a.start - b.start)
      .slice(0, 2)
  }, [clips, tracks, playhead])

  const base = stack[0] ?? null
  const top = stack[1] ?? null

  /** Text clips are painted over the picture, in timeline order. */
  const textClips = useMemo(() => {
    const lanes = tracks.filter((track) => track.kind === 'text' && !track.muted).map((track) => track.id)
    return clips.filter(
      (clip) => lanes.includes(clip.trackId) && playhead >= clip.start && playhead < clip.start + clip.duration
    )
  }, [clips, tracks, playhead])

  const activeAudio = useMemo(() => {
    const lanes = tracks.filter((track) => track.kind === 'audio' && !track.muted).map((track) => track.id)
    return (
      clips.find(
        (clip) =>
          clip.src && lanes.includes(clip.trackId) && playhead >= clip.start && playhead < clip.start + clip.duration
      ) ?? null
    )
  }, [clips, tracks, playhead])

  /** The transition between the two stacked clips, if they are a pair. */
  const pair: { transition: Transition; progress: number } | null = useMemo(() => {
    if (!base || !top) return null
    const transition =
      transitions.find((x) => x.fromClipId === base.id && x.toClipId === top.id) ??
      transitions.find((x) => x.fromClipId === top.id && x.toClipId === base.id) ??
      null
    if (!transition) return null
    const overlapStart = Math.max(base.start, top.start)
    const overlapEnd = Math.min(base.start + base.duration, top.start + top.duration)
    const span = Math.max(0.05, overlapEnd - overlapStart)
    return { transition, progress: (playhead - overlapStart) / span }
  }, [base, top, transitions, playhead])

  const baseSrc = base?.src ? mediaUrl(base.src) : null
  const topSrc = top?.src ? mediaUrl(top.src) : null

  useEffect(() => setFailed(null), [baseSrc])

  /** Keep an element aligned with the timeline position of its clip. */
  const sync = (element: HTMLMediaElement | null, clip: Clip | null, gain: number) => {
    if (!element || !clip) return
    const speed = propsOf(clip).speed
    // A freeze is speed ≈ 0: hold the frame instead of asking for an illegal rate.
    const frozen = speed < 0.1
    element.playbackRate = Math.min(4, Math.max(0.25, frozen ? 1 : speed))
    const target = frozen ? clip.offset : (playhead - clip.start) * speed + clip.offset
    if (Number.isFinite(target) && Math.abs(element.currentTime - target) > 0.3) {
      try {
        element.currentTime = Math.max(0, target)
      } catch {
        /* not ready yet */
      }
    }
    if (frozen) element.pause()
    element.volume = Math.min(1, Math.max(0, gain * master))
    element.muted = muted || gain === 0
  }

  const gainOf = (clip: Clip | null) => {
    if (!clip) return 0
    const props = propsOf(clip)
    if (props.muted) return 0
    // Honour the clip's own audio fades, like the export does.
    const local = playhead - clip.start
    let gain = props.volume
    if (props.fadeIn > 0 && local < props.fadeIn) gain *= Math.max(0, local / props.fadeIn)
    if (props.fadeOut > 0 && local > clip.duration - props.fadeOut) {
      gain *= Math.max(0, (clip.duration - local) / props.fadeOut)
    }
    return gain
  }

  useEffect(() => {
    // While a transition runs, the incoming clip's sound comes up with it.
    const mix = pair ? Math.min(1, Math.max(0, pair.progress)) : top ? 1 : 0
    sync(baseRef.current, base, gainOf(base) * (pair ? 1 - mix : 1))
    sync(topRef.current, top, gainOf(top) * (pair ? mix : 1))
    sync(audioRef.current, activeAudio, gainOf(activeAudio))
  }, [playhead, base, top, activeAudio, master, muted, pair])

  useEffect(() => {
    const elements = [baseRef.current, topRef.current, audioRef.current].filter(Boolean) as HTMLMediaElement[]
    for (const element of elements) {
      if (playing) void element.play().catch(() => undefined)
      else element.pause()
    }
  }, [playing, baseSrc, topSrc, activeAudio?.src])

  /*
   * The transport clock.
   *
   * Without this the playhead never moves: the video element played, the red
   * marker stood still and playback died at the first cut. The clock prefers the
   * video element's own currentTime (no drift, no stutter) and falls back to the
   * wall clock over gaps, frozen frames and audio-only stretches. When it walks
   * past the end of the active clip it steps just over the boundary, so the next
   * clip becomes the active one and starts playing by itself.
   */
  const baseClipRef = useRef(base)
  baseClipRef.current = base

  useEffect(() => {
    if (!playing) return
    let frame = 0
    let previous = performance.now()

    const tick = () => {
      const now = performance.now()
      const wall = Math.min(0.25, (now - previous) / 1000)
      previous = now

      const state = useEditor.getState()
      const head = state.playhead
      const end = state.contentEnd()
      const clip = baseClipRef.current
      const element = baseRef.current

      let next = head + wall
      if (clip && element && element.readyState >= 1 && !element.seeking) {
        const speed = Math.min(4, Math.max(0.25, propsOf(clip).speed))
        const derived = clip.start + (element.currentTime - clip.offset) / speed
        // Trust the element only while it really is running and roughly in
        // step; a stalled, frozen or freshly mounted element must not stop time.
        if (Number.isFinite(derived) && !element.paused && Math.abs(derived - head) < 1) next = derived
      }

      if (clip && next >= clip.start + clip.duration - 0.001) {
        // Hop over the cut so the following clip loads immediately.
        next = clip.start + clip.duration + 0.02
      }

      if (end <= 0 || next >= end) {
        state.setPlayhead(Math.max(0, end))
        state.togglePlay(false)
        return
      }

      state.setPlayhead(next)
      frame = requestAnimationFrame(tick)
    }

    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [playing])

  /* ------------------------------------------------------------- styling -- */

  const baseLayer = base ? layerStyle(base, playhead - base.start) : null
  const topLayer = top ? layerStyle(top, playhead - top.start) : null
  const topTransition = pair ? transitionStyle(pair.transition.type, pair.progress) : null
  const wash = pair ? transitionWash(pair.transition.type, pair.progress) : null

  const notes = Array.from(new Set([...(baseLayer?.notes ?? []), ...(topLayer?.notes ?? [])]))

  return (
    <div className="ed__preview">
      {baseSrc ? (
        <div className="ed__stagewrap">
          <div className="ed__layer" style={baseLayer?.media}>
            <video
              key={baseSrc}
              ref={baseRef}
              className="ed__video"
              src={baseSrc}
              playsInline
              preload="auto"
              onEnded={() => {
                // The source ran out before the clip did — move on regardless.
                const clip = baseClipRef.current
                if (clip) useEditor.getState().setPlayhead(clip.start + clip.duration + 0.02)
              }}
              onError={() => setFailed(t('This file could not be played', 'این فایل قابل پخش نیست'))}
            />
            {baseLayer?.tint && <span className="ed__wash" style={baseLayer.tint} />}
            {baseLayer?.vignette && <span className="ed__wash" style={baseLayer.vignette} />}
          </div>

          {topSrc && (
            <div
              className="ed__layer"
              style={{
                ...topLayer?.media,
                ...topTransition,
                opacity:
                  ((topLayer?.media.opacity as number) ?? 1) *
                  (typeof topTransition?.opacity === 'number' ? topTransition.opacity : 1),
                transform: [topLayer?.media.transform, topTransition?.transform].filter(Boolean).join(' '),
                clipPath: topTransition?.clipPath ?? topLayer?.media.clipPath,
                filter: [topLayer?.media.filter, topTransition?.filter].filter(Boolean).join(' ') || undefined,
              }}
            >
              <video key={topSrc} ref={topRef} className="ed__video" src={topSrc} playsInline preload="auto" />
              {topLayer?.tint && <span className="ed__wash" style={topLayer.tint} />}
              {topLayer?.vignette && <span className="ed__wash" style={topLayer.vignette} />}
            </div>
          )}

          {wash && <span className="ed__wash ed__wash--full" style={wash} />}

          {textClips.map((clip) => {
            const props = propsOf(clip)
            return (
              <span
                key={clip.id}
                className={`ed__text ed__text--${props.position} ed__text--${props.textStyle}`}
                dir="auto"
                style={{
                  // The export sizes text against the canvas height (PlayResY,
                  // 1920 by default), so the preview must do the same or every
                  // caption looks wrong here and right in the file.
                  fontSize: `${((props.fontSize / 1920) * 100).toFixed(2)}cqh`,
                  color: props.color,
                  ['--ce-text-highlight' as string]: props.highlight,
                }}
              >
                {clip.text || clip.label}
              </span>
            )
          })}
        </div>
      ) : (
        <div className="ed__preview-box">
          <span className="ed__preview-hint">
            {clips.some((c) => c.src)
              ? t('No clip under the playhead', 'زیر پلی‌هد کلیپی نیست')
              : t('Import media to see it here', 'یک فایل اضافه کن تا اینجا دیده شود')}
          </span>
          <strong className="ed__tc">{formatTimecode(playhead, true)}</strong>
        </div>
      )}

      {activeAudio?.src && <audio key={mediaUrl(activeAudio.src)} ref={audioRef} src={mediaUrl(activeAudio.src)} preload="auto" />}

      {failed && <div className="ed__preview-error">{failed}</div>}

      <div className="ed__preview-overlay">
        <span className="ed__tc ed__tc--sm">{formatTimecode(playhead, true)}</span>
        {base && <span className="ed__preview-name">{base.label}</span>}
        {pair && (
          <span className="ed__preview-badge">
            {pair.transition.type} · {t('preview', 'پیش‌نمایش')}
          </span>
        )}
        {notes.length > 0 && (
          <span className="ed__preview-badge" title={notes.join(', ')}>
            {t(`${notes.join(', ')} at export`, `${notes.join('، ')} هنگام خروجی`)}
          </span>
        )}
      </div>

      <div className="ed__preview-audio">
        <button
          className="ce-iconbtn"
          onClick={() => setMuted((v) => !v)}
          title={muted ? t('Unmute preview', 'صدادار کردن پیش‌نمایش') : t('Mute preview', 'بی‌صدا کردن پیش‌نمایش')}
        >
          {muted ? <VolumeX size={17} /> : <Volume2 size={17} />}
        </button>
        <Slider
          className="ed__preview-slider"
          min={0}
          max={1}
          step={0.05}
          value={master}
          tooltip={{ formatter: (v) => `${Math.round((v ?? 0) * 100)}%` }}
          onChange={setMaster}
        />
      </div>
    </div>
  )
}
