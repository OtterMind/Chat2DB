"""Reading and stopping long work.

The tasks themselves are started by the feature that owns them (`/api/style/...`);
this router is only the shared way to ask *how far along is it* and to say *stop*.
Progress also arrives unasked over the `/ws` channel — this is the answer for a
screen whose socket dropped, which is exactly when a progress bar must not lie.
"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException

from core.tasks import tasks

router = APIRouter(prefix="/api/tasks", tags=["tasks"])


@router.get("")
def list_tasks() -> dict:
    return {"tasks": tasks.list()}


@router.get("/{task_id}")
def read_task(task_id: str) -> dict:
    task = tasks.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=f"No task called {task_id}")
    # The result travels here, not over the socket: an editor document is far
    # larger than anything a status channel should carry.
    return task.as_dict(with_result=task.status == "done")


@router.post("/{task_id}/cancel")
def cancel_task(task_id: str) -> dict:
    task = tasks.cancel(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=f"No task called {task_id}")
    return task.as_dict()
