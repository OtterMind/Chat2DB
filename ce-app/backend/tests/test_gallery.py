"""The gallery: import what you are given only after checking it.

An exported `.cetemplate` is a file someone else made, and a file someone else
made is an interface that gets checked. These tests pin that a sound document
round-trips and an unsound one is refused with the reason named, and that a fresh
gallery is seeded with honest starters rather than left an empty room.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from core.engine import style

client = TestClient(app)


def _good():
    return {
        "name": "roundtrip",
        "source": "",
        "duration": 3.0,
        "aspect": "9:16",
        "width": 1080, "height": 1920,
        "shots": [
            {"start": 0.0, "duration": 1.5, "motion": "static", "energy": 0.5},
            {"start": 1.5, "duration": 1.5, "motion": "push", "energy": 0.5},
        ],
        "bpm": 120.0,
    }


def test_a_sound_template_round_trips():
    assert style.validate_template(_good()) == []

    body = client.post("/api/style/templates/import", json={"template": _good(), "name": "roundtrip"})
    assert body.status_code == 200

    loaded = client.get("/api/style/templates/roundtrip").json()
    assert len(loaded["shots"]) == 2
    client.delete("/api/style/templates/roundtrip")


@pytest.mark.parametrize("mutate,error", [
    (lambda d: d.update(shots=[]), "no shots"),
    (lambda d: d["shots"][0].update(duration=-1), "no length"),
    (lambda d: d["shots"][0].update(motion="spin"), "unknown camera move"),
    (lambda d: d.update(aspect="21:9"), "aspect"),
    (lambda d: d.update(name=""), "no name"),
])
def test_unsound_documents_are_refused_with_the_reason(mutate, error):
    bad = _good()
    mutate(bad)

    assert style.validate_template(bad), "an unsound document passed validation"
    refused = client.post("/api/style/templates/import", json={"template": bad})
    assert refused.status_code == 422
    assert error in refused.json()["detail"]


def test_starters_are_served_and_are_themselves_valid():
    body = client.get("/api/style/starters").json()

    assert body["starters"], "a fresh gallery would be an empty room"
    for starter in body["starters"]:
        assert style.validate_template(starter) == [], f"starter {starter['name']} is unsound"
        assert "starter" in starter["name"].lower(), "a starter must say it is one"


def test_imported_template_can_rebuild_footage(tmp_path):
    """The real proof: an imported template is a usable template."""
    import subprocess

    from core.engine import compose

    clip = tmp_path / "f.mp4"
    subprocess.run([
        compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=6",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(clip),
    ], check=True)

    built = style.build_timeline(_good(), str(clip), "T", brain=False)
    clips = [c for c in built["timeline"]["clips"] if c["trackId"] == "v1"]
    assert len(clips) == 2
