"""Analysis endpoints — silence, scenes, and everything needed for auto-editing."""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core.engine import analyze

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
