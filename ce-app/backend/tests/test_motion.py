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


# ---------------------------------------------------------------------------
# The switcher must actually move something. The class of bug this guards is the
# quiet one: CSS variables written by the app that no stylesheet ever reads, so
# the switch flips and nothing changes.
# ---------------------------------------------------------------------------
from pathlib import Path  # noqa: E402

_FRONTEND = Path(__file__).resolve().parents[2] / "frontend"
_CSS = _FRONTEND / "src" / "styles" / "global.css"
_LAYOUT = _FRONTEND / "src" / "components" / "Layout" / "index.tsx"


def _written_vars() -> set[str]:
    import re

    return set(re.findall(r"setProperty\('(--m-[a-z-]+)'", _LAYOUT.read_text(encoding="utf-8")))


def test_every_motion_variable_the_app_writes_is_read_by_the_css():
    css = _CSS.read_text(encoding="utf-8")
    written = _written_vars()
    assert {"--m-speed", "--m-stagger", "--m-ease"} <= written
    for var in written:
        assert f"var({var}" in css, f"{var} is written by Layout but no CSS rule reads it"


def test_css_defaults_are_the_builtin_cinematic_package():
    import re

    css = _CSS.read_text(encoding="utf-8")
    params = motion_packages.BUILTINS[0]["params"]
    assert re.search(r"--m-speed:\s*%g;" % params["duration"], css)
    assert re.search(r"--m-stagger:\s*%gms;" % (params["stagger"] * 1000), css)

    def nums(curve: str) -> tuple:
        return tuple(float(n) for n in re.findall(r"-?\d*\.?\d+", curve.split("(", 1)[1]))

    declared = re.search(r"--m-ease:\s*([^;]+);", css).group(1)
    assert nums(declared) == nums(params["ease"])


def test_the_motion_system_consumes_the_package():
    """The rise/stagger rules must be expressed through the variables."""
    css = _CSS.read_text(encoding="utf-8")
    assert "animation: rise calc(.45s * var(--m-speed)) var(--m-ease)" in css
    assert "calc(var(--m-stagger) * 1)" in css
    assert "sm-cardin calc(.45s * var(--m-speed)) var(--m-ease)" in css


# ---------------------------------------------------------------------------
# A package is data the user drops in, so it is treated like input.
# ---------------------------------------------------------------------------
def test_drop_in_numbers_are_clamped_and_css_cannot_be_injected(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    (tmp_path / "motion").mkdir(parents=True, exist_ok=True)
    (tmp_path / "motion" / "wild.json").write_text(json.dumps({
        "id": "wild", "params": {"particles": 999999, "stagger": -3, "duration": 0,
                                 "ease": "linear; background: red"}}))
    pkg = next(p for p in motion_packages.list_packages() if p["id"] == "wild")
    assert pkg["params"]["particles"] == 64
    assert pkg["params"]["stagger"] == 0.0
    assert pkg["params"]["duration"] == 0.4
    assert ";" not in pkg["params"]["ease"]  # the injected CSS never reaches the DOM


def test_a_broken_drop_in_is_ignored_not_fatal(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    (tmp_path / "motion").mkdir(parents=True, exist_ok=True)
    (tmp_path / "motion" / "bad.json").write_text("{not json")
    (tmp_path / "motion" / "no-params.json").write_text(json.dumps({"id": "x"}))
    ids = {p["id"] for p in motion_packages.list_packages()}
    assert {"x", "bad"} & ids == set()


def test_set_active_persists_to_the_config_file(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    motion_packages.set_active("calm")
    saved = json.loads((tmp_path / "config.json").read_text(encoding="utf-8"))
    assert saved["motion_package"] == "calm"


# ---------------------------------------------------------------------------
# The brain must know the capability, and recommend it from measurements.
# ---------------------------------------------------------------------------
def test_the_brain_owns_the_motion_package_decision():
    from core.brain import editor_brain

    assert "motion_package" in {t["id"] for t in editor_brain.TOOLS}
    assessment = editor_brain.assess({"bpm": 140.0, "shots": [{"duration": 1}] * 6},
                                     {"speech_ratio": 0.1, "action": 0.8, "emotion": 0.05})
    row = next(a for a in assessment if a["tool"] == "motion_package")
    assert row["use"] and row["reasonEn"] and row["reasonFa"]
    # 140 BPM with action 0.8 is a celebration, not just energetic
    assert row["recommend"]["id"] == "celebration"
    calmer = editor_brain.assess({"bpm": 140.0, "shots": [{"duration": 1}] * 6},
                                 {"speech_ratio": 0.1, "action": 0.5, "emotion": 0.0})
    row = next(a for a in calmer if a["tool"] == "motion_package")
    assert row["recommend"]["id"] == "energetic"


def test_recommendation_follows_the_measurement():
    assert motion_packages.recommend({"bpm": 0, "action": 0, "emotion": 0})["id"] == "cinematic"
    assert motion_packages.recommend({"emotion": 0.4})["id"] == "celebration"
    assert motion_packages.recommend({"bpm": 132, "action": 0.7})["id"] == "celebration"
    assert motion_packages.recommend({"action": 0.5})["id"] == "energetic"
    assert motion_packages.recommend({"speech_ratio": 0.8})["id"] == "calm"
    for rec in (motion_packages.recommend({"bpm": 90}), motion_packages.recommend({})):
        assert rec["id"] in {p["id"] for p in motion_packages.list_packages()}
        assert rec["params"]


def test_recommend_endpoint(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    body = client.post("/api/motion/recommend", json={"bpm": 140, "action": 0.9}).json()
    assert body["id"] in ("celebration", "energetic")
    assert "applied" in body and body["reasonFa"]
    empty = client.post("/api/motion/recommend", json={}).json()
    assert empty["id"] == "cinematic" and empty["measured"] is False


def test_the_reason_names_the_signal_that_actually_fired():
    """A recommendation must not claim a reaction that was not measured."""
    by_tempo = motion_packages.recommend({"bpm": 140, "action": 0.9, "emotion": 0.0})
    assert by_tempo["id"] == "celebration"
    assert "reacts" not in by_tempo["reasonEn"] and "140 BPM" in by_tempo["reasonEn"]
    by_crowd = motion_packages.recommend({"bpm": 0, "action": 0.0, "emotion": 0.5})
    assert by_crowd["id"] == "celebration"
    assert "reacts" in by_crowd["reasonEn"]
