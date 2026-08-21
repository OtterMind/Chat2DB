"""Shared fixtures — synthetic media so tests never depend on sample files."""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from core.engine.compose import ffmpeg_binary  # noqa: E402


def _ffmpeg_available() -> bool:
    exe = ffmpeg_binary()
    return bool(shutil.which(exe) or Path(exe).exists())


requires_ffmpeg = pytest.mark.skipif(not _ffmpeg_available(), reason="FFmpeg not available")


def _run(args: list[str]) -> None:
    subprocess.run([ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


@pytest.fixture(scope="session")
def media(tmp_path_factory) -> dict[str, Path]:
    """A tiny library: two silent videos, one tone, one speech-like track."""
    base = tmp_path_factory.mktemp("media")
    clip_a = base / "clip_a.mp4"
    clip_b = base / "clip_b.mp4"
    tone = base / "tone.m4a"
    gaps = base / "gaps.wav"
    shots = base / "shots.mp4"

    _run(["-f", "lavfi", "-i", "testsrc=size=640x360:rate=25:duration=4", "-c:v", "libx264", "-pix_fmt", "yuv420p", str(clip_a)])
    _run(["-f", "lavfi", "-i", "smptebars=size=640x360:rate=25:duration=3", "-c:v", "libx264", "-pix_fmt", "yuv420p", str(clip_b)])
    _run(["-f", "lavfi", "-i", "sine=frequency=440:duration=6", "-c:a", "aac", str(tone)])
    # 2s tone, 1.5s silence, 2s tone, 1.2s silence, 2s tone
    _run([
        "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
        "-f", "lavfi", "-i", "anullsrc=duration=1.5",
        "-f", "lavfi", "-i", "sine=frequency=660:duration=2",
        "-f", "lavfi", "-i", "anullsrc=duration=1.2",
        "-f", "lavfi", "-i", "sine=frequency=880:duration=2",
        "-filter_complex", "[0:a][1:a][2:a][3:a][4:a]concat=n=5:v=0:a=1[a]",
        "-map", "[a]", str(gaps),
    ])
    # three distinct shots of 3s each
    _run([
        "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25:duration=3",
        "-f", "lavfi", "-i", "smptebars=size=640x360:rate=25:duration=3",
        "-f", "lavfi", "-i", "color=c=blue:size=640x360:rate=25:duration=3",
        "-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]",
        "-map", "[v]", "-c:v", "libx264", "-pix_fmt", "yuv420p", str(shots),
    ])
    return {"clip_a": clip_a, "clip_b": clip_b, "tone": tone, "gaps": gaps, "shots": shots}
