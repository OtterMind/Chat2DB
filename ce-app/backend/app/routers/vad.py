"""The speech-map engine: what is installed, fetch it, and measure it.

The measure endpoint is the point of this router. Adding a model and claiming it
is better are two different things, and this app has been burned by the second
without the first often enough that the button exists (§4.57: a claim about a GPU
that was not measured on the machine it runs on is a brochure).
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.config import settings
from core.engine import cancellation, vad
from core.tasks import tasks

router = APIRouter(prefix="/api/vad", tags=["vad"])


@router.get("/status")
def status() -> dict:
    """Is the model here, is there something to run it, and which map is in use."""
    return {
        **vad.status(),
        "engine": settings.speech_engine,
        "choices": ["energy", "silero"],
    }


class ChooseRequest(BaseModel):
    engine: str = Field(description="energy | silero")


@router.post("/choose")
async def choose(payload: ChooseRequest) -> dict:
    """Pick the speech map the edit is built on.

    Choosing `silero` without the model on disk is refused rather than silently
    downgraded: the user should see that a 2.2 MB download is missing, not wonder
    later why the numbers did not change.
    """
    engine = payload.engine.strip().lower()
    if engine not in ("energy", "silero"):
        raise HTTPException(status_code=422, detail=f"Not a speech engine: {payload.engine}")
    if engine == "silero" and not vad.installed():
        raise HTTPException(
            status_code=409,
            detail="The speech model is not on this machine yet — fetch it first (2.2 MB).",
        )

    settings.speech_engine = engine
    try:
        import json

        from app.config import CONFIG_PATH

        existing = {}
        if CONFIG_PATH.exists():
            existing = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        existing["speech_engine"] = engine
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(existing, indent=2), encoding="utf-8")
    except Exception:  # noqa: BLE001 — the choice still applies to this session
        pass
    return {"engine": engine}


@router.post("/install/start")
def install_start() -> dict:
    """Fetch the model as a task: 2.2 MB, with a real progress bar."""

    def work(reporter) -> dict:
        cancellation.bind(reporter.cancel_event)
        try:
            path = vad.fetch(
                progress=lambda stage, fraction, label="": reporter.stage(stage, fraction, label)
            )
            return {"path": str(path), **vad.status()}
        finally:
            cancellation.bind(None)

    return tasks.start("vad:install", work).as_dict()


class CompareRequest(BaseModel):
    path: str = Field(description="A media file — ideally one with real speech in it")


@router.post("/compare")
async def compare(payload: CompareRequest) -> dict:
    """Both speech maps on the same file, with the numbers side by side."""
    loop = asyncio.get_running_loop()
    try:
        return await loop.run_in_executor(None, vad.compare, payload.path)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
