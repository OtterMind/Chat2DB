"""Projects are plain JSON documents; opening one must never lose work."""
from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.routers import projects

client = TestClient(app)

TIMELINE = {
    "tracks": [{"id": "v1", "kind": "video", "name": "Video 1"}],
    "clips": [{"id": "c1", "trackId": "v1", "start": 0, "duration": 3, "src": "/tmp/does-not-exist.mp4"}],
    "transitions": [],
}


@pytest.fixture(autouse=True)
def temporary_home(tmp_path, monkeypatch):
    monkeypatch.setattr(projects.settings, "cuttingedge_home", str(tmp_path))
    yield


def test_save_then_load_round_trips():
    saved = client.post("/api/projects", json={"name": "My Edit", "timeline": TIMELINE}).json()
    assert saved["clips"] == 1

    loaded = client.get("/api/projects/My Edit").json()
    assert loaded["timeline"]["clips"][0]["id"] == "c1"
    assert loaded["format"] == projects.FORMAT_VERSION


def test_missing_media_is_reported_not_fatal():
    client.post("/api/projects", json={"name": "Moved", "timeline": TIMELINE})
    loaded = client.get("/api/projects/Moved").json()
    assert loaded["missingMedia"] == ["/tmp/does-not-exist.mp4"]


def test_listing_orders_by_recency_and_hides_the_autosave():
    client.post("/api/projects", json={"name": "First", "timeline": TIMELINE})
    client.post("/api/projects", json={"name": "Second", "timeline": TIMELINE})
    client.post("/api/projects/autosave", json={"name": "scratch", "timeline": TIMELINE})

    listing = client.get("/api/projects").json()
    names = [p["name"] for p in listing["projects"]]
    assert names[0] == "Second" and "scratch" not in names
    assert listing["hasAutosave"] is True


def test_autosave_can_be_recovered():
    client.post("/api/projects/autosave", json={"name": "crashed", "timeline": TIMELINE})
    recovered = client.get("/api/projects/autosave").json()
    assert recovered["timeline"]["clips"][0]["id"] == "c1"


def test_names_are_sanitised_but_persian_survives():
    assert projects.safe_name("../../etc/passwd") == "etcpasswd"
    assert projects.safe_name("پروژه من") == "پروژه من"


def test_a_damaged_file_reports_clearly():
    projects.project_path("Broken").write_text("{not json", encoding="utf-8")
    response = client.get("/api/projects/Broken")
    assert response.status_code == 422
    assert "damaged" in response.json()["detail"]


def test_delete_is_idempotent():
    client.post("/api/projects", json={"name": "Temp", "timeline": TIMELINE})
    assert client.delete("/api/projects/Temp").json()["deleted"] == "Temp"
    assert client.delete("/api/projects/Temp").status_code == 200
