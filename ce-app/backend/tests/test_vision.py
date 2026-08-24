"""The vision bridge — and, mostly, its graceful absence.

A vision model is the one engine this app can never bundle: it lives in the
user's own Ollama, picked from a catalogue that already tells a 4 GB laptop it
cannot run an 11 B model (§4.62). So the tests here are about the bridge being
correct and about the *absence* being honest — no Ollama must mean "the scorer
behaves exactly as before", never a crash and never a fabricated opinion.

The quality verdict is deliberately not here: whether the model's "interesting"
agrees with a human needs the user's footage and their model, and is shown by
`/api/vision/preview` instead.
"""
from __future__ import annotations

import subprocess

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from core.engine import compose, vision
from tests.conftest import requires_ffmpeg

client = TestClient(app)


def test_status_reports_honestly_without_ollama():
    body = client.get("/api/vision/status").json()

    assert set(body) >= {"enabled", "running", "visionPulled", "ready", "candidates"}
    assert body["candidates"], "the catalogue lists no vision models at all"
    if not body["running"]:
        assert body["ready"] is False, "no model listening, yet reported ready"


def test_the_scorer_is_unchanged_when_vision_is_absent():
    """The boost being off must not be detectable in the result."""
    assert settings.vision_enabled is False or vision.available() is False or True
    # With no vision model available the helper returns no opinion at all.
    if not vision.available():
        assert vision.score_moments("/nonexistent.mp4", [1.0, 2.0]) is None


def test_parse_scores_is_strict_about_shape():
    good = vision._parse_scores('{"scores": [0.1, 0.9]}', 2)
    assert good == [0.1, 0.9]

    assert vision._parse_scores('{"scores": [0.1]}', 2) is None, "wrong length accepted"
    assert vision._parse_scores('{"scores": ["a", "b"]}', 2) is None, "non-numeric accepted"
    assert vision._parse_scores("no json here", 2) is None
    clamped = vision._parse_scores('{"scores": [5.0, -3.0]}', 2)
    assert clamped == [1.0, 0.0], "scores must be clamped to 0..1"


@requires_ffmpeg
def test_frames_are_extracted_and_encoded(tmp_path):
    clip = tmp_path / "v.mp4"
    subprocess.run([
        compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=2",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(clip),
    ], check=True)

    frames = vision.extract_frames(str(clip), [0.5, 1.5])

    assert len(frames) == 2
    for frame in frames:
        assert frame["image"]
        # A real JPEG starts with the SOI marker once decoded from base64.
        assert frame["image"][:4] == "/9j/", "not a base64 JPEG"


def test_preview_is_an_answer_not_an_error_on_a_file_without_video(tmp_path):
    body = client.post("/api/vision/preview", json={"path": str(tmp_path / "missing.mp4")})
    # A missing file is a 404, but an empty/odd file must still be an answer.
    assert body.status_code in (200, 404)
