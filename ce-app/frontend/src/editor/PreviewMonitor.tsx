import { useEffect, useMemo, useRef, useState } from 'react'
import { mediaUrl } from '../api/render'
import { useEditor, formatTimecode } from './model'
import { useI18n } from '../i18n'

/**
 * Program monitor.
 *
 * Shows the clip that sits under the playhead on the topmost video lane, seeked
 * to the matching source position. Media is streamed through the local API
 * because a packaged app runs from file://, where direct file playback cannot be
 * seeked reliably.
 */
export default function PreviewMonitor() {
  const { t } = useI18n()
  const videoRef = useRef<HTMLVideoElement>(null)
  const [failed, setFailed] = useState<string | null>(null)

  const { clips, tracks, playhead, playing } = useEditor()

  /** Topmost video clip covering the playhead. */
  const active = useMemo(() => {
    const videoLanes = tracks.filter((track) => track.kind === 'video').map((track) => track.id)
    const covering = clips.filter(
      (clip) =>
        clip.src &&
        videoLanes.includes(clip.trackId) &&
        playhead >= clip.start &&
        playhead < clip.start + clip.duration
    )
    // later lanes render on top, matching the compositor's overlay order
    return covering.sort((a, b) => videoLanes.indexOf(b.trackId) - videoLanes.indexOf(a.trackId))[0]
  }, [clips, tracks, playhead])

  const source = active?.src ? mediaUrl(active.src) : null

  // Load a new file only when the clip changes, never on every playhead tick.
  useEffect(() => {
    setFailed(null)
  }, [source])

  // Keep the element in sync with the timeline.
  useEffect(() => {
    const video = videoRef.current
    if (!video || !active) return
    const target = playhead - active.start + active.offset
    if (Number.isFinite(target) && Math.abs(video.currentTime - target) > 0.25) {
      try {
        video.currentTime = Math.max(0, target)
      } catch {
        /* the element is not ready yet; the next tick will retry */
      }
    }
  }, [playhead, active])

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    if (playing && active) void video.play().catch(() => undefined)
    else video.pause()
  }, [playing, active])

  return (
    <div className="ed__preview">
      {source ? (
        <video
          key={source}
          ref={videoRef}
          className="ed__video"
          src={source}
          muted
          playsInline
          preload="auto"
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

      {failed && <div className="ed__preview-error">{failed}</div>}

      <div className="ed__preview-overlay">
        <span className="ed__tc ed__tc--sm">{formatTimecode(playhead, true)}</span>
        {active && <span className="ed__preview-name">{active.label}</span>}
      </div>
    </div>
  )
}
