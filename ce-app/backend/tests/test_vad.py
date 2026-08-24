"""The speech map — and the honest way to decide whether a model is better.

The whole edit rests on where the speech is, so a second opinion about it is worth
having. But "the model is better" is a claim, and this app keeps claims in one
place: next to a measurement. These tests cover the parts that *can* be settled
here, and skip the parts that need something this sandbox does not have.

What is settled:

* the default is still the energy detector, so nothing about a shipped release
  changes by this existing;
* a loud sound that is not speech is exactly where the two disagree — FFmpeg's
  `silencedetect` measures energy and calls a tone speech, the model does not;
* choosing the model before fetching it is refused, not silently downgraded.

What is **not** settled here: whether the model is better on real speech. That
needs speech, and the judgement belongs to whoever runs `/api/vad/compare` on
material that matters — which is why that endpoint exists at all.
"""
from __future__ import annotations

import subprocess

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from core.engine import analyze as analysis
from core.engine import compose, vad
from tests.conftest import requires_ffmpeg

client = TestClient(app)

requires_model = pytest.mark.skipif(not vad.installed(), reason="speech model not fetched")


@pytest.fixture(scope="module")
def loud_but_not_speech(tmp_path_factory):
    """Twenty seconds: three loud, amplitude-modulated tone bursts, no voice.

    Built to a recipe so the right answer is known — the bursts are at 2–5 s,
    8–12 s and 15–18 s, and none of them is anyone talking.
    """
    target = tmp_path_factory.mktemp("vad") / "tone.wav"
    subprocess.run([
        compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i",
        "aevalsrc='if(between(t\\,2\\,5)+between(t\\,8\\,12)+between(t\\,15\\,18), "
        "0.5*sin(2*PI*200*t)*(0.6+0.4*sin(2*PI*4*t)), 0)':d=20:s=44100",
        "-c:a", "pcm_s16le", str(target),
    ], check=True)
    return target


def test_the_default_speech_map_is_the_one_every_release_used():
    """Adding an engine must not change what a shipped build does."""
    assert settings.speech_engine == "energy"


@requires_ffmpeg
def test_the_default_matches_the_energy_detector_exactly(loud_but_not_speech):
    default = vad.silent_ranges_auto(str(loud_but_not_speech))
    direct = analysis.detect_silence(str(loud_but_not_speech))

    assert [(r.start, r.end) for r in default] == [(r.start, r.end) for r in direct]


def test_the_engine_reports_what_is_on_the_machine():
    body = client.get("/api/vad/status").json()

    assert body["licence"] == "MIT"
    assert body["engine"] in ("energy", "silero")
    assert set(body) >= {"model", "onnxruntime", "ready", "engine", "choices"}


def test_choosing_the_model_before_fetching_it_is_refused():
    if vad.installed():
        pytest.skip("the model is on this machine, so the choice is legitimate")

    refused = client.post("/api/vad/choose", json={"engine": "silero"})

    assert refused.status_code == 409
    assert settings.speech_engine == "energy", "a refused choice must not stick"


def test_an_unknown_engine_is_refused():
    assert client.post("/api/vad/choose", json={"engine": "magic"}).status_code == 422


@requires_ffmpeg
def test_compare_reports_both_methods_and_how_far_apart_they_are(loud_but_not_speech):
    body = client.post("/api/vad/compare", json={"path": str(loud_but_not_speech)}).json()

    assert body["duration"] == pytest.approx(20.0, abs=0.2)
    assert body["silencedetect"] is not None
    assert body["silencedetect"]["seconds"] > 0
    if body["ready"]:
        assert body["silero"] is not None
        assert "disagreementRatio" in body


@requires_model
@requires_ffmpeg
def test_a_loud_sound_that_is_not_speech_is_where_the_two_disagree(loud_but_not_speech):
    """The measured case for having a model at all.

    Not a proof that silero is better on speech — that needs speech. It is proof
    of the specific failure the energy detector has: it cannot tell a loud tone
    from a voice, and three bursts of tone cover half this file.
    """
    duration = 20.0
    energy = analysis.keep_ranges(duration, analysis.detect_silence(str(loud_but_not_speech)))
    model = vad.speech_ranges(str(loud_but_not_speech))

    energy_speech = sum(r.duration for r in energy) / duration
    model_speech = sum(r.duration for r in model) / duration

    assert energy_speech > 0.3, (
        f"the fixture was supposed to look like speech to an energy detector ({energy_speech:.2f})"
    )
    assert model_speech < energy_speech, (
        "the model called the same tone speech — it is not answering a different question"
    )


@requires_model
def test_a_file_without_audio_is_an_answer_not_an_error(tmp_path):
    """A silent video is normal footage, and must not fill a console with errors."""
    silent = tmp_path / "no-audio.wav"
    subprocess.run([
        compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono", "-t", "2",
        "-c:a", "pcm_s16le", str(silent),
    ], check=True)

    # Two seconds of digital silence: nobody is talking, and that is the answer.
    assert vad.speech_ranges(str(silent)) == []
