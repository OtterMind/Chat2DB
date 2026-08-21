import { useEffect, useMemo, useRef, useState } from 'react'
import { Volume2, VolumeX } from 'lucide-react'
import { Slider } from 'antd'
import { mediaUrl } from '../api/render'
import { useEditor, formatTimecode, propsOf } from './model'
import { useI18n } from '../i18n'

/**
 * Program monitor with sound.
 *
 * The video element plays the clip under the playhead; a second, hidden element
 * plays the audio lane underneath it, so a music bed is audible while scrubbing
 * without building a full Web Audio mixer. Per-clip volume and mute are honoured,
 * and a master control sits on the monitor itself.
 */
export default function PreviewMonitor() {
  const { t } = useI18n()
  const videoRef = useRef<HTMLVideoElement>(null)
  const audioRef = useRef<HTMLAudioElement>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [master, setMaster] = useState(1)
  const [muted, setMuted] = useState(false)

  const { clips, tracks, playhead, playing } = useEditor()

  const under = (kind: 'video' | 'audio') => {
    const lanes = tracks.filter((track) => track.kind === kind && !track.muted).map((track) => track.id)
    const covering = clips.filter(
      (clip) =>
        clip.src &&
        lanes.includes(clip.trackId) &&
        playhead >= clip.start &&
        playhead < clip.start + clip.duration
    )
    return covering.sort((a, b) => lanes.indexOf(b.trackId) - lanes.indexOf(a.trackId))[0] ?? null
  }

  const activeVideo = useMemo(() => under('video'), [clips, tracks, playhead])
  const activeAudio = useMemo(() => under('audio'), [clips, tracks, playhead])

  const videoSrc = activeVideo?.src ? mediaUrl(activeVideo.src) : null
  const audioSrc = activeAudio?.src ? mediaUrl(activeAudio.src) : null

  useEffect(() => setFailed(null), [videoSrc])

  /** Keep an element aligned with the timeline position of its clip. */
  const sync = (
    element: HTMLMediaElement | null,
    clip: { start: number; offset: number } | null,
    gain: number,
    speed = 1
  ) => {
    if (!element || !clip) return
    element.playbackRate = Math.min(4, Math.max(0.25, speed))
    const target = (playhead - clip.start) * speed + clip.offset
    if (Number.isFinite(target) && Math.abs(element.currentTime - target) > 0.3) {
      try {
        element.currentTime = Math.max(0, target)
      } catch {
        /* not ready yet */
      }
    }
    element.volume = Math.min(1, Math.max(0, gain * master))
    element.muted = muted || gain === 0
  }

  useEffect(() => {
    const videoProps = activeVideo ? propsOf(activeVideo) : null
    const audioProps = activeAudio ? propsOf(activeAudio) : null
    sync(
      videoRef.current,
      activeVideo,
      videoProps ? (videoProps.muted ? 0 : videoProps.volume) : 0,
      videoProps?.speed ?? 1
    )
    sync(
      audioRef.current,
      activeAudio,
      audioProps ? (audioProps.muted ? 0 : audioProps.volume) : 0,
      audioProps?.speed ?? 1
    )
  }, [playhead, activeVideo, activeAudio, master, muted])

  useEffect(() => {
    const elements = [videoRef.current, audioRef.current].filter(Boolean) as HTMLMediaElement[]
    for (const element of elements) {
      if (playing) void element.play().catch(() => undefined)
      else element.pause()
    }
  }, [playing, videoSrc, audioSrc])

  /*
   * The transport clock.
   *
   * Without this the playhead never moves: the video played but the timeline
   * stood still, and playback died at the end of the first clip. The clock
   * prefers the video element's own currentTime (no drift, no stutter) and
   * falls back to the wall clock over gaps or audio-only stretches. When it
   * walks past the end of the active clip it steps just over the boundary, so
   * the next clip becomes the active one and starts playing by itself.
   */
  const activeVideoRef = useRef(activeVideo)
  activeVideoRef.current = activeVideo

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
      const clip = activeVideoRef.current
      const element = videoRef.current

      let next = head + wall
      if (clip && element && element.readyState >= 1 && !element.seeking) {
        const speed = Math.min(4, Math.max(0.25, propsOf(clip).speed))
        const derived = clip.start + (element.currentTime - clip.offset) / speed
        // Trust the element only while it really is running and roughly in
        // step; a stalled or freshly mounted element must not stop the clock.
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

  return (
    <div className="ed__preview">
      {videoSrc ? (
        <video
          key={videoSrc}
          ref={videoRef}
          className="ed__video"
          src={videoSrc}
          playsInline
          preload="auto"
          onEnded={() => {
            // The source ran out before the clip did — move on regardless.
            const state = useEditor.getState()
            if (activeVideo) state.setPlayhead(activeVideo.start + activeVideo.duration + 0.02)
          }}
          onError={() => setFailed(t('This file could not be played', 'این فایل قابل پخش نیست'))}
        />
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

      {audioSrc && <audio key={audioSrc} ref={audioRef} src={audioSrc} preload="auto" />}

      {failed && <div className="ed__preview-error">{failed}</div>}

      <div className="ed__preview-overlay">
        <span className="ed__tc ed__tc--sm">{formatTimecode(playhead, true)}</span>
        {activeVideo && <span className="ed__preview-name">{activeVideo.label}</span>}
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
