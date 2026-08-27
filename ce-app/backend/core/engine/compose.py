"""Cutting Edge (CE) — timeline compositor.

Turns the editor's edit model (tracks + clips, pure data) into a single FFmpeg
invocation. Nothing here mutates source media: every clip is a window
(`offset`, `duration`) placed at `start` on the timeline.

Design notes
------------
* One `filter_complex` graph is built instead of intermediate files, so a render
  is a single pass and stays fast even on long timelines.
* The base is a solid canvas, which makes gaps between clips well defined
  (black) instead of undefined behaviour.
* Video lanes are composited bottom-up with `overlay ... enable='between(t,..)'`,
  so upper lanes win exactly while they are on screen.
* Audio from every non-muted lane is delayed to its timeline position and mixed.
* Hardware encoding is used when the machine reports it, with a CPU fallback.
"""
from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

from app.config import settings
from core.engine import subtitles as subs


@dataclass
class ClipProps:
    """Per-clip effects. Nothing here modifies the source file."""

    speed: float = 1.0
    volume: float = 1.0
    opacity: float = 1.0
    muted: bool = False
    reversed: bool = False
    crop: tuple[float, float, float, float] = (0.0, 0.0, 0.0, 0.0)  # l, t, r, b
    transform: tuple[float, float, float, float] = (0.0, 0.0, 1.0, 0.0)  # x, y, scale, rotate
    fade_in: float = 0.0
    fade_out: float = 0.0
    # colour grade: brightness/contrast/saturation/temperature/sharpen/vignette
    adjust: tuple[float, float, float, float, float, float] = (0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
    filter: str = "none"
    anim_in: str = "none"
    anim_out: str = "none"
    anim_duration: float = 0.6
    denoise: float = 0.0
    enhance_voice: bool = False
    #: Music that steps aside for the voice (sidechain compression at render).
    duck: bool = False
    #: Animation over time: [{"t": seconds, "x": .., "y": .., "scale": ..,
    #: "rotate": .., "opacity": .., "volume": ..}, ...] — sorted, clip-local.
    keyframes: list[dict] = field(default_factory=list)

    @classmethod
    def from_dict(cls, data: dict | None) -> "ClipProps":
        data = data or {}
        crop = data.get("crop") or {}
        transform = data.get("transform") or {}
        adjust = data.get("adjust") or {}
        return cls(
            speed=max(0.0001, float(data.get("speed", 1.0) or 1.0)),
            volume=max(0.0, float(data.get("volume", 1.0) or 1.0)),
            opacity=min(1.0, max(0.0, float(data.get("opacity", 1.0) or 1.0))),
            muted=bool(data.get("muted", False)),
            reversed=bool(data.get("reversed", False)),
            crop=(
                float(crop.get("left", 0.0)), float(crop.get("top", 0.0)),
                float(crop.get("right", 0.0)), float(crop.get("bottom", 0.0)),
            ),
            transform=(
                float(transform.get("x", 0.0)), float(transform.get("y", 0.0)),
                max(0.05, float(transform.get("scale", 1.0) or 1.0)), float(transform.get("rotate", 0.0)),
            ),
            fade_in=max(0.0, float(data.get("fadeIn", 0.0) or 0.0)),
            fade_out=max(0.0, float(data.get("fadeOut", 0.0) or 0.0)),
            adjust=(
                float(adjust.get("brightness", 0.0)),
                float(adjust.get("contrast", 1.0)),
                float(adjust.get("saturation", 1.0)),
                float(adjust.get("temperature", 0.0)),
                float(adjust.get("sharpen", 0.0)),
                float(adjust.get("vignette", 0.0)),
            ),
            filter=str(data.get("filter", "none") or "none"),
            anim_in=str(data.get("animIn", "none") or "none"),
            anim_out=str(data.get("animOut", "none") or "none"),
            anim_duration=max(0.1, float(data.get("animDuration", 0.6) or 0.6)),
            denoise=min(1.0, max(0.0, float(data.get("denoise", 0.0) or 0.0))),
            enhance_voice=bool(data.get("enhanceVoice", False)),
            duck=bool(data.get("duck", False)),
            keyframes=sorted(
                [k for k in (data.get("keyframes") or []) if isinstance(k, dict)],
                key=lambda k: float(k.get("t", 0.0)),
            ),
        )


@dataclass
class Transition:
    id: str
    track_id: str
    from_clip_id: str
    to_clip_id: str
    type: str = "fade"
    duration: float = 0.5


@dataclass
class Clip:
    id: str
    track_id: str
    start: float
    duration: float
    offset: float = 0.0
    src: str | None = None
    label: str = ""
    kind: str = "video"
    props: ClipProps = field(default_factory=ClipProps)
    #: Text clips carry their content and optional word timings instead of media.
    text: str = ""
    words: list[dict] = field(default_factory=list)
    raw_props: dict = field(default_factory=dict)

    def as_text_dict(self) -> dict:
        return {
            "start": self.start,
            "duration": self.duration,
            "text": self.text or self.label,
            "label": self.label,
            "words": self.words,
            "props": self.raw_props,
        }

    @property
    def end(self) -> float:
        return self.start + self.duration

    @property
    def source_window(self) -> float:
        """How much source material a clip consumes, accounting for speed."""
        return self.duration * self.props.speed


@dataclass
class Track:
    id: str
    kind: str = "video"
    name: str = ""
    #: Silences the lane. On a video lane the picture still renders.
    muted: bool = False
    #: Hides the picture of a video/text lane. Its sound is unaffected.
    hidden: bool = False


@dataclass
class Timeline:
    tracks: list[Track] = field(default_factory=list)
    clips: list[Clip] = field(default_factory=list)
    transitions: list[Transition] = field(default_factory=list)
    width: int = 1080
    height: int = 1920
    fps: int = 30
    bpm: float = 0.0
    #: Package B brand overlays — off by default, opt-in per export.
    progress_bar: bool = False
    brand_text: str = ""

    @property
    def duration(self) -> float:
        return max((c.end for c in self.clips), default=0.0)

    def transition_between(self, from_id: str, to_id: str) -> Transition | None:
        for transition in self.transitions:
            if transition.from_clip_id == from_id and transition.to_clip_id == to_id:
                return transition
        return None

    @classmethod
    def from_dict(cls, data: dict) -> "Timeline":
        tracks = [
            Track(
                id=str(t["id"]),
                kind=t.get("kind", "video"),
                name=t.get("name", ""),
                muted=bool(t.get("muted", False)),
                hidden=bool(t.get("hidden", False)),
            )
            for t in data.get("tracks", [])
        ]
        kind_by_track = {t.id: t.kind for t in tracks}
        bpm = float(data.get("bpm", 0.0) or 0.0)
        progress_bar = bool(data.get("progressBar", False))
        brand_text = str(data.get("brandText", "") or "")
        clips = [
            Clip(
                id=str(c["id"]),
                track_id=str(c["trackId"]),
                start=float(c["start"]),
                duration=float(c["duration"]),
                offset=float(c.get("offset", 0.0)),
                src=c.get("src"),
                label=c.get("label", ""),
                kind=kind_by_track.get(str(c["trackId"]), "video"),
                props=ClipProps.from_dict(c.get("props")),
                text=str(c.get("text", "") or ""),
                words=list(c.get("words") or []),
                raw_props=dict(c.get("props") or {}),
            )
            for c in data.get("clips", [])
        ]
        transitions = [
            Transition(
                id=str(t.get("id", "")),
                track_id=str(t["trackId"]),
                from_clip_id=str(t["fromClipId"]),
                to_clip_id=str(t["toClipId"]),
                type=str(t.get("type", "fade")),
                duration=float(t.get("duration", 0.5)),
            )
            for t in data.get("transitions", [])
        ]
        return cls(
            tracks=tracks,
            clips=clips,
            transitions=transitions,
            progress_bar=progress_bar,
            brand_text=brand_text,
            bpm=bpm,
            width=int(data.get("width", 1080)),
            height=int(data.get("height", 1920)),
            fps=int(data.get("fps", 30)),
        )


def ffmpeg_binary() -> str:
    """Bundled FFmpeg first (CE_FFMPEG_DIR is exported by the desktop shell)."""
    if settings.ffmpeg_path:
        return settings.ffmpeg_path
    import os

    bundled = os.environ.get("CE_FFMPEG_DIR")
    if bundled:
        candidate = Path(bundled) / "ffmpeg.exe"
        if candidate.exists():
            return str(candidate)
        candidate = Path(bundled) / "ffmpeg"
        if candidate.exists():
            return str(candidate)
    return shutil.which("ffmpeg") or "ffmpeg"


def _brand_font() -> str | None:
    """A real font for the watermark, or None — drawtext without a font is a
    render failure waiting to happen, so absent font means no text watermark."""
    import os
    for candidate in (
        "C:/Windows/Fonts/segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        os.path.expanduser("~/.fonts/Vazirmatn-Regular.ttf"),
    ):
        if os.path.exists(candidate):
            return candidate
    return None


def ffprobe_binary() -> str:
    exe = ffmpeg_binary()
    probe = Path(exe).with_name("ffprobe.exe" if exe.endswith(".exe") else "ffprobe")
    return str(probe) if probe.exists() else (shutil.which("ffprobe") or "ffprobe")


def probe_media(path: str) -> dict:
    """Duration / size / fps for a media file, used when importing into the timeline."""
    probe = ffprobe_binary()
    if shutil.which(probe) is None and not Path(probe).exists():
        # Minimal FFmpeg builds ship without ffprobe; parse `ffmpeg -i` instead.
        return _probe_with_ffmpeg(path)
    out = subprocess.run(
        [
            probe, "-v", "error", "-print_format", "json",
            "-show_format", "-show_streams", path,
        ],
        capture_output=True, text=True, check=True,
    )
    data = json.loads(out.stdout)
    video = next((s for s in data.get("streams", []) if s.get("codec_type") == "video"), None)
    audio = next((s for s in data.get("streams", []) if s.get("codec_type") == "audio"), None)
    fps = 30.0
    if video and video.get("r_frame_rate", "0/0") != "0/0":
        num, _, den = video["r_frame_rate"].partition("/")
        try:
            fps = float(num) / float(den or 1)
        except (ValueError, ZeroDivisionError):
            fps = 30.0
    return {
        "path": path,
        "duration": float(data.get("format", {}).get("duration", 0.0)),
        "width": int(video["width"]) if video else 0,
        "height": int(video["height"]) if video else 0,
        "fps": round(fps, 3),
        "has_audio": audio is not None,
        "has_video": video is not None,
    }


def _probe_with_ffmpeg(path: str) -> dict:
    """Fallback metadata reader that only needs the ffmpeg binary itself."""
    import re

    out = subprocess.run([ffmpeg_binary(), "-hide_banner", "-i", path], capture_output=True, text=True)
    text = out.stderr
    duration = 0.0
    match = re.search(r"Duration:\s*(\d+):(\d+):(\d+\.\d+)", text)
    if match:
        h, m, sec = match.groups()
        duration = int(h) * 3600 + int(m) * 60 + float(sec)
    size = re.search(r"Video:.*?(\d{2,5})x(\d{2,5})", text)
    fps_match = re.search(r"(\d+(?:\.\d+)?)\s*fps", text)
    return {
        "path": path,
        "duration": duration,
        "width": int(size.group(1)) if size else 0,
        "height": int(size.group(2)) if size else 0,
        "fps": float(fps_match.group(1)) if fps_match else 30.0,
        "has_audio": "Audio:" in text,
        "has_video": "Video:" in text,
    }


_AUDIO_CACHE: dict[str, bool] = {}


def _has_audio_stream(path: str) -> bool:
    """A video file without an audio track must not get an audio filter branch —
    FFmpeg aborts the whole graph with "matches no streams" if it does."""
    if path not in _AUDIO_CACHE:
        try:
            _AUDIO_CACHE[path] = bool(probe_media(path).get("has_audio"))
        except Exception:
            _AUDIO_CACHE[path] = False
    return _AUDIO_CACHE[path]


def _has_video_stream(path: str) -> bool:
    try:
        return bool(probe_media(path).get("has_video"))
    except Exception:
        return False


#: Colour looks, expressed with filters that ship inside our FFmpeg build so no
#: external LUT files have to be downloaded or licensed.
LOOKS: dict[str, list[str]] = {
    "none": [],
    "warm": ["colorbalance=rs=0.10:gs=0.02:bs=-0.08", "eq=saturation=1.06"],
    "cool": ["colorbalance=rs=-0.08:gs=0.00:bs=0.12", "eq=saturation=1.02"],
    "cinematic": ["curves=preset=medium_contrast", "eq=saturation=0.92:contrast=1.08"],
    "vivid": ["eq=saturation=1.45:contrast=1.12:brightness=0.02"],
    "bw": ["hue=s=0", "eq=contrast=1.12"],
    "sepia": ["colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"],
    "vintage": ["curves=vintage", "eq=saturation=0.9"],
    "matte": ["curves=all='0/0.06 0.5/0.5 1/0.94'", "eq=saturation=0.95"],
    "night": ["colorbalance=rs=-0.12:bs=0.18", "eq=brightness=-0.06:contrast=1.1"],
}


def _look_filters(name: str) -> list[str]:
    return LOOKS.get(name, [])


def _adjust_filters(adjust: tuple[float, float, float, float, float, float]) -> list[str]:
    brightness, contrast, saturation, temperature, sharpen, vignette = adjust
    parts: list[str] = []
    if any(abs(v - d) > 0.001 for v, d in ((brightness, 0.0), (contrast, 1.0), (saturation, 1.0))):
        parts.append(
            f"eq=brightness={brightness:.3f}:contrast={contrast:.3f}:saturation={saturation:.3f}"
        )
    if abs(temperature) > 0.001:
        # positive is warmer, negative cooler
        parts.append(f"colorbalance=rs={temperature * 0.3:.3f}:bs={-temperature * 0.3:.3f}")
    if sharpen > 0.001:
        parts.append(f"unsharp=5:5:{min(2.0, sharpen * 2):.3f}")
    if vignette > 0.001:
        parts.append(f"vignette=PI/{max(2.5, 6 - vignette * 3):.2f}")
    return parts


def _animation_filters(
    kind: str,
    duration: float,
    clip_duration: float,
    entering: bool,
    width: int,
    height: int,
) -> list[str]:
    """In/out animations expressed with time-aware filters.

    Expression commas must be escaped: inside filter_complex a bare comma ends
    the filter, which is why an unescaped `if(lt(t,d),..)` breaks the whole graph.
    """
    if kind in ("none", "", None):
        return []
    d = max(0.1, min(duration, clip_duration / 2))
    start = 0.0 if entering else max(0.0, clip_duration - d)

    if kind == "fade":
        return [f"fade=t={'in' if entering else 'out'}:st={start:.3f}:d={d:.3f}:alpha=1"]

    if kind in ("zoomIn", "zoomOut"):
        # A time-varying crop, then scale back to the canvas: zoompan restarts
        # per frame on moving footage, so it cannot animate a clip.
        if kind == "zoomIn":
            zoom = (
                f"if(lt(t,{d:.3f}),1.18-0.18*t/{d:.3f},1)"
                if entering
                else f"if(gt(t,{start:.3f}),1+0.18*(t-{start:.3f})/{d:.3f},1)"
            )
        else:
            zoom = (
                f"if(lt(t,{d:.3f}),1+0.18*(1-t/{d:.3f}),1)"
                if entering
                else f"if(gt(t,{start:.3f}),1.18-0.18*(1-(t-{start:.3f})/{d:.3f}),1)"
            )
        zoom = zoom.replace(",", "\\,")
        return [
            f"crop=w=iw/({zoom}):h=ih/({zoom}):x=(iw-ow)/2:y=(ih-oh)/2",
            f"scale={width}:{height}",
        ]
    return []


def keyframe_expression(keyframes: list[dict], channel: str, default: float) -> str | None:
    """A piecewise-linear FFmpeg expression for one animated channel.

    Returns None when the channel is not animated, so a static clip keeps the
    simple, fast filter chain. The shape mirrors `sampleChannel()` in the editor
    exactly — hold before the first key, linear between keys, hold after the last
    — because the monitor and the render must never disagree.

    Commas inside the expression are escaped: an unescaped one ends the filter
    and silently truncates the graph (the bug that broke the zoom animations).
    """
    points = [
        (float(k.get("t", 0.0)), float(k[channel]))
        for k in keyframes
        if channel in k and k[channel] is not None
    ]
    if not points:
        return None
    points.sort(key=lambda p: p[0])
    if len(points) == 1:
        return f"{points[0][1]:.6f}"

    # Built from the last segment backwards: if(lt(t,t1), seg0, if(lt(t,t2), seg1, ...))
    expression = f"{points[-1][1]:.6f}"
    for i in range(len(points) - 2, -1, -1):
        t0, v0 = points[i]
        t1, v1 = points[i + 1]
        span = max(1e-6, t1 - t0)
        slope = (v1 - v0) / span
        segment = f"({v0:.6f}+({slope:.6f})*(t-{t0:.6f}))"
        if i == 0:
            segment = f"if(lt(t\\,{t0:.6f})\\,{v0:.6f}\\,{segment})"
        expression = f"if(lt(t\\,{t1:.6f})\\,{segment}\\,{expression})"
    return expression


def _piecewise_expression(points: list[tuple[float, float]]) -> str:
    """A piecewise-linear FFmpeg expression through (time, value) points.

    Same shape as `keyframe_expression`, built from a curve instead of keyframes,
    and with the same rule about commas: every one escaped, or the filter ends
    early and takes the rest of the graph with it.
    """
    if not points:
        return "1"
    if len(points) == 1:
        return f"{points[0][1]:.4f}"
    expression = f"{points[-1][1]:.4f}"
    for i in range(len(points) - 2, -1, -1):
        t0, v0 = points[i]
        t1, v1 = points[i + 1]
        span = max(1e-6, t1 - t0)
        slope = (v1 - v0) / span
        segment = f"({v0:.4f}+({slope:.4f})*(t-{t0:.4f}))"
        if i == 0:
            segment = f"if(lt(t\\,{t0:.4f})\\,{v0:.4f}\\,{segment})"
        expression = f"if(lt(t\\,{t1:.4f})\\,{segment}\\,{expression})"
    return expression


def _atempo_chain(speed: float) -> list[float]:
    """atempo only accepts 0.5–2.0, so extreme speeds need to be chained."""
    factors: list[float] = []
    remaining = speed
    while remaining > 2.0:
        factors.append(2.0)
        remaining /= 2.0
    while remaining < 0.5:
        factors.append(0.5)
        remaining /= 0.5
    factors.append(remaining)
    return factors


def _build_sequences(timeline: "Timeline", clips: list[Clip]) -> list[list[Clip]]:
    """Group clips that are joined by transitions into crossfade chains."""
    by_id = {c.id: c for c in clips}
    successors = {
        t.from_clip_id: t.to_clip_id
        for t in timeline.transitions
        if t.from_clip_id in by_id and t.to_clip_id in by_id
    }
    predecessors = set(successors.values())

    sequences: list[list[Clip]] = []
    consumed: set[str] = set()
    for clip in sorted(clips, key=lambda c: (c.track_id, c.start)):
        if clip.id in consumed or clip.id in predecessors:
            continue
        chain = [clip]
        consumed.add(clip.id)
        nxt = successors.get(clip.id)
        while nxt and nxt not in consumed:
            chain.append(by_id[nxt])
            consumed.add(nxt)
            nxt = successors.get(nxt)
        sequences.append(chain)
    return sequences


def _sequence_duration(timeline: "Timeline", sequence: list[Clip]) -> float:
    total = sum(c.duration for c in sequence)
    for a, b in zip(sequence, sequence[1:]):
        transition = timeline.transition_between(a.id, b.id)
        total -= transition.duration if transition else 0.0
    return total


def _has_nvenc(ffmpeg: str) -> bool:
    """Ask the card to encode a frame, rather than reading a list.

    `ffmpeg -encoders` lists `h264_nvenc` on machines whose driver refuses it at
    runtime, so this used to be wrong in both directions. `core.engine.gpu`
    probes for real and caches the answer.
    """
    from core.engine import gpu

    return gpu.can_encode()


def build_command(
    timeline: Timeline,
    output: Path,
    *,
    ffmpeg: str | None = None,
    quality: dict | None = None,
    ass_path: Path | None = None,
) -> list[str]:
    """Compose the full FFmpeg argument list for a timeline."""
    ffmpeg = ffmpeg or ffmpeg_binary()
    total = timeline.duration
    if total <= 0:
        raise ValueError("timeline is empty")

    hidden = {t.id for t in timeline.tracks if t.hidden}
    text_clips = [
        c for c in timeline.clips
        if c.kind == "text" and (c.text or c.label) and c.track_id not in hidden
    ]
    playable = [c for c in timeline.clips if c.src and Path(c.src).exists() and c.kind != "text"]
    muted = {t.id for t in timeline.tracks if t.muted}
    # Mute silences, hide removes the picture — two different switches, and the
    # export has to make exactly the same distinction as the monitor.
    video_clips = [
        c for c in playable
        if c.kind == "video" and c.track_id not in hidden and _has_video_stream(c.src)  # type: ignore[arg-type]
    ]
    audio_clips = [
        c
        for c in playable
        if c.track_id not in muted
        and not c.props.muted
        and c.props.volume > 0
        and _has_audio_stream(c.src)  # type: ignore[arg-type]
    ]

    args: list[str] = [ffmpeg, "-hide_banner", "-y"]

    # Solid canvas as the base layer — defines gaps and the output geometry.
    args += [
        "-f", "lavfi",
        "-i", f"color=c=black:s={timeline.width}x{timeline.height}:r={timeline.fps}:d={total:.3f}",
    ]

    for clip in playable:
        # A reversed clip has to be decoded whole, so it cannot be seek-trimmed.
        if clip.props.reversed:
            args += ["-i", clip.src]  # type: ignore[arg-type]
        else:
            args += [
                "-ss", f"{clip.offset:.3f}",
                "-t", f"{clip.source_window:.3f}",
                "-i", clip.src,  # type: ignore[arg-type]
            ]

    index_of = {clip.id: i + 1 for i, clip in enumerate(playable)}

    steps: list[str] = []

    # ---- video ---------------------------------------------------------
    def video_chain(clip: Clip, label: str) -> str:
        """Normalise one clip to the canvas, applying its per-clip effects."""
        idx = index_of[clip.id]
        chain = [f"[{idx}:v]"]
        parts: list[str] = []

        if clip.props.reversed:
            parts.append(f"trim=start={clip.offset:.3f}:duration={clip.source_window:.3f}")
            parts.append("setpts=PTS-STARTPTS")
            parts.append("reverse")

        left, top, right, bottom = clip.props.crop
        if any(v > 0.001 for v in (left, top, right, bottom)):
            parts.append(
                f"crop=iw*{max(0.05, 1 - left - right):.4f}:ih*{max(0.05, 1 - top - bottom):.4f}"
                f":iw*{left:.4f}:ih*{top:.4f}"
            )

        x, y, scale, rotate = clip.props.transform

        # Animated channels, if any. `t` inside a clip chain is clip-local, which
        # is the same clock the editor samples its keyframes on.
        rotate_expr = keyframe_expression(clip.props.keyframes, "rotate", rotate)
        scale_expr = keyframe_expression(clip.props.keyframes, "scale", scale)
        x_expr = keyframe_expression(clip.props.keyframes, "x", x)
        y_expr = keyframe_expression(clip.props.keyframes, "y", y)
        animated_geometry = any(e is not None for e in (scale_expr, x_expr, y_expr))

        if rotate_expr is not None:
            # Keep the frame size fixed: a per-frame rotw()/roth() would resize
            # the stream on every frame and the overlay below would jitter.
            parts.append(f"rotate=a='({rotate_expr})*PI/180':c=none:ow=iw:oh=ih")
        elif abs(rotate) > 0.01:
            parts.append(f"rotate={rotate}*PI/180:c=none:ow=rotw({rotate}*PI/180):oh=roth({rotate}*PI/180)")

        if animated_geometry:
            # Resize per frame, then place the (varying) picture on a transparent
            # canvas with expressions — this is the only combination in FFmpeg
            # that reproduces "scale about the centre, then translate".
            size = scale_expr if scale_expr is not None else f"{scale:.6f}"
            parts.append(
                f"scale=w='trunc(({size})*{timeline.width}/2)*2':h=-2:eval=frame"
            )
        else:
            target_w = max(2, int(timeline.width * scale))
            target_h = max(2, int(timeline.height * scale))
            parts.append(f"scale={target_w}:{target_h}:force_original_aspect_ratio=decrease")
            parts.append(
                f"pad={timeline.width}:{timeline.height}:(ow-iw)/2+{int(x * timeline.width)}"
                f":(oh-ih)/2+{int(y * timeline.height)}:color=black@0"
            )
        parts.extend(_look_filters(clip.props.filter))
        parts.extend(_adjust_filters(clip.props.adjust))
        parts.append("setsar=1")
        parts.append(f"fps={timeline.fps}")

        if clip.props.speed != 1.0:
            parts.append(f"setpts=PTS/{clip.props.speed:.6f}")

        if clip.props.opacity < 0.999:
            parts.append("format=rgba")
            parts.append(f"colorchannelmixer=aa={clip.props.opacity:.3f}")

        parts.extend(
            _animation_filters(
                clip.props.anim_in, clip.props.anim_duration, clip.duration, True,
                timeline.width, timeline.height,
            )
        )
        parts.extend(
            _animation_filters(
                clip.props.anim_out, clip.props.anim_duration, clip.duration, False,
                timeline.width, timeline.height,
            )
        )

        if clip.props.fade_in > 0:
            parts.append(f"fade=t=in:st=0:d={clip.props.fade_in:.3f}:alpha=1")
        if clip.props.fade_out > 0:
            parts.append(
                f"fade=t=out:st={max(0.0, clip.duration - clip.props.fade_out):.3f}"
                f":d={clip.props.fade_out:.3f}:alpha=1"
            )

        if animated_geometry:
            # Finish the per-clip chain, then composite it onto a transparent
            # canvas of the project size with time-varying placement.
            moving = f"{label[:-1]}_kf]"
            canvas = f"{label[:-1]}_bg]"
            steps.append("".join(chain) + ",".join(parts) + moving)
            steps.append(
                f"color=c=black@0:s={timeline.width}x{timeline.height}"
                f":r={timeline.fps}:d={clip.duration:.3f}{canvas}"
            )
            place_x = x_expr if x_expr is not None else f"{x:.6f}"
            place_y = y_expr if y_expr is not None else f"{y:.6f}"
            steps.append(
                f"{canvas}{moving}overlay=x='(main_w-w)/2+({place_x})*main_w'"
                f":y='(main_h-h)/2+({place_y})*main_h':eval=frame:shortest=1{label}"
            )
            return label

        steps.append("".join(chain) + ",".join(parts) + label)
        return label

    # Clips chained by transitions must be crossfaded together before they are
    # placed, so xfade sees two aligned streams — that is what unlocks all of
    # FFmpeg's transition types rather than a hand-rolled alpha ramp.
    sequences = _build_sequences(timeline, video_clips)

    current = "[0:v]"
    placed = 0
    for seq_index, sequence in enumerate(sequences):
        head = sequence[0]
        if len(sequence) == 1:
            label = video_chain(head, f"[v{seq_index}]")
            seq_start = head.start
            seq_label = label
        else:
            labels = [video_chain(clip, f"[v{seq_index}_{i}]") for i, clip in enumerate(sequence)]
            seq_label = labels[0]
            elapsed = sequence[0].duration
            for i in range(1, len(sequence)):
                transition = timeline.transition_between(sequence[i - 1].id, sequence[i].id)
                d = min(transition.duration if transition else 0.5, sequence[i - 1].duration, sequence[i].duration)
                kind = transition.type if transition else "fade"
                out = f"[x{seq_index}_{i}]"
                steps.append(
                    f"{seq_label}{labels[i]}xfade=transition={kind}:duration={d:.3f}"
                    f":offset={max(0.0, elapsed - d):.3f}{out}"
                )
                elapsed = elapsed - d + sequence[i].duration
                seq_label = out
            seq_start = head.start

        seq_end = seq_start + _sequence_duration(timeline, sequence)
        shifted = f"[p{seq_index}]"
        steps.append(f"{seq_label}setpts=PTS-STARTPTS+{seq_start:.3f}/TB{shifted}")
        out = f"[bg{seq_index}]" if seq_index < len(sequences) - 1 else "[vout]"
        steps.append(
            f"{current}{shifted}overlay=eof_action=pass:enable='between(t,{seq_start:.3f},{seq_end:.3f})'{out}"
        )
        current = out
        placed += 1

    if placed == 0:
        steps.append("[0:v]null[vout]")

    # ---- text and captions ---------------------------------------------
    # One ASS document carries every text cue, so libass draws them in a single
    # pass with correct shaping and bidi for Persian.
    if text_clips and ass_path is not None:
        cues = subs.cues_from_clips([c.as_text_dict() for c in text_clips])
        if cues:
            subs.write_ass(cues, timeline.width, timeline.height, ass_path, getattr(timeline, "bpm", 0.0))
            fonts = subs.fonts_dir()
            arg = f"subtitles={subs.filter_path(ass_path)}"
            if fonts:
                arg += f":fontsdir={subs.filter_path(Path(fonts))}"
            steps.append(f"[vout]{arg}[vtext]")
            steps.append("[vtext]null[voutfinal]")

    # ---- audio ---------------------------------------------------------
    # Ducking only makes sense when there is both a bed to lower and a voice to
    # lower it under; otherwise every clip takes the plain path.
    # Ducking is computed, not side-chained.
    #
    # `sidechaincompress` was tried first and is a trap in a big graph: when its
    # key input reaches EOF a moment before the main — which happens under load,
    # not on an idle machine — the filter emits silence for the rest of the
    # render, so the music simply disappeared from the last word onward. Instead
    # the voice envelope is measured here and applied as a volume automation
    # curve: one stream, deterministic, and inspectable as numbers.
    ducked_clips = [c for c in audio_clips if c.props.duck]
    voice_clips = [c for c in audio_clips if not c.props.duck]
    duck_curve: str | None = None
    if ducked_clips and voice_clips:
        from core.engine import audio as audio_engine

        activity = audio_engine.voice_envelope(
            [(audio_engine.voice_source(c.src), c.start, c.offset, c.duration)
             for c in voice_clips if c.src],  # type: ignore[misc]
            total,
        )
        points = audio_engine.ducking_points(activity)
        if points:
            duck_curve = _piecewise_expression(points)
    audio_labels: list[str] = []
    for n, clip in enumerate(audio_clips):
        idx = index_of[clip.id]
        label = f"[a{n}]"
        parts = ["aresample=48000"]
        if clip.props.reversed:
            parts.insert(0, f"atrim=start={clip.offset:.3f}:duration={clip.source_window:.3f}")
            parts.insert(1, "asetpts=PTS-STARTPTS")
            parts.insert(2, "areverse")
        if clip.props.speed != 1.0:
            for factor in _atempo_chain(clip.props.speed):
                parts.append(f"atempo={factor:.6f}")
        if clip.props.denoise > 0.01:
            # afftdn is spectral denoise; 0..1 maps to a sane 6..24 dB reduction
            parts.append(f"afftdn=nr={6 + clip.props.denoise * 18:.1f}:nf=-25")
        if clip.props.enhance_voice:
            parts.extend([
                "highpass=f=90",
                "equalizer=f=3000:width_type=o:width=1:g=3",
                "acompressor=threshold=-18dB:ratio=3:attack=15:release=180",
                "loudnorm=I=-16:TP=-1.5:LRA=11",
            ])
        if clip.props.duck and duck_curve:
            # The bed's own volume, times the ducking curve. `eval=frame` is what
            # makes it follow time at all.
            parts.append(f"volume=volume='({clip.props.volume:.3f})*({duck_curve})':eval=frame")
        volume_expr = None if clip.props.duck and duck_curve else keyframe_expression(
            clip.props.keyframes, "volume", clip.props.volume
        )
        if volume_expr is not None:
            # eval=frame is what makes the expression time-varying at all.
            parts.append(f"volume=volume='{volume_expr}':eval=frame")
        elif clip.props.volume != 1.0 and not (clip.props.duck and duck_curve):
            parts.append(f"volume={clip.props.volume:.3f}")
        if clip.props.fade_in > 0:
            parts.append(f"afade=t=in:st=0:d={clip.props.fade_in:.3f}")
        if clip.props.fade_out > 0:
            parts.append(
                f"afade=t=out:st={max(0.0, clip.duration - clip.props.fade_out):.3f}:d={clip.props.fade_out:.3f}"
            )
        delay_ms = int(clip.start * 1000)
        parts.append(f"adelay={delay_ms}|{delay_ms}")

        parts.append(f"apad=whole_dur={total:.3f}")
        steps.append(f"[{idx}:a]" + ",".join(parts) + label)

        audio_labels.append(label)

    if audio_labels:
        if len(audio_labels) == 1:
            steps.append(
                audio_labels[0] + f"atrim=0:{total:.3f},alimiter=limit=0.95[aout]"
            )
        else:
            steps.append(
                "".join(audio_labels)
                + f"amix=inputs={len(audio_labels)}:duration=longest:dropout_transition=0,"
                f"atrim=0:{total:.3f},alimiter=limit=0.95[aout]"
            )

    # ---- Package B brand overlays (opt-in, appended after everything else) ----
    final_label = "[voutfinal]" if any("[voutfinal]" in step for step in steps) else "[vout]"
    if timeline.progress_bar:
        steps.append(
            f"{final_label}drawbox=x=0:y=ih-12:w='min(iw\,iw*t/{total:.3f})':h=8:"
            "color=0x06B6D4@0.9:t=fill:eval=frame[vbrand]"
        )
        final_label = "[vbrand]"
    if timeline.brand_text:
        font = _brand_font()
        if font:
            safe = timeline.brand_text.replace(":", "\\:").replace("'", "’")
            steps.append(
                f"{final_label}drawtext=fontfile={font}:text='{safe}':"
                "x=w-tw-24:y=h-th-28:fontsize=26:fontcolor=white@0.55[vbrand2]"
            )
            final_label = "[vbrand2]"

    args += ["-filter_complex", ";".join(steps)]
    args += ["-map", final_label]
    if audio_labels:
        args += ["-map", "[aout]", "-c:a", "aac", "-b:a", "192k"]
    else:
        args += ["-an"]

    quality = quality or {"crf": 21, "preset": "veryfast", "nvenc_cq": 23}
    if _has_nvenc(ffmpeg):
        args += ["-c:v", "h264_nvenc", "-preset", "p4", "-cq", str(quality.get("nvenc_cq", 23))]
    else:
        args += [
            "-c:v", "libx264",
            "-preset", str(quality.get("preset", "veryfast")),
            "-crf", str(quality.get("crf", 21)),
        ]

    args += [
        "-pix_fmt", "yuv420p",
        "-movflags", "+faststart",
        "-t", f"{total:.3f}",
        "-progress", "pipe:1",
        "-nostats",
        str(output),
    ]
    return args


def render(
    timeline: Timeline,
    output: Path,
    on_progress: Callable[[float, str], None] | None = None,
    quality: dict | None = None,
) -> Path:
    """Run the render, reporting progress in percent."""
    output.parent.mkdir(parents=True, exist_ok=True)
    total = timeline.duration
    ass_path = output.with_suffix(".ass")
    command = build_command(timeline, output, quality=quality, ass_path=ass_path)

    process = subprocess.Popen(
        command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1
    )

    assert process.stdout is not None
    for line in process.stdout:
        line = line.strip()
        if line.startswith("out_time_ms=") and total > 0:
            try:
                seconds = int(line.split("=", 1)[1]) / 1_000_000
            except ValueError:
                continue
            percent = max(0.0, min(99.0, seconds / total * 100))
            if on_progress:
                on_progress(percent, "render")

    stderr = process.stderr.read() if process.stderr else ""
    code = process.wait()
    if code != 0:
        raise RuntimeError(f"ffmpeg failed ({code}): {_tail(stderr)}")
    if on_progress:
        on_progress(100.0, "render")
    # the subtitle script is an intermediate artefact, not something to leave behind
    try:
        if ass_path.exists():
            ass_path.unlink()
    except OSError:
        pass
    return output


def _tail(text: str, lines: int = 12) -> str:
    return "\n".join(text.strip().splitlines()[-lines:])


def export_dir() -> Path:
    path = settings.export_dir
    path.mkdir(parents=True, exist_ok=True)
    return path


def unique_output(name: str) -> Path:
    safe = "".join(ch for ch in name if ch.isalnum() or ch in " -_").strip() or "timeline"
    candidate = export_dir() / f"{safe}.mp4"
    counter = 2
    while candidate.exists():
        candidate = export_dir() / f"{safe} ({counter}).mp4"
        counter += 1
    return candidate


def iter_missing_sources(timeline: Timeline) -> Iterable[str]:
    for clip in timeline.clips:
        if clip.src and not Path(clip.src).exists():
            yield clip.src
