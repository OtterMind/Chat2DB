"""Cutting Edge (CE) — media analysis for edit automation.

Two operations that remove most of the manual work in a rough cut:

* silence detection — the idea behind `auto-editor`, implemented with FFmpeg's
  own `silencedetect` filter so it needs no extra dependency and works with the
  binary we already ship.
* scene detection — PySceneDetect when available (already pinned), with an
  FFmpeg-only fallback so the feature never disappears on a trimmed install.

Both return plain time ranges; deciding what to do with them is the editor's job,
which keeps every change undoable and never touches the source media.
"""
from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from core.engine.compose import ffmpeg_binary, probe_media  # noqa: F401


@dataclass
class Range:
    start: float
    end: float

    @property
    def duration(self) -> float:
        return max(0.0, self.end - self.start)

    def as_dict(self) -> dict:
        return {"start": round(self.start, 3), "end": round(self.end, 3)}


_SILENCE_START = re.compile(r"silence_start:\s*(-?[\d.]+)")
_SILENCE_END = re.compile(r"silence_end:\s*(-?[\d.]+)")


def detect_silence(
    path: str,
    *,
    noise_db: float = -32.0,
    min_silence: float = 0.35,
    padding: float = 0.05,
) -> list[Range]:
    """Silent stretches in a media file.

    `padding` keeps a sliver of room tone on both sides so cuts do not clip the
    first consonant of a word — the single most common complaint about automatic
    silence removal.
    """
    command = [
        ffmpeg_binary(), "-hide_banner", "-nostats", "-i", path,
        "-af", f"silencedetect=noise={noise_db}dB:d={min_silence}",
        "-f", "null", "-",
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    text = result.stderr

    duration = 0.0
    try:
        duration = float(probe_media(path).get("duration") or 0.0)
    except Exception:
        pass

    ranges: list[Range] = []
    pending: float | None = None
    for line in text.splitlines():
        start = _SILENCE_START.search(line)
        if start:
            pending = float(start.group(1))
            continue
        end = _SILENCE_END.search(line)
        if end and pending is not None:
            ranges.append(Range(pending, float(end.group(1))))
            pending = None
    if pending is not None:
        ranges.append(Range(pending, duration or pending))

    trimmed: list[Range] = []
    for r in ranges:
        start = max(0.0, r.start + padding)
        end = r.end - padding if duration == 0 or r.end < duration else r.end
        if end - start >= min_silence * 0.5:
            trimmed.append(Range(start, end))
    return trimmed


def detect_scenes(path: str, *, threshold: float = 27.0) -> list[float]:
    """Timestamps where the shot changes, in seconds."""
    try:
        from scenedetect import ContentDetector, SceneManager, open_video  # type: ignore

        video = open_video(path)
        manager = SceneManager()
        manager.add_detector(ContentDetector(threshold=threshold))
        manager.detect_scenes(video, show_progress=False)
        scenes = manager.get_scene_list()
        return [round(start.get_seconds(), 3) for start, _ in scenes][1:]
    except Exception:
        return _detect_scenes_ffmpeg(path)


def _detect_scenes_ffmpeg(path: str, *, sensitivity: float = 0.4) -> list[float]:
    """Fallback that only needs the bundled FFmpeg."""
    command = [
        ffmpeg_binary(), "-hide_banner", "-nostats", "-i", path,
        "-filter:v", f"select='gt(scene,{sensitivity})',showinfo",
        "-f", "null", "-",
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    times = [float(m) for m in re.findall(r"pts_time:([\d.]+)", result.stderr)]
    return [round(t, 3) for t in times]


def keep_ranges(duration: float, remove: list[Range], *, minimum: float = 0.15) -> list[Range]:
    """Invert a list of ranges to remove into the ranges worth keeping."""
    keep: list[Range] = []
    cursor = 0.0
    for r in sorted(remove, key=lambda x: x.start):
        if r.start - cursor >= minimum:
            keep.append(Range(cursor, r.start))
        cursor = max(cursor, r.end)
    if duration - cursor >= minimum:
        keep.append(Range(cursor, duration))
    return keep


def analyse(path: str) -> dict:
    """Everything the editor needs in one round trip."""
    if not Path(path).exists():
        raise FileNotFoundError(path)
    info = probe_media(path)
    silences = detect_silence(path) if info.get("has_audio") else []
    scenes = detect_scenes(path) if info.get("has_video") else []
    return {
        "media": info,
        "silences": [r.as_dict() for r in silences],
        "speech": [r.as_dict() for r in keep_ranges(info.get("duration", 0.0), silences)],
        "scenes": scenes,
    }


__all__ = ["Range", "detect_silence", "detect_scenes", "keep_ranges", "analyse", "probe_media"]
