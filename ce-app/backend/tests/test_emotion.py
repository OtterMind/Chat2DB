"""B2 — cut on emotion: the cue maths must separate a roar from a voice.

Everything here is measured on sounds synthesised in the test itself, so the
assertions are about *separation*, not about a threshold somebody liked: applause
must out-crowd a voice, a voice must out-tone applause, and a whoosh must read as
a transient rather than as a sustained bed. A detector that cannot tell those
apart on synthetic audio has no business weighting a real edit.
"""
from __future__ import annotations

import wave
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from core.engine import emotion

client = TestClient(app)
SR = 16_000


def _write(path: Path, samples: np.ndarray) -> Path:
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
    return path


def _applause(seconds=6.0, rate=7.0, amp=0.7) -> np.ndarray:
    """Broadband noise with a clapping rhythm — hands, not a voice."""
    t = np.arange(int(seconds * SR)) / SR
    noise = np.random.default_rng(7).standard_normal(t.size)
    noise = np.diff(noise, prepend=0.0)  # pushes the energy up in frequency
    return noise * (0.55 + 0.45 * np.sin(2 * np.pi * rate * t)) * amp


def _voice(seconds=6.0, f0=180.0, syllables=4.0, amp=0.5) -> np.ndarray:
    """A tonal voice: harmonics in the speech band with a syllable rhythm."""
    t = np.arange(int(seconds * SR)) / SR
    tone = sum(gain * np.sin(2 * np.pi * f0 * k * t)
               for k, gain in zip(range(1, 9), [1, .6, .45, .3, .22, .16, .12, .09]))
    return tone / 3.0 * (0.55 + 0.45 * np.sin(2 * np.pi * syllables * t)) * amp


def _whoosh(seconds=6.0, at=2.0, dur=0.35) -> np.ndarray:
    out = np.zeros(int(seconds * SR))
    n0, n1 = int(at * SR), int((at + dur) * SR)
    t = np.arange(n1 - n0) / SR
    burst = np.random.default_rng(3).standard_normal(n1 - n0)
    out[n0:n1] = np.diff(burst, prepend=0.0) * np.sin(np.pi * t / dur) ** 2 * 5.0
    return out


@pytest.fixture(scope="module")
def sounds(tmp_path_factory) -> dict[str, Path]:
    base = tmp_path_factory.mktemp("cues")
    return {
        "applause": _write(base / "applause.wav", _applause()),
        "voice": _write(base / "voice.wav", _voice()),
        "whoosh": _write(base / "whoosh.wav", _whoosh()),
    }


def _mean(cues: emotion.Cues, key: str) -> float:
    return float(np.mean(getattr(cues, key)))


def test_applause_reads_as_crowd_and_not_as_a_voice(sounds):
    cues = emotion.audio_cues(str(sounds["applause"]))

    assert _mean(cues, "crowd") > 0.4
    assert _mean(cues, "voiced") < 0.2  # hands are not tonal


def test_a_voice_reads_as_tonal_and_not_as_a_crowd(sounds):
    cues = emotion.audio_cues(str(sounds["voice"]))

    assert _mean(cues, "voiced") > 0.6
    assert _mean(cues, "crowd") < 0.05


def test_the_crowd_outranks_a_steady_voice(sounds):
    """The whole point: the celebration must beat the commentary."""
    roar = emotion.audio_cues(str(sounds["applause"]))
    talk = emotion.audio_cues(str(sounds["voice"]))

    assert _mean(roar, "joy") > _mean(talk, "joy")


def test_a_whoosh_is_a_transient_not_a_bed(sounds):
    whoosh = emotion.audio_cues(str(sounds["whoosh"]))
    applause = emotion.audio_cues(str(sounds["applause"]))

    assert max(whoosh.whoosh) > 0.8
    # a sustained clap bed also rises fast every clap — it must not read as one
    assert max(applause.whoosh) < 0.5


def test_silence_is_refused_instead_of_scored(tmp_path):
    silent = _write(tmp_path / "silence.wav", np.zeros(SR * 3))

    with pytest.raises(emotion.NoAudio):
        emotion.audio_cues(str(silent))


