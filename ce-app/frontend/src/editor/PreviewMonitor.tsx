import { useEffect, useMemo, useRef, useState } from 'react'
import { Volume2, VolumeX } from 'lucide-react'
import { Slider } from 'antd'
import { mediaUrl } from '../api/render'
import { useEditor, formatTimecode, propsOf, sampleChannel, type Clip, type Transition } from './model'
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
/**
 * A caption, word by word when it was asked for.
 *
 * The exporter has drawn karaoke captions since 0.4.x (libass `\k` timing from
 * the word timestamps Whisper returns), but the monitor drew the whole line
 * flat — so switching `animateWords` on changed nothing you could see, and the
 * only honest conclusion from the user's chair was that the switch did nothing.
 * Same lesson as every other effect: what the file will do, the monitor shows.
 */
function renderCaption(
  clip: { text?: string; label?: string; words?: { start: number; end: number; text?: string; prob?: number }[]; start: number; offset: number },
  props: { animateWords?: boolean },
  playhead: number,
  beatPop: number
) {
  const words = clip.words ?? []
  if (!props.animateWords || words.length === 0) return clip.text || clip.label

  // Word timings are in source time; the clip may sit anywhere on the timeline.
  const local = playhead - clip.start + clip.offset
  return (
    <>
      {words.map((word, index) => (
        <span
          key={index}
          className={`${local >= word.start && local < word.end ? 'ed__word is-now' : 'ed__word'}${
            (word.prob ?? 1) < 0.5 ? ' is-unsure' : ''
          }${beatPop ? ' is-pop' : ''}`}
          style={beatPop ? { animationDuration: `${(60 / beatPop).toFixed(2)}s` } : undefined}
          title={(word.prob ?? 1) < 0.5 ? 'low confidence — check me' : undefined}
        >
          {word.text ?? ''}{index < words.length - 1 ? ' ' : ''}
        </span>
      ))}
    </>
  )
}

