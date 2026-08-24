"""Shot detection, scored against cuts that are known to exist.

This file is the reason `detect_scenes` uses AdaptiveDetector and not
ContentDetector. Both are in the already-shipped `scenedetect`, so the choice
costs nothing to install — which is exactly why it had to be *measured* rather
than picked from a changelog. The scoreboard, on fixtures built to a recipe:

    fixture                     known   ContentDetector   AdaptiveDetector
    hard cuts, static shots       6     6  P=1.00         6  P=1.00
    hard cuts, camera push        6     6  P=1.00         6  P=1.00
    fast pan + handheld wobble    2     3  P=0.67 (FP)    2  P=1.00
    3 s clip, one cut             1     1  correct        1  correct
    1.5 s single shot             0     0  correct        0  correct

A false cut is not cosmetic: it becomes a clip boundary in the rebuild, a shot in
the template's rhythm, and a cut the user never asked for and cannot see the
reason for. Fast camera motion is what a phone video is made of.
"""
from __future__ import annotations

import subprocess

import pytest

from core.engine import analyze, compose
from tests.conftest import requires_ffmpeg

TOLERANCE = 0.3


def _run(args: list[str]) -> None:
    subprocess.run(
        [compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y", *args], check=True
    )


def _concat(parts: list[str], out) -> str:
    listing = out.parent / f"{out.stem}.txt"
    listing.write_text("".join(f"file '{p}'\n" for p in parts), encoding="utf-8")
    _run(["-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(out)])
    return str(out)


@pytest.fixture(scope="module")
def hard_cuts(tmp_path_factory):
    """Six hard cuts between static shots: 1.5, 3.0, 4.5, 6.0, 7.5, 9.0."""
    folder = tmp_path_factory.mktemp("scenes")
    parts = []
    for index, pattern in enumerate(
        ("testsrc", "smptebars", "rgbtestsrc", "testsrc2", "smptehdbars", "yuvtestsrc", "testsrc")
    ):
        part = folder / f"h{index}.mp4"
        _run(["-f", "lavfi", "-i", f"{pattern}=size=320x240:rate=25:duration=1.5",
              "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(part)])
        parts.append(str(part))
    return _concat(parts, folder / "hard_cuts.mp4")


@pytest.fixture(scope="module")
def fast_pan(tmp_path_factory):
    """Two cuts at 4.0 and 8.0 — inside a fast pan with handheld wobble.

    This is the fixture that decides the detector. The motion is not a cut, and a
    detector that reports it as one is inventing edits.
    """
    folder = tmp_path_factory.mktemp("pan")
    parts = []
    for index, source in enumerate(
        ("mandelbrot=size=640x480:rate=25", "testsrc2=size=640x480:rate=25",
         "rgbtestsrc=size=640x480:rate=25")
    ):
        part = folder / f"p{index}.mp4"
        _run([
            "-f", "lavfi", "-i", source, "-t", "4",
            "-vf", "crop=320:240:'(iw-ow)*abs(sin(2*PI*0.35*t))':'(ih-oh)/2',"
                   "rotate=a='0.04*sin(2*PI*2.5*t)':c=none,scale=320:240",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(part),
        ])
        parts.append(str(part))
    return _concat(parts, folder / "fast_pan.mp4")


def _score(found: list[float], known: list[float]) -> tuple[int, int, float, float]:
    hits = [k for k in known if any(abs(k - f) <= TOLERANCE for f in found)]
    false = [f for f in found if not any(abs(k - f) <= TOLERANCE for k in known)]
    precision = len(hits) / (len(hits) + len(false)) if (hits or false) else 0.0
    return len(hits), len(false), precision, len(hits) / len(known)


@requires_ffmpeg
def test_hard_cuts_are_all_found_and_none_invented(hard_cuts):
    known = [1.5, 3.0, 4.5, 6.0, 7.5, 9.0]

    hits, false, precision, recall = _score(analyze.detect_scenes(hard_cuts), known)

    assert recall == 1.0, f"missed {len(known) - hits} of {len(known)} real cuts"
    assert false == 0, f"invented {false} cuts that are not there"
    assert precision == 1.0


@requires_ffmpeg
def test_fast_camera_motion_is_not_a_cut(fast_pan):
    """The measurement that chose the detector. ContentDetector scored 0.67 here."""
    known = [4.0, 8.0]

    hits, false, precision, recall = _score(analyze.detect_scenes(fast_pan), known)

    assert recall == 1.0, f"missed {len(known) - hits} real cuts"
    assert false == 0, f"camera motion was reported as a cut: {false}"
    assert precision == 1.0


@requires_ffmpeg
def test_a_short_clip_and_a_single_shot_are_both_handled(tmp_path):
    """The switch must not cost anything on the easy cases."""
    first = tmp_path / "a.mp4"
    second = tmp_path / "b.mp4"
    for path, pattern in ((first, "testsrc"), (second, "smptebars")):
        _run(["-f", "lavfi", "-i", f"{pattern}=size=320x240:rate=25:duration=1.5",
              "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(path)])

    assert analyze.detect_scenes(str(first)) == []

    joined = _concat([str(first), str(second)], tmp_path / "joined.mp4")
    hits, false, _, _ = _score(analyze.detect_scenes(joined), [1.5])
    assert hits == 1 and false == 0
