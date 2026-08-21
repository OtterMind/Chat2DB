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

    @property
    def end(self) -> float:
        return self.start + self.duration


@dataclass
class Track:
    id: str
    kind: str = "video"
    name: str = ""
    muted: bool = False


@dataclass
class Timeline:
    tracks: list[Track] = field(default_factory=list)
    clips: list[Clip] = field(default_factory=list)
    width: int = 1080
    height: int = 1920
    fps: int = 30

    @property
    def duration(self) -> float:
        return max((c.end for c in self.clips), default=0.0)

    @classmethod
    def from_dict(cls, data: dict) -> "Timeline":
        tracks = [
            Track(
                id=str(t["id"]),
                kind=t.get("kind", "video"),
                name=t.get("name", ""),
                muted=bool(t.get("muted", False)),
            )
            for t in data.get("tracks", [])
        ]
        kind_by_track = {t.id: t.kind for t in tracks}
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
            )
            for c in data.get("clips", [])
        ]
        return cls(
            tracks=tracks,
            clips=clips,
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


def _has_nvenc(ffmpeg: str) -> bool:
    try:
        out = subprocess.run([ffmpeg, "-hide_banner", "-encoders"], capture_output=True, text=True, timeout=20)
        return "h264_nvenc" in out.stdout
    except Exception:
        return False


def build_command(timeline: Timeline, output: Path, *, ffmpeg: str | None = None) -> list[str]:
    """Compose the full FFmpeg argument list for a timeline."""
    ffmpeg = ffmpeg or ffmpeg_binary()
    total = timeline.duration
    if total <= 0:
        raise ValueError("timeline is empty")

    playable = [c for c in timeline.clips if c.src and Path(c.src).exists()]
    muted = {t.id for t in timeline.tracks if t.muted}
    # Only branch a stream that the source actually contains.
    video_clips = [c for c in playable if c.kind == "video" and _has_video_stream(c.src)]  # type: ignore[arg-type]
    audio_clips = [
        c for c in playable if c.track_id not in muted and _has_audio_stream(c.src)  # type: ignore[arg-type]
    ]

    args: list[str] = [ffmpeg, "-hide_banner", "-y"]

    # Solid canvas as the base layer — defines gaps and the output geometry.
    args += [
        "-f", "lavfi",
        "-i", f"color=c=black:s={timeline.width}x{timeline.height}:r={timeline.fps}:d={total:.3f}",
    ]

    for clip in playable:
        args += ["-ss", f"{clip.offset:.3f}", "-t", f"{clip.duration:.3f}", "-i", clip.src]  # type: ignore[arg-type]

    index_of = {clip.id: i + 1 for i, clip in enumerate(playable)}
    steps: list[str] = []

    # ---- video ---------------------------------------------------------
    current = "[0:v]"
    for n, clip in enumerate(video_clips):
        idx = index_of[clip.id]
        scaled = f"[v{n}]"
        steps.append(
            f"[{idx}:v]scale={timeline.width}:{timeline.height}:force_original_aspect_ratio=decrease,"
            f"pad={timeline.width}:{timeline.height}:-1:-1:color=black,"
            f"setsar=1,fps={timeline.fps},setpts=PTS-STARTPTS+{clip.start:.3f}/TB{scaled}"
        )
        out = f"[bg{n}]" if n < len(video_clips) - 1 else "[vout]"
        steps.append(
            f"{current}{scaled}overlay=eof_action=pass:enable='between(t,{clip.start:.3f},{clip.end:.3f})'{out}"
        )
        current = out
    if not video_clips:
        steps.append("[0:v]null[vout]")

    # ---- audio ---------------------------------------------------------
    audio_labels: list[str] = []
    for n, clip in enumerate(audio_clips):
        idx = index_of[clip.id]
        label = f"[a{n}]"
        delay_ms = int(clip.start * 1000)
        steps.append(
            f"[{idx}:a]aresample=48000,adelay={delay_ms}|{delay_ms},apad=whole_dur={total:.3f}{label}"
        )
        audio_labels.append(label)

    if audio_labels:
        steps.append(
            "".join(audio_labels)
            + f"amix=inputs={len(audio_labels)}:duration=longest:dropout_transition=0,"
            f"atrim=0:{total:.3f},alimiter=limit=0.95[aout]"
        )

    args += ["-filter_complex", ";".join(steps)]
    args += ["-map", "[vout]"]
    if audio_labels:
        args += ["-map", "[aout]", "-c:a", "aac", "-b:a", "192k"]
    else:
        args += ["-an"]

    if _has_nvenc(ffmpeg):
        args += ["-c:v", "h264_nvenc", "-preset", "p4", "-cq", "23"]
    else:
        args += ["-c:v", "libx264", "-preset", "veryfast", "-crf", "21"]

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
) -> Path:
    """Run the render, reporting progress in percent."""
    output.parent.mkdir(parents=True, exist_ok=True)
    total = timeline.duration
    command = build_command(timeline, output)

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
