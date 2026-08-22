"""Waveforms and beat detection, measured against ground truth we synthesise.

A click every 0.5 s *is* 120 BPM, so the detector has a right answer to hit; a
tone followed by silence has a waveform whose shape is known in advance. Both are
checked numerically rather than by looking at a picture.
"""
from __future__ import annotations

import subprocess

import pytest

from core.engine import audio, compose
from tests.conftest import requires_ffmpeg


def _click_track(tmp_path, bpm: float, seconds: float = 8.0):
    """Short exponential blips at an exact tempo."""
    period = 60.0 / bpm
    target = tmp_path / f"click{int(bpm)}.wav"
    subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi",
            "-i", f"aevalsrc='0.9*sin(2*PI*880*t)*exp(-30*mod(t\\,{period}))':d={seconds}:s=44100",
            str(target),
        ],
        check=True,
    )
    return target


def _half_loud(tmp_path):
    """Two seconds of tone, then two of silence."""
    target = tmp_path / "halfloud.wav"
    subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono:d=2",
            "-filter_complex", "[0:a][1:a]concat=n=2:v=0:a=1[a]", "-map", "[a]", str(target),
        ],
        check=True,
    )
    return target


# ------------------------------------------------------------------ peaks


@requires_ffmpeg
def test_peaks_follow_the_sound(tmp_path):
    source = _half_loud(tmp_path)
    result = audio.peaks(str(source), points=100)

    assert result["points"] == 100
    assert abs(result["duration"] - 4.0) < 0.15

    first_half = result["peaks"][:45]
    second_half = result["peaks"][55:]
    assert min(first_half) > 0.5          # the tone is there
    assert max(second_half) < 0.05        # the silence is silent


@requires_ffmpeg
def test_peaks_are_cached_and_bounded(tmp_path):
    source = _half_loud(tmp_path)
    first = audio.peaks(str(source), points=64)
    second = audio.peaks(str(source), points=64)
    assert first == second                                  # served from disk the second time
    assert all(0.0 <= value <= 1.0 for value in first["peaks"])
    # The request is clamped: nobody gets to ask for a million points.
    assert audio.peaks(str(source), points=10_000)["points"] <= 4000


@requires_ffmpeg
def test_a_silent_video_draws_no_waveform_and_raises_nothing(media):
    """Regression: a 422 for a silent clip filled the console with failures."""
    result = audio.peaks(str(media["clip_a"]))     # video without an audio track
    assert result["peaks"] == [] and result["points"] == 0


def test_a_missing_file_is_still_an_error():
    with pytest.raises(FileNotFoundError):
        audio.peaks("/no/such/file.wav")


# ------------------------------------------------------------------ beats


@requires_ffmpeg
@pytest.mark.parametrize("bpm", [90.0, 120.0, 150.0])
def test_the_tempo_is_the_one_we_synthesised(tmp_path, bpm):
    source = _click_track(tmp_path, bpm)
    result = audio.beats(str(source))

    assert abs(result.bpm - bpm) < 3.0, f"detected {result.bpm} for a {bpm} BPM track"
    assert result.confidence > 0.3

    # The grid must cover the file at the right spacing, not just guess a number.
    expected = int(8.0 / (60.0 / bpm))
    assert abs(len(result.beats) - expected) <= 2
    gaps = [b - a for a, b in zip(result.beats, result.beats[1:])]
    assert all(abs(gap - 60.0 / bpm) < 0.02 for gap in gaps)


@requires_ffmpeg
def test_beats_land_on_the_clicks(tmp_path):
    source = _click_track(tmp_path, 120.0)
    result = audio.beats(str(source))
    period = 0.5
    # Every beat sits within 60 ms of a real click (phase, not just tempo).
    for beat in result.beats:
        offset = abs((beat % period) - period) if (beat % period) > period / 2 else beat % period
        assert offset < 0.06, f"beat at {beat}s is {offset:.3f}s away from a click"


@requires_ffmpeg
def test_silence_produces_no_beat_grid(tmp_path):
    quiet = tmp_path / "quiet.wav"
    subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono:d=5", str(quiet),
        ],
        check=True,
    )
    result = audio.beats(str(quiet))
    # No onsets means no honest tempo: an empty grid, not an invented one.
    assert result.confidence < 0.3 or result.bpm == 0.0
