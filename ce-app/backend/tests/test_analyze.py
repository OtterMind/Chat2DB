"""Silence and scene detection are checked against known ground truth."""
from __future__ import annotations

from core.engine import analyze
from tests.conftest import requires_ffmpeg


@requires_ffmpeg
def test_silence_matches_the_known_gaps(media):
    # source: tone 0-2, silence 2-3.5, tone 3.5-5.5, silence 5.5-6.7, tone 6.7-8.7
    ranges = analyze.detect_silence(str(media["gaps"]))
    assert len(ranges) == 2

    first, second = ranges
    assert abs(first.start - 2.0) < 0.15 and abs(first.end - 3.5) < 0.15
    assert abs(second.start - 5.5) < 0.15 and abs(second.end - 6.7) < 0.15


@requires_ffmpeg
def test_speech_is_the_inverse_of_silence(media):
    result = analyze.analyse(str(media["gaps"]))
    speech = result["speech"]
    assert len(speech) == 3
    assert speech[0]["start"] == 0.0
    # nothing kept may overlap a detected silence
    for gap in result["silences"]:
        for part in speech:
            assert part["end"] <= gap["start"] + 0.01 or part["start"] >= gap["end"] - 0.01


@requires_ffmpeg
def test_scene_cuts_are_found_at_shot_boundaries(media):
    scenes = analyze.detect_scenes(str(media["shots"]))
    assert len(scenes) >= 2
    assert any(abs(t - 3.0) < 0.3 for t in scenes)
    assert any(abs(t - 6.0) < 0.3 for t in scenes)
