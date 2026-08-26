"""On-screen text — reading what a frame says, on a fixture the repo ships.

The fixture is `docs/screenshots/01-launcher.png`: real rendered UI whose words
are known in advance ("New video", "Recent projects", "Face Tracking"), so a
correct OCR must find them and must *not* find words that are not there. It is
stable, needs no download, and is the same file the docs show.

The engine is on-demand, so every test that needs it skips when it is absent —
the same pattern as the face fixture. The endpoint tests run always, because a
screen that cannot even ask "is OCR installed" is broken for everyone.
"""
from __future__ import annotations

import subprocess

import pytest
from fastapi.testclient import TestClient

from app.main import app
from core.engine import compose, ocr
from tests.conftest import requires_ffmpeg

client = TestClient(app)

SCREENSHOT = str(
    pytest.importorskip if False else __import__("pathlib").Path(__file__).resolve()
    .parents[2] / "docs" / "screenshots" / "01-launcher.png"
)

requires_ocr = pytest.mark.skipif(not ocr.installed(), reason="OCR engine not fetched")


def test_the_engine_reports_its_state():
    body = client.get("/api/ocr/status").json()

    assert body["licence"] == "Apache-2.0"
    assert set(body) >= {"installed", "licence", "modelsBundled"}


def test_reading_without_the_engine_is_refused_not_crashed():
    if ocr.installed():
        pytest.skip("engine is present, so this degradation path is not exercised")

    refused = client.post("/api/ocr/read", json={"path": SCREENSHOT})
    assert refused.status_code == 409


@requires_ocr
def test_a_known_image_is_read_correctly():
    assert ocr.contains(SCREENSHOT, "New video")
    assert ocr.contains(SCREENSHOT, "Recent projects")
    assert ocr.contains(SCREENSHOT, "Face Tracking")
    assert not ocr.contains(SCREENSHOT, "a phrase that is not on the screen")


@requires_ocr
def test_normalise_defeats_the_space_dropping_quirk():
    assert ocr.normalise("Open the editor") == ocr.normalise("Opentheeditor")


@requires_ocr
def test_the_endpoint_returns_lines():
    body = client.post("/api/ocr/read", json={"path": SCREENSHOT}).json()

    joined = " ".join(line["text"] for line in body["lines"]).casefold()
    assert "newvideo" in ocr.normalise(joined) or "new video" in joined or \
           any("new" in l["text"].casefold() for l in body["lines"])


@requires_ocr
@requires_ffmpeg
def test_a_blank_video_has_zero_text_coverage(tmp_path):
    """A plain colour is an answer of 0, not an error — for the 'no on-screen
    text' restriction."""
    blank = tmp_path / "blank.mp4"
    subprocess.run([
        compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=black:s=320x240:rate=25:duration=3",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(blank),
    ], check=True)

    assert ocr.text_coverage(str(blank), every=1.0) == 0.0
