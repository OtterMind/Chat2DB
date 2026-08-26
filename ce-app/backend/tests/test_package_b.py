"""Package B: brand overlays in the render graph, recorder save endpoint."""
from __future__ import annotations

import base64
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app
from core.engine import compose

client = TestClient(app)

TIMELINE = {
    "tracks": [{"id": "v1", "kind": "video"}],
    "clips": [{"id": "c1", "trackId": "v1", "start": 0, "duration": 2}],
    "transitions": [],
    "width": 320,
    "height": 180,
    "fps": 25,
}


def _cmd(extra: dict) -> list[str]:
    timeline = compose.Timeline.from_dict({**TIMELINE, **extra})
    return compose.build_command(timeline, Path("/tmp/pkgb.mp4"))


def test_progress_bar_appends_an_animated_drawbox():
    cmd = " ".join(_cmd({"progressBar": True}))

    assert "drawbox" in cmd
    assert "eval=frame" in cmd  # the bar grows with t, not a static stripe


def test_no_brand_means_no_overlay():
    cmd = " ".join(_cmd({}))

    assert "drawbox" not in cmd
    assert "drawtext" not in cmd


def test_brand_text_uses_a_real_font_or_skips():
    cmd = " ".join(_cmd({"brandText": "Cutting Edge"}))

    if compose._brand_font() is None:
        assert "drawtext" not in cmd
    else:
        assert "drawtext" in cmd and "Cutting Edge" in cmd


def test_recordings_save_round_trip():
    payload = {"name": "unit rec!", "data": base64.b64encode(b"webm-bytes").decode(), "ext": "webm"}
    response = client.post("/api/render/recordings/save", json=payload)

    assert response.status_code == 200
    path = Path(response.json()["path"])
    assert path.exists() and path.read_bytes() == b"webm-bytes"
    assert path.name == "unitrec.webm"  # sanitised