def test_the_cue_cache_survives_and_is_versioned(sounds):
    first = emotion.audio_cues(str(sounds["applause"]))
    second = emotion.audio_cues(str(sounds["applause"]))

    assert first.joy == second.joy
    assert emotion.CUE_VERSION >= 1  # a formula change has to invalidate the cache


def test_window_value_only_reads_inside_the_window(sounds):
    cues = emotion.audio_cues(str(sounds["whoosh"]))

    inside = emotion.window_value(cues, 1.8, 2.5, "whoosh")
    outside = emotion.window_value(cues, 4.0, 5.0, "whoosh")
    assert inside > outside


def test_score_moments_answers_per_moment(sounds):
    scores = emotion.score_moments(str(sounds["applause"]), [1.0, 3.0, 5.0])

    assert set(scores) == {1.0, 3.0, 5.0}
    assert all(0.0 <= value <= 1.0 for value in scores.values())


def test_blendshapes_map_to_named_action_units():
    class Category:
        def __init__(self, name, score):
            self.category_name, self.score = name, score

    out = emotion.expression_from_blendshapes([
        Category("mouthSmileLeft", 0.9), Category("mouthSmileRight", 0.7),
        Category("jawOpen", 0.2), Category("browDownLeft", 0.1),
    ])

    assert out["smile"] == pytest.approx(0.8)
    assert out["joy"] > 0.9  # a full smile is joy, whatever the model called it
    assert emotion.expression_from_blendshapes([]) ["smile"] == 0.0


def test_the_status_endpoint_reports_what_can_weigh_in():
    body = client.get("/api/emotion/status").json()

    assert body["maxWeight"] == emotion.MAX_WEIGHT
    audio = next(s for s in body["sources"] if s["id"] == "audio")
    assert audio["active"] is True
    # an absent engine is reported, never faked
    assert next(s for s in body["sources"] if s["id"] == "face")["active"] is False


def test_the_preview_endpoint_shows_the_numbers(sounds):
    body = client.post("/api/emotion/preview", json={"path": str(sounds["applause"]), "count": 5}).json()

    assert body["duration"] == pytest.approx(6.0, abs=0.3)
    assert body["meanJoy"] > 0.3
    assert body["peaks"] and {"t", "joy", "crowd", "voiced", "whoosh", "speech"} <= set(body["peaks"][0])


def test_enable_is_persisted_and_reversible():
    before = settings.emotion_enabled

    assert client.post("/api/emotion/enable", json={"enabled": not before}).json() == {"enabled": not before}
    assert settings.emotion_enabled is not before
    client.post("/api/emotion/enable", json={"enabled": before})
    assert settings.emotion_enabled is before


def test_the_highlight_scorer_takes_the_emotion_vote(sounds, monkeypatch):
    """The signal must reach the ranking — a feature nobody calls is a brochure."""
    from core.engine import style

    monkeypatch.setattr(settings, "emotion_enabled", True)
    seen: dict[str, float] = {}
    real = emotion.score_moments

    def spy(path, times):
        scores = real(path, times)
        seen.update(scores)
        return scores

    monkeypatch.setattr(emotion, "score_moments", spy)
    monkeypatch.setattr(style, "_classify_motion", lambda *a, **k: ("steady", 0.2))
    monkeypatch.setattr(style, "_action_profile", lambda *a, **k: (0.2, 0.2))

    out = style._highlights(str(sounds["applause"]), wanted=3, minimum=1.0)

    assert out, "the scorer returned nothing"
    assert seen, "the emotion signal was never asked"
    assert any("emotion" in candidate["signals"] for candidate in out)


def test_the_brain_offers_the_cut_only_when_the_room_reacts():
    from core.brain import editor_brain

    def use(footage):
        row = next(x for x in editor_brain.assess({"bpm": 0, "shots": []}, footage, {})
                   if x["tool"] == "cut_on_emotion")
        return row["use"], row["reasonFa"]

    assert use({"emotion": 0.4})[0] is True
    assert use({"emotion": 0.02})[0] is False
    assert "واکنش" in use({"emotion": 0.4})[1]


def test_the_tool_is_in_the_toolbelt():
    from core.brain import editor_brain

    assert "cut_on_emotion" in {t["id"] for t in editor_brain.TOOLS}
