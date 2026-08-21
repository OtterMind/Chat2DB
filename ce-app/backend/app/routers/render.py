"""Render endpoints — turn an editor timeline into a real video file."""
from __future__ import annotations

import asyncio
import threading
import uuid
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core.engine import compose
from app.websocket.job_events import ws_manager

router = APIRouter(prefix="/api/render", tags=["render"])

# Renders live in memory: they are short-lived and always tied to a UI session.
_renders: dict[str, dict[str, Any]] = {}


class RenderRequest(BaseModel):
    name: str = Field(default="timeline")
    timeline: dict


class ProbeRequest(BaseModel):
    path: str


@router.post("")
async def start_render(payload: RenderRequest) -> dict:
    timeline = compose.Timeline.from_dict(payload.timeline)

    if timeline.duration <= 0:
        raise HTTPException(status_code=400, detail="Timeline is empty")

    missing = list(compose.iter_missing_sources(timeline))
    if missing:
        raise HTTPException(status_code=400, detail=f"Missing media: {missing[0]}")

    playable = [c for c in timeline.clips if c.src]
    if not playable:
        raise HTTPException(
            status_code=400,
            detail="No media attached to the timeline — import a file before exporting",
        )

    render_id = str(uuid.uuid4())
    output = compose.unique_output(payload.name)
    _renders[render_id] = {
        "id": render_id,
        "status": "running",
        "progress": 0.0,
        "output": str(output),
        "error": None,
        "duration": timeline.duration,
    }

    # Must be captured on the event loop itself: a sync endpoint runs in an AnyIO
    # worker thread where get_event_loop() raises.
    loop = asyncio.get_running_loop()

    def publish(event: dict) -> None:
        asyncio.run_coroutine_threadsafe(ws_manager.broadcast(event), loop)

    def worker() -> None:
        state = _renders[render_id]
        try:
            def on_progress(percent: float, stage: str) -> None:
                state["progress"] = percent
                publish(
                    {
                        "type": "render:progress",
                        "render_id": render_id,
                        "stage": stage,
                        "progress": percent,
                    }
                )

            compose.render(timeline, output, on_progress=on_progress)
            state.update(status="done", progress=100.0)
            publish({"type": "render:done", "render_id": render_id, "output": str(output)})
        except Exception as exc:  # noqa: BLE001 — surfaced to the UI verbatim
            state.update(status="failed", error=str(exc))
            publish({"type": "render:failed", "render_id": render_id, "error": str(exc)})

    threading.Thread(target=worker, name=f"render-{render_id[:8]}", daemon=True).start()
    return _renders[render_id]


@router.get("/{render_id}")
def get_render(render_id: str) -> dict:
    state = _renders.get(render_id)
    if not state:
        raise HTTPException(status_code=404, detail="Render not found")
    return state


@router.post("/probe")
def probe(payload: ProbeRequest) -> dict:
    path = Path(payload.path)
    if not path.exists():
        raise HTTPException(status_code=404, detail="File not found")
    try:
        return compose.probe_media(str(path))
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=f"Could not read media: {exc}") from exc
