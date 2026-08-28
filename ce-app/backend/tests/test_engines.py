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


def test_probe_says_repo_only_where_no_pip_package_exists():
    from core.engine import engines

    film = next(e for e in engines.ENGINES if e["id"] == "film")
    virastar = next(e for e in engines.ENGINES if e["id"] == "virastar")

    assert engines.probe(film)["fetchable"] is False
    assert engines.probe(virastar)["fetchable"] is False
    assert "repo-only" in engines.probe(film)["why"]


def test_status_carries_fetchability_for_the_download_button():
    body = client.get("/api/engines/status").json()

    for engine in body["engines"]:
        assert "fetchable" in engine and "why" in engine


def test_a_heavy_engine_refuses_without_explicit_opt_in():
    from core.engine import engines as reg

    if reg.status()["engines"] and next(
            e for e in reg.ENGINES if e["id"] == "whisperx")["module"]:
        import importlib.util
        if importlib.util.find_spec("whisperx") is not None:
            pytest.skip("whisperX fetched on this machine")

    response = client.post("/api/engines/install/start", json={"engine": "whisperx"})

    assert response.status_code == 409
    assert "torch" in response.json()["detail"]


def test_python_ass_downloads_end_to_end_through_the_real_endpoint():
    """The download the Settings button starts must actually win: run it for a
    small pure wheel through the real install endpoint and task poller."""
    import time as _time

    from core import runtime_packages

    response = client.post("/api/engines/install/start",
                           json={"engine": "python-ass", "heavy": False})
    assert response.status_code == 200, response.text
    task_id = response.json()["id"]

    deadline = _time.time() + 120
    body = {}
    while _time.time() < deadline:
        body = client.get(f"/api/tasks/{task_id}").json()
        if body["status"] != "running":
            break
        _time.sleep(1.0)

    assert body["status"] == "done", body.get("error")
    assert runtime_packages.is_installed("ass")


def test_torch_heavy_deps_include_transitive_runtime_deps():
    """The packaged pip-free installer extracts exactly this list, so it must carry
    torch's own deps — omitting them is what made 'Download + torch' unimportable."""
    from core.engine import engines

    for key in ("torch", "torch+HF-token"):
        deps = engines.HEAVY_DEPS[key]
        for required in ("torch", "filelock", "sympy", "jinja2", "typing-extensions"):
            assert required in deps, f"{key} is missing {required}"


def test_bulk_install_plan_includes_torch_and_skips_gated():
    from core.engine import engines

    plan = engines.bulk_install_plan()
    assert "torch" in plan["deps"]
    assert {"transnet", "demucs"} <= set(plan["ids"])
    assert "pyannote" not in plan["ids"]
    assert "film" not in plan["ids"]


def test_install_all_plan_endpoint():
    body = client.get("/api/engines/install-all/plan").json()
    assert body["count"] >= 2 and "torch" in body["deps"]


def test_install_skips_already_importable_packages():
    """A re-run must be a fast no-op, not a Permission-denied fight (numpy is
    importable in the test env, so nothing should be fetched)."""
    from core import runtime_packages

    out = runtime_packages.install(["numpy"])
    assert out["packages"] == []
    assert "nothing to do" in out["log"]


def test_bulk_plan_excludes_unbuildable_and_gated():
    from core.engine import engines

    plan = engines.bulk_install_plan()
    assert "rife" not in plan["ids"]       # source-only C++: packaged can't build
    assert "film" not in plan["ids"]       # tensorflow
    assert "pyannote" not in plan["ids"]   # HF token
    assert {"transnet", "demucs"} <= set(plan["ids"])
    assert plan["groups"], "each engine must be its own resilient stage"
    assert all(g["deps"] for g in plan["groups"])


def test_bulk_torch_only_for_engines_that_need_it():
    from core.engine import engines

    plan = engines.bulk_install_plan()
    by_id = {g["id"]: g for g in plan["groups"]}
    assert by_id["transnet"]["needs_torch"] is True and "torch" in by_id["transnet"]["deps"]
    assert by_id["mediapipe"]["needs_torch"] is False and "torch" not in by_id["mediapipe"]["deps"]
    assert by_id["hazm"]["needs_torch"] is False


def test_importable_false_for_broken_half_install(tmp_path, monkeypatch):
    """A download that died halfway must NOT be treated as present (the 0.9.45
    find_spec regression that pinned users to broken torch)."""
    import importlib.util

    from app.config import settings
    from core import runtime_packages

    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    runtime_packages.ensure_on_path()
    broken = runtime_packages.runtime_dir() / "brokenmod"
    broken.mkdir(parents=True)
    (broken / "__init__.py").write_text("raise RuntimeError('half extracted')")

    assert importlib.util.find_spec("brokenmod") is not None  # find_spec lies…
    assert runtime_packages._importable("brokenmod") is False  # …the import tells truth
    assert runtime_packages._importable("json") is True
    assert runtime_packages._importable("definitely-missing-xyz") is False
