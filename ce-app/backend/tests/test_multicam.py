"""B3 — multi-cam: the offset is a measurement with a known answer.

Two synthetic angles are made here, one of them the same material started two
seconds later. The aligner must find **+2.0 s**, not −2.0 s: the sign is the
difference between a plan that lines the angles up and one that puts every cut
two seconds away from where it belongs. The switch plan is then checked for the
two rules that keep it watchable — the mode actually changes who is on screen,
and no angle is held for less than the dwell.
"""
from __future__ import annotations

import wave
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import app
from core.engine import multicam

client = TestClient(app)
SR = 16_000


def _write(path: Path, samples: np.ndarray) -> Path:
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
    return path


def _voice(seconds=8.0) -> np.ndarray:
    """Speech-like: talk with gaps, so the VAD-based cue has something to find."""
    talk, rest = 0.6, 0.35
    out, cursor = [], 0.0
    while cursor < seconds:
        t = np.arange(int(talk * SR)) / SR
        tone = sum(g * np.sin(2 * np.pi * 180 * k * t)
                   for k, g in zip(range(1, 7), [1, .6, .4, .3, .2, .15])) / 2.5
        out.append(tone * 0.5 * (0.6 + 0.4 * np.sin(2 * np.pi * 4 * t)))
        out.append(np.zeros(int(rest * SR)))
        cursor += talk + rest
    return np.concatenate(out)[: int(seconds * SR)]


def _crowd(seconds=8.0) -> np.ndarray:
    """Mostly quiet, with one loud applause burst at 4–6 s."""
    out = np.zeros(int(seconds * SR))
    t = np.arange(2 * SR) / SR
    noise = np.random.default_rng(11).standard_normal(2 * SR)
    out[4 * SR:6 * SR] = np.diff(noise, prepend=0.0) * (0.55 + 0.45 * np.sin(2 * np.pi * 7 * t)) * 0.9
    return out


@pytest.fixture(scope="module")
def angles(tmp_path_factory) -> dict[str, Path]:
    base = tmp_path_factory.mktemp("angles")
    talk = _voice()
    late = np.concatenate([np.zeros(2 * SR, dtype=np.float32), talk])
    return {
        "talk": _write(base / "angle_talk.wav", talk),
        "late": _write(base / "angle_late.wav", late),
        "crowd": _write(base / "angle_crowd.wav", _crowd()),
    }


def test_align_finds_the_offset_with_the_right_sign(angles):
    result = multicam.align([str(angles["talk"]), str(angles["late"])])

    assert result["ok"] is True
    assert result["method"] == "audio-xcorr"
    assert result["offsets"][0] == 0.0
    assert result["offsets"][1] == pytest.approx(2.0, abs=0.1)  # started 2 s later
    assert result["confidence"][1] > 0.5  # same material: the match must be strong


def test_align_says_so_when_there_is_nothing_to_align_on(angles, tmp_path):
    empty = _write(tmp_path / "empty.wav", np.zeros(SR * 3))

    result = multicam.align([str(angles["talk"]), str(empty)])

    assert result["confidence"][1] == 0.0
    assert any("no usable audio" in note for note in result["notes"])


def test_one_angle_is_refused(angles):
    result = multicam.align([str(angles["talk"])])

    assert result["ok"] is False
    assert result["notes"]


def test_the_mode_decides_who_is_on_screen(angles):
    paths = [str(angles["talk"]), str(angles["crowd"])]

    talking = multicam.switch_plan(paths, [0.0, 0.0], mode="speech", dwell=1.0)
    roaring = multicam.switch_plan(paths, [0.0, 0.0], mode="crowd", dwell=1.0)

    assert talking["share"][0] > talking["share"][1]      # the talking angle stays up
    assert roaring["switches"] >= 1                        # the roar pulls a switch
    assert roaring["segments"][-1]["angle"] == 1           # …onto the crowd angle


def test_no_segment_is_shorter_than_the_dwell(angles):
    plan = multicam.switch_plan([str(angles["talk"]), str(angles["crowd"])], [0.0, 0.0],
                                mode="balanced", dwell=1.5)

    lengths = [s["end"] - s["start"] for s in plan["segments"]]
    assert lengths and min(lengths) >= 1.5 - 1e-6
    assert plan["notes"], "a plan with no explanation is not reviewable"


def test_segments_carry_the_place_in_their_own_file(angles):
    plan = multicam.switch_plan([str(angles["talk"]), str(angles["late"])], [0.0, 2.0],
                                mode="balanced", dwell=1.0)

    for segment in plan["segments"]:
        assert segment["offset"] == pytest.approx(segment["start"] + [0.0, 2.0][segment["angle"]], abs=1e-6)
        assert Path(segment["src"]).exists()


def test_the_router_checks_the_paths(angles):
    assert client.post("/api/multicam/align", json={"paths": [str(angles["talk"])]}).status_code == 400
    assert client.post("/api/multicam/align",
                       json={"paths": [str(angles["talk"]), "relative/path.wav"]}).status_code == 400

    body = client.post("/api/multicam/align",
                       json={"paths": [str(angles["talk"]), str(angles["late"])]}).json()
    assert body["offsets"][1] == pytest.approx(2.0, abs=0.1)


def test_the_router_builds_a_plan(angles):
    body = client.post("/api/multicam/plan", json={
        "paths": [str(angles["talk"]), str(angles["crowd"])],
        "mode": "crowd", "dwell": 1.0,
    }).json()

    assert body["ok"] is True
    assert body["segments"] and body["span"]["end"] > body["span"]["start"]


def test_the_brain_offers_multi_cam_only_with_two_angles():
    from core.brain import editor_brain

    def use(footage):
        return next(x for x in editor_brain.assess({"bpm": 0, "shots": []}, footage, {})
                    if x["tool"] == "multicam")["use"]

    assert use({"angles": 2}) is True
    assert use({"angles": 1}) is False
    assert use({}) is False
    assert "multicam" in {t["id"] for t in editor_brain.TOOLS}
