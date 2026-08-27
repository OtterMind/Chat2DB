"""Render endpoints — turn an editor timeline into a real video file."""
from __future__ import annotations

import asyncio
import threading
import uuid
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException
from app.routers.paths import safe_user_path
from pydantic import BaseModel, Field

from core.engine import compose
from app.websocket.job_events import ws_manager

router = APIRouter(prefix="/api/render", tags=["render"])

# Renders live in memory: they are short-lived and always tied to a UI session.
_renders: dict[str, dict[str, Any]] = {}


QUALITY_PRESETS = {
    "high": {"crf": 18, "preset": "slow", "nvenc_cq": 19},
    "balanced": {"crf": 21, "preset": "veryfast", "nvenc_cq": 23},
    "fast": {"crf": 26, "preset": "ultrafast", "nvenc_cq": 28},
}


class RenderRequest(BaseModel):
    name: str = Field(default="timeline")
    timeline: dict
    quality: str = Field(default="balanced", description="high | balanced | fast")
    output: str | None = Field(default=None, description="Absolute destination path")


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
    if payload.output:
        output = Path(payload.output)
        output.parent.mkdir(parents=True, exist_ok=True)
    else:
        output = compose.unique_output(payload.name)
    quality = QUALITY_PRESETS.get(payload.quality, QUALITY_PRESETS["balanced"])
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

            compose.render(timeline, output, on_progress=on_progress, quality=quality)
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


class RecordingSaveRequest(BaseModel):
    name: str
    data: str  # base64 blob from the renderer's MediaRecorder
    ext: str = "webm"


@router.post("/recordings/save")
def recordings_save(payload: RecordingSaveRequest) -> dict:
    """The screen/webcam recorder posts its blob here; it lands in the user's
    recordings folder and comes back as a path the timeline can import."""
    import base64
    import os
    import re
    from pathlib import Path as _Path

    safe = re.sub(r"[^A-Za-z0-9_\-]", "", payload.name) or "recording"
    ext = re.sub(r"[^a-z0-9]", "", payload.ext.lower()) or "webm"
    folder = _Path(os.path.expanduser("~/CuttingEdge/recordings"))
    folder.mkdir(parents=True, exist_ok=True)
    dest = folder / f"{safe}.{ext}"
    dest.write_bytes(base64.b64decode(payload.data))
    try:
        safe_user_path(str(dest), must_exist=False)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"path": str(dest)}
