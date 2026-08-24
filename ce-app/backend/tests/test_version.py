"""One version number, or none at all.

The backend used to keep its own copy of the release number, so bumping
`frontend/package.json` — the file the updater, the installer and the release are
built from — left `/api/health` and the Diagnostics screen reporting the previous
build. A user telling us "I am on 0.9.6" while running 0.9.7 is a conversation
that goes nowhere, and it is the same class of bug as the Settings card that said
`base` while the engine loaded `small` (STATE.md §4.44).
"""
from __future__ import annotations

import json
from pathlib import Path

from app import __version__
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

PACKAGE_JSON = Path(__file__).resolve().parents[2] / "frontend" / "package.json"


def test_the_backend_and_the_app_agree_on_the_version():
    """The label must not contradict the thing it labels."""
    published = json.loads(PACKAGE_JSON.read_text(encoding="utf-8"))["version"]

    assert __version__ == published, (
        f"the backend says {__version__} but the app ships {published}"
    )


def test_the_health_endpoint_reports_the_release_being_built():
    published = json.loads(PACKAGE_JSON.read_text(encoding="utf-8"))["version"]

    body = client.get("/api/health").json()

    assert body["status"] == "ok"
    assert body["version"] == published


def test_the_environment_can_overrule_it_for_a_packaged_install(monkeypatch):
    """In a packaged app the frontend is inside an asar; CE_VERSION wins there."""
    from app import _read_version

    monkeypatch.setenv("CE_VERSION", "9.9.9")

    assert _read_version() == "9.9.9"
