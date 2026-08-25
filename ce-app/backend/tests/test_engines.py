"""The engine registry: the licence gate as data, and OTIO round-trips."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from core.engine import interchange

client = TestClient(app)


def test_every_accepted_engine_is_listed_with_a_licence():
    from core.engine import engines

    body = engines.status()
    names = {e["id"] for e in body["engines"]}

    assert {"rife", "whisperx", "transnet", "demucs", "mediapipe", "clip",
            "esrgan", "pyannote", "film", "otio"} <= names
    assert all(e["licence"] for e in body["engines"])


def test_rejected_engines_are_named_with_reasons():
    from core.engine import engines

    body = engines.status()
    rejected = {r["name"] for r in body["rejected"]}

    assert "YOLOv8" in rejected and "madmom" in rejected and "Remotion" in rejected
    assert all(r["why"] for r in body["rejected"])


def test_a_rejected_engine_cannot_be_installed():
    assert client.post("/api/engines/install/start", json={"engine": "yolov8"}).status_code == 404


@pytest.mark.skipif(not interchange.available(), reason="OpenTimelineIO not fetched")
def test_otio_round_trip(tmp_path):
    timeline = {
        "clips": [
            {"id": "a", "trackId": "v1", "start": 0, "duration": 2.0, "offset": 1.0,
             "src": "/tmp/a.mp4", "label": "one"},
            {"id": "b", "trackId": "v1", "start": 2, "duration": 3.0, "offset": 5.0,
             "src": "/tmp/b.mp4", "label": "two"},
        ]
    }
    path = tmp_path / "out.otio"
    interchange.export_otio(timeline, str(path))
    assert path.exists()

    back = interchange.import_otio(str(path))
    assert len(back["clips"]) == 2
    assert back["clips"][0]["duration"] == pytest.approx(2.0)
    assert back["clips"][1]["offset"] == pytest.approx(5.0)


def test_otio_without_the_engine_is_a_clean_409():
    if interchange.available():
        pytest.skip("engine present")
    timeline = {"clips": []}
    assert client.post("/api/engines/otio/export",
                       json={"timeline": timeline, "path": "/tmp/x.otio"}).status_code == 409


def test_rife_degrades_when_not_fetched():
    from core.engine import rife

    if rife.available():
        pytest.skip("RIFE fetched on this machine")
    with pytest.raises(rife.RifeNotInstalled):
        rife.interpolate(b"", b"", 64, 64)


def test_ai_transitions_one_per_contiguous_junction_sized_to_music():
    from core.engine import style

    timeline = {"clips": [
        {"id": "a", "trackId": "v1", "start": 0, "duration": 2.0},
        {"id": "b", "trackId": "v1", "start": 2.0, "duration": 2.0},
        {"id": "c", "trackId": "v1", "start": 5.0, "duration": 2.0},  # gap before c: no junction
    ]}
    out = style.suggest_transitions(timeline, bpm=120.0)

    assert len(out) == 1, "only the contiguous a→b junction gets a transition"
    assert out[0]["fromClipId"] == "a" and out[0]["toClipId"] == "b"
    assert 0.2 <= out[0]["duration"] <= 0.8, "duration must be a half-beat, clamped"
