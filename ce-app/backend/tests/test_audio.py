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


# ------------------------------------------------------------------ ducking


def _voice_bursts(tmp_path):
    """Quiet speech-like bursts: loud between 1–2 s and 3–4 s, silent elsewhere."""
    target = tmp_path / "voice.wav"
    subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi",
            "-i", "aevalsrc='0.25*sin(2*PI*300*t)*(between(t\\,1\\,2)+between(t\\,3\\,4))':d=5:s=48000",
            str(target),
        ],
        check=True,
    )
    return target


def _steady_music(tmp_path):
    target = tmp_path / "music.wav"
    subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "sine=frequency=220:duration=5:sample_rate=48000",
            str(target),
        ],
        check=True,
    )
    return target


def _mean_db(path, start: float, end: float, band: float | None = None) -> float:
    """Mean level of a window, optionally of one narrow frequency band.

    The band matters for ducking: during speech the mix is dominated by the
    voice, so measuring everything would hide what the music is doing. The bed is
    a 220 Hz tone and the voice is 300 Hz, so a narrow filter isolates the bed
    inside the finished file — no separate stem, no trust required.
    """
    chain = "volumedetect" if band is None else f"bandpass=f={band}:width_type=h:width=30,volumedetect"
    out = subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-nostats", "-y",
            "-ss", f"{start}", "-t", f"{end - start}", "-i", str(path),
            "-af", chain, "-f", "null", "-",
        ],
        capture_output=True, text=True, check=True,
    )
    for line in out.stderr.splitlines():
        if "mean_volume" in line:
            return float(line.split(":")[-1].replace("dB", "").strip())
    raise AssertionError("no mean_volume")


def _duck_timeline(music, voice, duck: bool) -> dict:
    return {
        "width": 320, "height": 240, "fps": 15,
        "tracks": [
            {"id": "v1", "kind": "video"},
            {"id": "a1", "kind": "audio"},
            {"id": "a2", "kind": "audio"},
        ],
        "clips": [
            {"id": "m", "trackId": "a1", "start": 0, "duration": 5, "offset": 0,
             "src": str(music), "props": {"duck": duck}},
            {"id": "v", "trackId": "a2", "start": 0, "duration": 5, "offset": 0,
             "src": str(voice)},
        ],
    }


@requires_ffmpeg
def test_the_music_steps_aside_for_the_voice(tmp_path):
    music, voice = _steady_music(tmp_path), _voice_bursts(tmp_path)

    plain = compose.render(
        compose.Timeline.from_dict(_duck_timeline(music, voice, duck=False)), tmp_path / "plain.mp4"
    )
    ducked = compose.render(
        compose.Timeline.from_dict(_duck_timeline(music, voice, duck=True)), tmp_path / "ducked.mp4"
    )

    # Listen to the music band only, in the same window of both renders. The
    # window sits inside the plateau of the duck, not across its ramp: attack and
    # release are real and measuring through them would test the slope, not the
    # depth.
    plain_speech = _mean_db(plain, 1.4, 1.9, band=220)
    ducked_speech = _mean_db(ducked, 1.4, 1.9, band=220)
    assert plain_speech - ducked_speech > 6.0, (
        f"only {plain_speech - ducked_speech:.1f} dB of ducking during speech"
    )

    # "The bed comes back" is best proven *between* the two bursts: 300 ms after
    # the first one ends and well before the second starts.
    plain_between = _mean_db(plain, 2.3, 2.8, band=220)
    ducked_between = _mean_db(ducked, 2.3, 2.8, band=220)
    assert abs(plain_between - ducked_between) < 2.0, (
        f"the bed did not recover between words: {plain_between:.1f} vs {ducked_between:.1f} dB"
    )

    # The very tail is measured too, but with a looser bound: it sits against the
    # end of the file, where the limiter and the encoder's own padding live, and
    # that last fraction of a second is not worth a flaky test.
    plain_tail = _mean_db(plain, 4.6, 4.95, band=220)
    ducked_tail = _mean_db(ducked, 4.6, 4.95, band=220)
    assert abs(plain_tail - ducked_tail) < 4.0, (
        f"the bed stayed down at the end: {plain_tail:.1f} vs {ducked_tail:.1f} dB"
    )

    # …and the voice itself is untouched: ducking lowers music, not speech.
    plain_voice = _mean_db(plain, 1.2, 1.9, band=300)
    ducked_voice = _mean_db(ducked, 1.2, 1.9, band=300)
    assert abs(plain_voice - ducked_voice) < 2.0


@requires_ffmpeg
def test_ducking_needs_something_to_duck_under(tmp_path):
    """A ducked bed with no voice in the project must render unchanged, not silent."""
    music = _steady_music(tmp_path)
    timeline = {
        "width": 320, "height": 240, "fps": 15,
        "tracks": [{"id": "a1", "kind": "audio"}],
        "clips": [{"id": "m", "trackId": "a1", "start": 0, "duration": 3, "offset": 0,
                   "src": str(music), "props": {"duck": True}}],
    }
    output = compose.render(compose.Timeline.from_dict(timeline), tmp_path / "alone.mp4")
    assert _mean_db(output, 0.5, 2.5) > -30.0
