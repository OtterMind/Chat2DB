"""Audio extraction: lift a track with FFmpeg, split stems with Demucs."""
from __future__ import annotations

import asyncio
from pathlib import Path

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core.engine import audio_extract
from core.tasks import tasks

router = APIRouter(prefix="/api/audio", tags=["audio"])


class ExtractRequest(BaseModel):
    path: str
    dest: str | None = Field(default=None, description="Optional output file")


@router.post("/extract")
async def extract(payload: ExtractRequest) -> dict:
    """Lift the audio track of a video into its own .m4a — FFmpeg, always on."""
    if not Path(payload.path).exists():
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}")
    loop = asyncio.get_running_loop()
    try:
        return await loop.run_in_executor(
            None, audio_extract.extract, payload.path, payload.dest)
    except RuntimeError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


class StemsRequest(BaseModel):
    path: str


@router.post("/stems/start")
def stems_start(payload: StemsRequest) -> dict:
    """Vocals/drums/bass/other via Demucs (MIT) — a task, because torch is slow."""
    if not Path(payload.path).exists():
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}")
    if not audio_extract.available():
        raise HTTPException(
            status_code=409,
            detail="Demucs is not fetched — fetch it in Settings → On-demand engines")

    def work(reporter) -> dict:
        reporter.stage("separate", 0.2, "Demucs is splitting the mix")
        return audio_extract.separate(payload.path)

    return tasks.start("audio:stems", work).as_dict()
