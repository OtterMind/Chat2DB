"""Long work with a face: staged progress, a poll answer, and a cancel button.

Style analysis and a styled rebuild take tens of seconds on a long reference —
sometimes minutes when captions are involved. Until 0.6.0 both were a single
synchronous request against a client with a 30 second budget, which is the same
shape as the failure the user reported in 0.5.3 (`timeout of 30000ms exceeded`):
the work was fine, the request was not.

So the work moves here. A task runs on a worker thread, reports the stage it is
in over the WebSocket every screen is already listening to, can be polled if that
socket dropped, and can be cancelled — which really does kill the FFmpeg child,
not just stop reporting.

Nothing here is persisted: a task is alive for the session that started it. The
result is fetched over HTTP rather than broadcast, because an editor document is
far too big to push through a status channel.
"""
from __future__ import annotations

import asyncio
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from app.websocket.job_events import ws_manager
from core.engine.cancellation import Cancelled

#: Finished tasks stay readable for a while so a slow screen can still collect them.
KEEP = 50


@dataclass
class Reporter:
    """What a worker is handed: a way to say where it is, and a way to give up."""

    task: "Task"

    def stage(self, name: str, progress: float, label: str = "") -> None:
        self.check()
        self.task._advance(name, progress, label)

    def check(self) -> None:
        if self.task.cancel_event.is_set():
            raise Cancelled()

    @property
    def cancel_event(self) -> threading.Event:
        return self.task.cancel_event


@dataclass
class Task:
    id: str
    kind: str
    status: str = "running"          # running | done | failed | cancelled
    stage: str = "starting"
    progress: float = 0.0
    label: str = "Warming up"
    started: float = field(default_factory=time.time)
    finished: float | None = None
    result: Any = None
    error: str | None = None
    cancel_event: threading.Event = field(default_factory=threading.Event)
    _loop: asyncio.AbstractEventLoop | None = None

    # ----------------------------------------------------------------- state

    def as_dict(self, with_result: bool = False) -> dict:
        data = {
            "id": self.id,
            "kind": self.kind,
            "status": self.status,
            "stage": self.stage,
            "progress": round(self.progress, 4),
            "label": self.label,
            "elapsed": round((self.finished or time.time()) - self.started, 3),
            "error": self.error,
        }
        if with_result:
            data["result"] = self.result
        return data

    def _advance(self, stage: str, progress: float, label: str) -> None:
        self.stage = stage
        self.progress = max(0.0, min(1.0, float(progress)))
        self.label = label
        self._emit("task:progress")

    def _finish(self, status: str, result: Any = None, error: str | None = None) -> None:
        self.status = status
        self.finished = time.time()
        self.result = result
        self.error = error
        if status == "done":
            self.progress = 1.0
        self._emit(f"task:{status}")

    def _emit(self, event: str) -> None:
        """Broadcast from a worker thread onto the event loop that owns the socket."""
        if self._loop is None:
            return
        message = {"type": event, "task_id": self.id, **self.as_dict()}
        broadcast = ws_manager.broadcast(message)
        try:
            asyncio.run_coroutine_threadsafe(broadcast, self._loop)
        except RuntimeError:  # pragma: no cover - the loop shut down mid-task
            broadcast.close()  # or Python warns about a coroutine nobody awaited


class TaskRegistry:
    def __init__(self) -> None:
        self._tasks: dict[str, Task] = {}
        self._order: list[str] = []
        self._lock = threading.Lock()

    def start(self, kind: str, work: Callable[[Reporter], Any]) -> Task:
        """Run `work(reporter)` on a worker thread and return the task at once."""
        task = Task(id=uuid.uuid4().hex[:12], kind=kind)
        try:
            task._loop = asyncio.get_running_loop()
        except RuntimeError:  # called from a plain thread (tests)
            task._loop = None

        with self._lock:
            self._tasks[task.id] = task
            self._order.append(task.id)
            for stale in self._order[:-KEEP]:
                self._tasks.pop(stale, None)
            self._order = self._order[-KEEP:]

        def run() -> None:
            reporter = Reporter(task)
            try:
                result = work(reporter)
            except Cancelled:
                task._finish("cancelled")
            except Exception as error:  # noqa: BLE001 - reported, never swallowed
                task._finish("failed", error=str(error) or error.__class__.__name__)
            else:
                task._finish("done", result=result)

        threading.Thread(target=run, name=f"ce-task-{kind}", daemon=True).start()
        return task

    def get(self, task_id: str) -> Task | None:
        return self._tasks.get(task_id)

    def cancel(self, task_id: str) -> Task | None:
        task = self._tasks.get(task_id)
        if task and task.status == "running":
            task.cancel_event.set()
        return task

    def list(self) -> list[dict]:
        return [self._tasks[i].as_dict() for i in self._order if i in self._tasks]


tasks = TaskRegistry()
