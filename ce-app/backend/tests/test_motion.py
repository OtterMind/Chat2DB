"""Motion package switcher: builtin presets, drop-in extras, active switching."""
from __future__ import annotations

import json

from fastapi.testclient import TestClient

from app.main import app
from core import motion_packages

client = TestClient(app)


def test_builtin_packages_listed_with_active(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    pkgs = motion_packages.list_packages()
    ids = {p["id"] for p in pkgs}
    assert {"cinematic", "energetic", "calm", "celebration"} <= ids
    assert sum(1 for p in pkgs if p["active"]) == 1


def test_drop_in_extra_package_appears(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    (tmp_path / "motion").mkdir(parents=True, exist_ok=True)
    (tmp_path / "motion" / "neon.json").write_text(json.dumps(
        {"id": "neon", "en": "Neon", "fa": "نئون",
         "params": {"particles": 30, "stagger": 0.04, "duration": 0.7, "ease": "ease"}}))
    ids = {p["id"] for p in motion_packages.list_packages()}
    assert "neon" in ids


def test_set_active_and_reject_unknown(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    assert motion_packages.set_active("energetic")["active"] == "energetic"
    import pytest
    with pytest.raises(ValueError):
        motion_packages.set_active("nope")


def test_motion_endpoints(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    assert client.get("/api/motion/list").json()["active"] in ("cinematic", "energetic")
    ok = client.post("/api/motion/set", json={"id": "calm"})
    assert ok.status_code == 200 and ok.json()["active"] == "calm"
    assert client.post("/api/motion/set", json={"id": "nope"}).status_code == 400
    assert client.get("/api/motion/params").json()["duration"] == 1.4
