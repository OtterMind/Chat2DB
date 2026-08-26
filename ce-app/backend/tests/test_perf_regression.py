"""Performance regression ratchet: the pipelines must stay strip-based and cheap.

These caps are not benchmarks; they are tripwires for the failure class this
project has actually shipped once — a loop that spawns one FFmpeg process per
frame (§16: "Frames come in strips, not one process each"), a detector that
quietly became O(n²), a renderer that re-walks the whole timeline per clip.
Measured baselines on 3 s fixtures in the sandbox:

    motion_curve 0.03 s · detect_silence 0.02 s · beats 0.02 s ·
    detect_scenes 0.20 s · build_ass(20 cues) < 1 ms

The caps sit ~25–100× above the measurement so a slow CI runner never flakes,
while a per-frame process loop (which would be tens of seconds here) trips
every one of them. A number that only ever passes is not a ratchet; these were
set from a real clock, and a future change that halves or doubles the work will
still pass — but a change that multiplies it by a thousand will not.
"""
from __future__ import annotations

import time

from core.engine import analyze, audio, subtitles
from tests.conftest import requires_ffmpeg

MOTION_CAP = 5.0
SILENCE_CAP = 5.0
BEATS_CAP = 5.0
SCENES_CAP = 20.0
ASS_CAP = 1.0


def _seconds(fn) -> float:
    began = time.time()
    fn()
    return time.time() - began


@requires_ffmpeg
def test_motion_curve_stays_one_decode(media):
    took = _seconds(lambda: analyze.motion_curve(str(media["shots"])))
    assert took < MOTION_CAP, f"motion_curve took {took:.1f}s — a per-frame loop?"


@requires_ffmpeg
def test_silence_detection_stays_one_pass(media):
    took = _seconds(lambda: analyze.detect_silence(str(media["gaps"])))
    assert took < SILENCE_CAP, f"detect_silence took {took:.1f}s"


@requires_ffmpeg
def test_beat_detection_stays_in_numpy(media):
    took = _seconds(lambda: audio.beats(str(media["gaps"])))
    assert took < BEATS_CAP, f"beats took {took:.1f}s"


@requires_ffmpeg
def test_scene_detection_stays_in_the_library(media):
    took = _seconds(lambda: analyze.detect_scenes(str(media["shots"])))
    assert took < SCENES_CAP, f"detect_scenes took {took:.1f}s"


def test_ass_building_is_string_math_not_io():
    cues = [subtitles.TextCue(start=i, end=i + 1, text="سلام دنیا", animate=False)
            for i in range(20)]
    took = _seconds(lambda: subtitles.build_ass(cues, 1080, 1920))
    assert took < ASS_CAP, f"build_ass took {took:.2f}s for 20 cues"
