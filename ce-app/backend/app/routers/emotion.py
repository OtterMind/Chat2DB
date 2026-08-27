"""Cut on emotion — status, the numbers in the open, and the on/off switch.

The preview endpoint exists for the same reason the vision preview does (§4.57):
whether "the crowd roared here" agrees with what the user considers the moment
can only be answered on their own footage, so the cue values are shown per
moment instead of being asserted.
"""
from __future__ import annotations

import asyncio
import json

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.config import CONFIG_PATH, settings
from core.engine import emotion

router = APIRouter(prefix="/api/emotion", tags=["emotion"])


def _persist(key: str, value) -> None:
    try:
        existing = json.loads(CONFIG_PATH.read_text(encoding="utf-8")) if CONFIG_PATH.exists() else {}
        existing[key] = value
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(existing, indent=2), encoding="utf-8")
    except Exception:  # noqa: BLE001 — the setting still holds for this session
        pass


@router.get("/status")
def status() -> dict:
    """What can weigh in, and whether the user has asked it to."""
    return {
        "enabled": bool(settings.emotion_enabled),
        "maxWeight": emotion.MAX_WEIGHT,
        "sources": emotion.sources(),
        "faceModel": str(emotion.face_model_path()),
        "faceAvailable": emotion.face_available(),
    }


class PreviewRequest(BaseModel):
    path: str = Field(description="A media file to measure")
    count: int = Field(default=12, ge=1, le=40)


@router.post("/preview")
async def preview(payload: PreviewRequest) -> dict:
    loop = asyncio.get_running_loop()
    try:
        return await loop.run_in_executor(None, emotion.preview, payload.path, payload.count)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
    except emotion.NoAudio as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


class EnableRequest(BaseModel):
    enabled: bool


@router.post("/enable")
def enable(payload: EnableRequest) -> dict:
    settings.emotion_enabled = payload.enabled
    _persist("emotion_enabled", payload.enabled)
    return {"enabled": payload.enabled}


@router.post("/face-model/fetch")
async def face_model_fetch() -> dict:
    """Fetch the MediaPipe landmark model (Apache-2.0) — only when asked."""
    loop = asyncio.get_running_loop()
    try:
        path = await loop.run_in_executor(None, emotion.fetch_face_model)
    except Exception as error:  # noqa: BLE001 — a failed download is a message, not a crash
        raise HTTPException(status_code=502, detail=f"could not download the model: {error}") from error
    return {"path": str(path)}
