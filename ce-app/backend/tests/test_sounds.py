"""The sound shelf degrades honestly without a key."""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from core.engine import sounds

client = TestClient(app)


def test_status_reports_configuration():
    body = client.get("/api/sounds/status").json()

    assert body["source"] == "freesound"
    assert body["configured"] is bool(settings.freesound_api_key)
    assert body["allowedLicences"]


def test_search_without_a_key_is_an_empty_shelf_not_an_error():
    if settings.freesound_api_key:
        assert isinstance(sounds.search("whoosh"), list)
        return

    assert sounds.search("whoosh") == []
    body = client.get("/api/sounds/search", params={"query": "whoosh"})
    assert body.status_code == 200
    assert body.json()["results"] == []


def test_download_refuses_without_configuration():
    assert sounds.download("https://example.invalid/x.mp3", "x") is None
