"""A download the user paid for must survive the next update.

The installer replaces the whole application folder. `pip install` into the
bundled Python therefore lasts exactly until the next release — and the CUDA
libraries are 1.3 GB. So on-demand packages go beside the user's projects, in
`~/CuttingEdge/runtime/py`, and the backend puts that on `sys.path` at startup.

The same reasoning covers the other two downloads, which is why neither is
touched by us: Ollama keeps models in its own store and Whisper in the Hugging
Face cache, both in the user's profile.
"""
from __future__ import annotations

import sys
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app
from core import runtime_packages


def test_the_runtime_directory_is_outside_the_installation(tmp_path, monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    target = runtime_packages.runtime_dir()

    assert target == tmp_path / "runtime" / "py"
    assert target.exists()
    # The application lives wherever this file is; the runtime must not be under it.
    app_root = Path(__file__).resolve().parents[2]
    assert app_root not in target.resolve().parents


def test_it_is_importable_after_a_restart(tmp_path, monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    runtime_packages.ensure_on_path()

    assert str(tmp_path / "runtime" / "py") in sys.path
    # Ahead of the bundled packages: a newer CUDA the user fetched should win.
    assert sys.path.index(str(tmp_path / "runtime" / "py")) == 0


def test_calling_it_twice_does_not_stack_up_paths(tmp_path, monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    runtime_packages.ensure_on_path()
    runtime_packages.ensure_on_path()

    assert sys.path.count(str(tmp_path / "runtime" / "py")) == 1


def test_the_long_downloads_are_tasks_with_a_bar():
    """Each one answers immediately with a task id, then reports progress."""
    client = TestClient(app)

    started = client.post("/api/ai/ollama/pull/start", json={"model": "qwen2.5vl:3b"})
    assert started.status_code == 200
    body = started.json()
    assert body["status"] == "running" and body["kind"] == "ollama:pull"

    # And the shared task endpoint can see it — that is what paints the bar.
    state = client.get(f"/api/tasks/{body['id']}").json()
    assert set(state) >= {"progress", "stage", "label", "status"}


def test_a_missing_engine_fails_the_task_with_a_reason_not_a_500():
    client = TestClient(app)
    started = client.post("/api/ai/whisper/download/start", json={"size": "base"}).json()

    import time

    for _ in range(40):
        state = client.get(f"/api/tasks/{started['id']}").json()
        if state["status"] != "running":
            break
        time.sleep(0.1)

    assert state["status"] in {"done", "failed"}
    if state["status"] == "failed":
        assert state["error"], "a failure has to say what went wrong"
