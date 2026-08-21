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
    gain: number
  ) => {
    if (!element || !clip) return
    const target = playhead - clip.start + clip.offset
    if (Number.isFinite(target) && Math.abs(element.currentTime - target) > 0.25) {
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
    sync(videoRef.current, activeVideo, videoProps ? (videoProps.muted ? 0 : videoProps.volume) : 0)
    sync(audioRef.current, activeAudio, audioProps ? (audioProps.muted ? 0 : audioProps.volume) : 0)
  }, [playhead, activeVideo, activeAudio, master, muted])

  useEffect(() => {
    const elements = [videoRef.current, audioRef.current].filter(Boolean) as HTMLMediaElement[]
    for (const element of elements) {
      if (playing) void element.play().catch(() => undefined)
      else element.pause()
    }
  }, [playing, videoSrc, audioSrc])

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
