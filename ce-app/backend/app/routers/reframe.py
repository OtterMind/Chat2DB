"""Auto-reframe: a camera path that follows the speaker.

The answer is keyframes on the clip, not a rendered file — so the move is
visible on the timeline, editable by hand, and reproduced by the ordinary
exporter. See `core/engine/reframe.py` for why it is built that way.
"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core import tasks
from core.engine import cancellation, reframe

router = APIRouter(prefix="/api/reframe", tags=["reframe"])


class ReframeRequest(BaseModel):
    path: str
    width: int = Field(default=1080, ge=16, le=7680, description="Canvas width")
    height: int = Field(default=1920, ge=16, le=7680, description="Canvas height")


@router.post("/plan")
def plan(payload: ReframeRequest) -> dict:
    """Measure a short clip straight away."""
    from pathlib import Path

    if not Path(payload.path).exists():
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}")
    try:
        return reframe.plan(payload.path, payload.width, payload.height).as_dict()
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
    except Exception as error:  # noqa: BLE001 — surfaced, never swallowed
        raise HTTPException(status_code=422, detail=str(error)) from error


@router.post("/start")
def start(payload: ReframeRequest) -> dict:
    """The same, as a cancellable task — a long recording takes a while to scan."""

    def work(reporter) -> dict:
        cancellation.bind(reporter.cancel_event)
        try:
            reporter.stage("scan", 0.1, "Looking for the speaker")
            result = reframe.plan(payload.path, payload.width, payload.height)
            reporter.stage("done", 1.0, result.reason)
            return result.as_dict()
        finally:
            cancellation.bind(None)

    return tasks.start("reframe", work).as_dict()