export default function PreviewMonitor() {
  const beatBpm = useEditor((s) => (s.bpm >= 60 ? s.bpm : 0))
  const { t } = useI18n()
  const baseRef = useRef<HTMLVideoElement>(null)
  const topRef = useRef<HTMLVideoElement>(null)
  const audioRef = useRef<HTMLAudioElement>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [master, setMaster] = useState(1)
  const [muted, setMuted] = useState(false)
  /* 0.9.33 cinematic chrome: letterbox / safezone / vignette / a light scope */
  const [letterbox, setLetterbox] = useState(false)
  const [safezone, setSafezone] = useState(false)
  const [vignette, setVignette] = useState(false)
  const [scope, setScope] = useState(false)
  const scopeRef = useRef<HTMLCanvasElement>(null)

  /** A light luma histogram, sampled from the live video — a scope that costs
   *  a 64×36 read every 400 ms, not a GPU. */
  useEffect(() => {
    if (!scope) return undefined
    const off = document.createElement('canvas')
    off.width = 64; off.height = 36
    const octx = off.getContext('2d', { willReadFrequently: true })
    const id = window.setInterval(() => {
      const v = baseRef.current
      const c = scopeRef.current
      if (!v || !c || !octx || v.readyState < 2 || v.videoWidth === 0) return
      octx.drawImage(v, 0, 0, 64, 36)
      const px = octx.getImageData(0, 0, 64, 36).data
      const bins = new Array(24).fill(0) as number[]
      for (let i = 0; i < px.length; i += 4) {
        const l = (px[i] + px[i + 1] + px[i + 2]) / 765
        bins[Math.min(23, Math.floor(l * 24))] += 1
      }
      const max = Math.max(1, ...bins)
      const ctx = c.getContext('2d')
      if (!ctx) return
      ctx.clearRect(0, 0, c.width, c.height)
      const bw = c.width / 24
      bins.forEach((n, i2) => {
        const h = (n / max) * (c.height - 6)
        ctx.fillStyle = `rgba(56, 189, 248, ${0.35 + 0.6 * (n / max)})`
        ctx.fillRect(i2 * bw + 1, c.height - h, bw - 2, h)
      })
    }, 400)
    return () => window.clearInterval(id)
  }, [scope])

  const { clips, tracks, transitions, playhead, playing, aspect } = useEditor()

  /*
   * The monitor takes the shape of the project.
   *
   * A fixed 16:9 box showed a phone video as a thin strip between two black
   * walls, which is what "the preview does not show the video properly" meant.
   * In Auto the canvas follows the first video clip's real pixel size.
   */
  const ratio = useMemo(() => useEditor.getState().canvasRatio(), [aspect, clips, tracks])

  /**
   * Every video clip under the playhead, bottom lane first.
   *
   * Muting a lane silences it — it must never blank the monitor. Hiding the
   * picture is a separate switch (`hidden`), which is what the eye icon does.
   */
  const stack = useMemo(() => {
    const lanes = tracks.filter((track) => track.kind === 'video' && !track.hidden).map((track) => track.id)
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
    const lanes = tracks.filter((track) => track.kind === 'text' && !track.hidden).map((track) => track.id)
    return clips.filter(
      (clip) => lanes.includes(clip.trackId) && playhead >= clip.start && playhead < clip.start + clip.duration
    )
  }, [clips, tracks, playhead])

  const activeAudio = useMemo(() => {
    const lanes = tracks.filter((track) => track.kind === 'audio' && !track.hidden).map((track) => track.id)
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

  // The proxy is what makes scrubbing 4K footage bearable; the export ignores it.
  const playable = (clip: Clip | null) => (clip?.proxy || clip?.src ? mediaUrl(clip.proxy || (clip.src as string)) : null)
  const baseSrc = playable(base)
  const topSrc = playable(top)

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
    // Lane mute and clip mute are both honoured here, and only here: silence is
    // an audio decision, never a reason to stop drawing the frame.
    if (props.muted || tracks.find((track) => track.id === clip.trackId)?.muted) return 0

    // Ducking, approximated: while any non-ducked clip with sound is playing,
    // a ducked bed steps back. The export does this properly with a sidechain
    // compressor; the monitor only has to be honest about the direction.
    if (props.duck) {
      const voicePlaying = clips.some(
        (other) =>
          other.id !== clip.id &&
          other.src &&
          !propsOf(other).duck &&
          !propsOf(other).muted &&
          !tracks.find((track) => track.id === other.trackId)?.muted &&
          tracks.find((track) => track.id === other.trackId)?.kind !== 'text' &&
          playhead >= other.start &&
          playhead < other.start + other.duration
      )
      if (voicePlaying) return props.volume * 0.3
    }
    // Honour the clip's own audio fades, like the export does.
    const local = playhead - clip.start
    let gain = sampleChannel(clip, 'volume', local) ?? props.volume
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

    // A7 (advisors): a hidden tab must not bank up time — pause instead of jump.
    const onVis = () => {
      if (document.visibilityState === 'hidden') useEditor.getState().togglePlay(false)
    }
    document.addEventListener('visibilitychange', onVis)

    frame = requestAnimationFrame(tick)
    return () => {
      document.removeEventListener('visibilitychange', onVis)
      cancelAnimationFrame(frame)
    }
  }, [playing])

  /* ------------------------------------------------------------- styling -- */

  const baseLayer = base ? layerStyle(base, playhead - base.start) : null
  const topLayer = top ? layerStyle(top, playhead - top.start) : null
  const topTransition = pair ? transitionStyle(pair.transition.type, pair.progress) : null
  const wash = pair ? transitionWash(pair.transition.type, pair.progress) : null

  const notes = Array.from(new Set([...(baseLayer?.notes ?? []), ...(topLayer?.notes ?? [])]))

  return (
    <div
      className={`ed__preview ${letterbox ? 'is-letterbox' : ''} ${vignette ? 'is-vignette' : ''}`}
      style={{ ['--ce-ratio' as string]: `${ratio}` }}
    >
      {/* 0.9.33 monitor chrome: facts on the left, cinematic toggles right */}
      <div className="ed__mon-chips" dir="ltr">
        <span className="badge mono">{Math.round(ratio * 100) / 100}:1</span>
        <span className="badge mono">{Math.round(master * 100)}%</span>
      </div>
      <div className="ed__mon-actions">
        <button className={`ed__btn ${letterbox ? 'is-on' : ''}`} title={t('Letterbox', 'لترباکس سینمایی')} onClick={() => setLetterbox(v => !v)}>▬</button>
        <button className={`ed__btn ${safezone ? 'is-on' : ''}`} title={t('Safe area', 'حاشیه امن')} onClick={() => setSafezone(v => !v)}>▣</button>
        <button className={`ed__btn ${vignette ? 'is-on' : ''}`} title={t('Vignette', 'وینیت')} onClick={() => setVignette(v => !v)}>◐</button>
        <button className={`ed__btn ${scope ? 'is-on' : ''}`} title={t('Luma scope', 'اسکوپ نور')} onClick={() => setScope(v => !v)}>≡</button>
      </div>
      {safezone && <div className="ed__safezone" />}
      {scope && <canvas ref={scopeRef} width={96} height={54} className="ed__scope" />}
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
                {renderCaption(clip, props, playhead, beatBpm)}
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

      {activeAudio?.src && (
        <audio
          key={playable(activeAudio) ?? ''}
          ref={audioRef}
          src={playable(activeAudio) ?? undefined}
          preload="auto"
        />
      )}

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
