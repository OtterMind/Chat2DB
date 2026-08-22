"""Analysis endpoints — silence, scenes, and everything needed for auto-editing."""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

import asyncio

from core.engine import analyze
from core.engine import audio as audio_engine

router = APIRouter(prefix="/api/analyze", tags=["analyze"])


class AnalyzeRequest(BaseModel):
    path: str
    noise_db: float = Field(default=-32.0, description="Silence threshold in dBFS")
    min_silence: float = Field(default=0.35, description="Shortest gap treated as silence, seconds")


def _require_file(path: str) -> str:
    if not Path(path).exists():
        raise HTTPException(status_code=404, detail=f"File not found: {path}")
    return path


@router.post("/silence")
def silence(payload: AnalyzeRequest) -> dict:
    _require_file(payload.path)
    try:
        ranges = analyze.detect_silence(
            payload.path, noise_db=payload.noise_db, min_silence=payload.min_silence
        )
        info = analyze.probe_media(payload.path)
        duration = float(info.get("duration") or 0.0)
        return {
            "duration": duration,
            "silences": [r.as_dict() for r in ranges],
            "speech": [r.as_dict() for r in analyze.keep_ranges(duration, ranges)],
        }
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/scenes")
def scenes(payload: AnalyzeRequest) -> dict:
    _require_file(payload.path)
    try:
        return {"scenes": analyze.detect_scenes(payload.path)}
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("")
def full(payload: AnalyzeRequest) -> dict:
    _require_file(payload.path)
    try:
        return analyze.analyse(payload.path)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=str(exc)) from exc


class BeatRequest(BaseModel):
    path: str
    min_bpm: float = Field(default=60.0, ge=20.0, le=300.0)
    max_bpm: float = Field(default=200.0, ge=40.0, le=400.0)


@router.post("/beats")
async def detect_beats(payload: BeatRequest) -> dict:
    """Tempo and beat times, so cuts can land on the music.

    Runs in a worker thread: decoding and an FFT over a whole song is seconds of
    CPU, and the event loop has a WebSocket to keep answering.
    """
    _require_file(payload.path)
    try:
        result = await asyncio.get_running_loop().run_in_executor(
            None, audio_engine.beats, payload.path, payload.min_bpm, payload.max_bpm
        )
    except ValueError as error:
        raise HTTPException(status_code=422, detail=f"No audio to analyse: {error}") from error
    return {"bpm": result.bpm, "beats": result.beats, "confidence": result.confidence}
