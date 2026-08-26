"""A model that has seen frames — status and an honest preview.

The preview endpoint is the measurement for a question that cannot be settled in
a sandbox: does the model's sense of "interesting" agree with a human's? It runs
on a file the user chooses and shows the per-moment scores in the open, because a
verdict without the user's own footage and model would be a brochure (§4.57).
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.config import settings
from core.engine import vision


def _preview(path: str, count: int) -> dict:
    return vision.preview(path, count=count)

router = APIRouter(prefix="/api/vision", tags=["vision"])


@router.get("/status")
def status() -> dict:
    return vision.status()


class PreviewRequest(BaseModel):
    path: str = Field(description="A media file to sample")
    count: int = Field(default=6, ge=2, le=12)


@router.post("/preview")
async def preview(payload: PreviewRequest) -> dict:
    loop = asyncio.get_running_loop()
    try:
        return await loop.run_in_executor(None, _preview, payload.path, payload.count)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error


class EnableRequest(BaseModel):
    enabled: bool


@router.post("/enable")
def enable(payload: EnableRequest) -> dict:
    """One vote for the model, off by default. Refused when no model listens."""
    if payload.enabled and not vision.available():
        raise HTTPException(
            status_code=409,
            detail="No vision model is pulled in Ollama, so there is nothing to enable.",
        )
    settings.vision_enabled = payload.enabled
    try:
        import json

        from app.config import CONFIG_PATH

        existing = {}
        if CONFIG_PATH.exists():
            existing = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        existing["vision_enabled"] = payload.enabled
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(existing, indent=2), encoding="utf-8")
    except Exception:  # noqa: BLE001
        pass
    return {"enabled": payload.enabled}
