"""Long work must report, survive the client's budget, and stop when told.

The bug this guards against is not hypothetical: `timeout of 30000ms exceeded`
reached the user in 0.5.3, and `POST /api/style/analyze` was still shaped that
way — one request held open for as long as the work took. These tests assert the
three properties that replace it: the request returns at once, the stages arrive,
and cancelling actually kills the child process rather than just hiding it.
"""
from __future__ import annotations

import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.main import app  # noqa: E402
from core.engine import cancellation  # noqa: E402
from core.tasks import Cancelled, tasks  # noqa: E402

client = TestClient(app)


# ----------------------------------------------------------------- the runner


def test_a_task_reports_its_stages_and_result():
    seen: list[tuple[str, float]] = []

    def work(reporter):
        for index, stage in enumerate(("one", "two", "three")):
            reporter.stage(stage, (index + 1) / 3, f"step {index}")
            seen.append((stage, reporter.task.progress))
        return {"answer": 42}

    task = tasks.start("test:stages", work)
    _wait(task.id)

    finished = tasks.get(task.id)
    assert finished.status == "done"
    assert finished.progress == 1.0
    assert finished.result == {"answer": 42}
    assert [s for s, _ in seen] == ["one", "two", "three"]
    assert finished.as_dict()["elapsed"] >= 0.0


def test_a_failing_task_says_why():
    task = tasks.start("test:boom", lambda reporter: (_ for _ in ()).throw(ValueError("no such file")))
    _wait(task.id)
    finished = tasks.get(task.id)
    assert finished.status == "failed"
    assert "no such file" in finished.error


def test_cancel_stops_the_worker_at_the_next_checkpoint():
    started = threading.Event()

    def work(reporter):
        started.set()
        for index in range(200):
            reporter.stage("loop", index / 200, "working")
            time.sleep(0.02)
        return "never"

    task = tasks.start("test:cancel", work)
    assert started.wait(2.0)
    tasks.cancel(task.id)
    _wait(task.id, timeout=3.0)
    assert tasks.get(task.id).status == "cancelled"


def test_cancel_kills_the_child_process():
    """A cancel that only stops the reporting is a lie — the child must die."""
    def work(reporter):
        cancellation.bind(reporter.cancel_event)
        try:
            # Something that would run far longer than the test.
            return cancellation.run([sys.executable, "-c", "import time; time.sleep(60)"],
                                    capture_output=True)
        finally:
            cancellation.bind(None)

    before = _python_sleepers()
    task = tasks.start("test:child", work)
    deadline = time.time() + 5
    while time.time() < deadline and len(_python_sleepers()) <= len(before):
        time.sleep(0.05)
    spawned = set(_python_sleepers()) - set(before)
    assert spawned, "the child never started"

    tasks.cancel(task.id)
    _wait(task.id, timeout=5.0)
    assert tasks.get(task.id).status == "cancelled"

    # The child is gone within a second of the flag, not at the end of its sleep.
    deadline = time.time() + 2
    while time.time() < deadline and set(_python_sleepers()) & spawned:
        time.sleep(0.05)
    assert not (set(_python_sleepers()) & spawned), "the FFmpeg-shaped child outlived the cancel"


def _python_sleepers() -> list[int]:
    # `-ww` is load-bearing, not decoration. Without it procps truncates each line
    # to 80 columns when it has no terminal to ask, and the interpreter's own path
    # is part of those 80: from `/tmp/cevenv` the marker survives, from a venv
    # inside a deep checkout (`ce-app/.venv`, the one dev-setup.sh creates) the
    # line ends at `... -c import time; ti` and the grep finds nothing — so the
    # test reported "the child never started" for a child that was running.
    # Measured both ways: `ps -eo` cut at column 80, `ps -eww -o` printed all 84.
    out = subprocess.run(["ps", "-eww", "-o", "pid,args"], capture_output=True, text=True).stdout
    return [int(line.split()[0]) for line in out.splitlines() if "time.sleep(60)" in line]


# ------------------------------------------------------------------- the API


