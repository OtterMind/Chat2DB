"""Transcription and caption generation."""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core.engine import transcribe as engine

router = APIRouter(prefix="/api/captions", tags=["captions"])


class TranscribeRequest(BaseModel):
    path: str
    language: str | None = Field(default=None, description="ISO code; auto-detected when omitted")
    max_chars: int = Field(default=42, description="Soft limit per caption line")


@router.post("/transcribe")
def transcribe(payload: TranscribeRequest) -> dict:
    media = Path(payload.path)
    if not media.exists():
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}")
    try:
        return engine.transcribe_to_cues(
            str(media), language=payload.language, max_chars=payload.max_chars
        )
    except engine.TranscriberUnavailable as exc:
        # A missing model must say so plainly instead of looking like a crash.
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/status")
def status() -> dict:
    return engine.availability()
