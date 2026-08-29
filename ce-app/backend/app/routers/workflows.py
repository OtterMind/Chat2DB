"""The local automation layer, exposed like the rest of the app."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.routers.paths import safe_user_path
from core import workflows

router = APIRouter(prefix="/api/workflows", tags=["workflows"])


@router.get("/list")
def list_workflows() -> dict:
    return {"presets": workflows.list_presets(), "nodes": workflows.NODES,
            "watch": workflows.watch_status()}


class RunRequest(BaseModel):
    preset: str
    path: str


@router.post("/run")
def run(payload: RunRequest) -> dict:
    from core.tasks import tasks  # noqa: PLC0415

    try:
        media = safe_user_path(payload.path)
    except (ValueError, FileNotFoundError) as exc:
        raise HTTPException(status_code=400 if isinstance(exc, ValueError) else 404,
                            detail=str(exc)) from exc

    def work(reporter) -> dict:
        return workflows.run(payload.preset, str(media), reporter)

    return tasks.start(f"workflow:{payload.preset}", work).as_dict()


class WatchRequest(BaseModel):
    dir: str
    preset: str = Field(default="shorts")


@router.post("/watch/start")
def watch_start(payload: WatchRequest) -> dict:
    return workflows.start_watch(payload.dir, payload.preset)


@router.post("/watch/stop")
def watch_stop() -> dict:
    return workflows.stop_watch()