def test_the_task_api_answers_and_cancels():
    def work(reporter):
        for index in range(100):
            reporter.stage("slow", index / 100, "still going")
            time.sleep(0.03)
        return "done"

    task = tasks.start("test:api", work)
    reply = client.get(f"/api/tasks/{task.id}")
    assert reply.status_code == 200
    assert reply.json()["status"] == "running"
    assert reply.json()["kind"] == "test:api"

    cancelled = client.post(f"/api/tasks/{task.id}/cancel")
    assert cancelled.status_code == 200
    _wait(task.id, timeout=3.0)
    assert client.get(f"/api/tasks/{task.id}").json()["status"] == "cancelled"

    assert client.get("/api/tasks/nope").status_code == 404
    assert client.post("/api/tasks/nope/cancel").status_code == 404


def test_style_analysis_returns_immediately_and_streams_stages(sample_video):
    """The whole point: the HTTP call is instant, the work reports as it goes."""
    began = time.time()
    reply = client.post("/api/style/analyze/start", json={"path": str(sample_video), "save": False})
    assert reply.status_code == 200
    assert time.time() - began < 2.0, "starting an analysis must not wait for it"

    task_id = reply.json()["id"]
    assert reply.json()["status"] == "running"

    stages: list[str] = []
    deadline = time.time() + 180
    while time.time() < deadline:
        state = client.get(f"/api/tasks/{task_id}").json()
        if state["stage"] not in stages:
            stages.append(state["stage"])
        if state["status"] != "running":
            break
        time.sleep(0.05)

    final = client.get(f"/api/tasks/{task_id}").json()
    assert final["status"] == "done", final.get("error")
    assert final["progress"] == 1.0
    # Not "busy": the screen can name what is happening, several times over.
    assert len(stages) >= 5, stages
    assert "done" in stages
    assert final["result"]["shots"], "the template came back empty"


def test_style_analysis_can_be_cancelled(sample_video):
    reply = client.post("/api/style/analyze/start", json={"path": str(sample_video), "save": False})
    task_id = reply.json()["id"]
    time.sleep(0.2)
    client.post(f"/api/tasks/{task_id}/cancel")
    _wait(task_id, timeout=20.0)
    assert client.get(f"/api/tasks/{task_id}").json()["status"] in {"cancelled", "done"}


def test_a_missing_file_fails_the_task_not_the_request():
    reply = client.post("/api/style/analyze/start", json={"path": "/no/such/file.mp4"})
    assert reply.status_code == 200
    task_id = reply.json()["id"]
    _wait(task_id, timeout=10.0)
    state = client.get(f"/api/tasks/{task_id}").json()
    assert state["status"] == "failed"
    assert "file.mp4" in state["error"]


def _wait(task_id: str, timeout: float = 30.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        task = tasks.get(task_id)
        if task and task.status != "running":
            return
        time.sleep(0.02)
    raise AssertionError(f"task {task_id} never finished")


def test_cancelled_is_one_class():
    """Two exception classes with the same name is how a cancel becomes a crash."""
    from core.engine.cancellation import Cancelled as EngineCancelled

    assert Cancelled is EngineCancelled


@pytest.fixture(scope="module")
def sample_video(tmp_path_factory):
    """Eight shots, so there is something to report progress about."""
    from core.engine.compose import ffmpeg_binary

    target = tmp_path_factory.mktemp("tasks") / "reference.mp4"
    parts = []
    folder = target.parent
    for index in range(8):
        piece = folder / f"p{index}.mp4"
        colour = ["red", "green", "blue", "yellow", "magenta", "cyan", "orange", "purple"][index]
        subprocess.run(
            [ffmpeg_binary(), "-y", "-hide_banner", "-loglevel", "error",
             "-f", "lavfi", "-i", f"color=c={colour}:s=320x180:d=1.2:r=25",
             "-f", "lavfi", "-i", "sine=frequency=440:duration=1.2",
             "-shortest", "-pix_fmt", "yuv420p", str(piece)],
            check=True,
        )
        parts.append(piece)
    listing = folder / "list.txt"
    listing.write_text("".join(f"file '{p}'\n" for p in parts), encoding="utf-8")
    subprocess.run(
        [ffmpeg_binary(), "-y", "-hide_banner", "-loglevel", "error",
         "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(target)],
        check=True,
    )
    return target
