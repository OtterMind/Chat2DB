"""Tier 1 — transcript-first editing endpoints.

The backend answers "which ranges would this transcript edit remove?"; the editor
applies them as one undoable ripple delete. All inputs are the word timings and
silence ranges the app already measured, so nothing here re-guesses a boundary.
"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core.engine import transcript_edit

router = APIRouter(prefix="/api/transcript", tags=["transcript"])


class CutsRequest(BaseModel):
    words: list[dict] = Field(description="Word timings [{start, end, word/text}]")
    spans: list[list[int]] = Field(description="Inclusive word-index spans to delete")


@router.post("/cuts")
def cuts(payload: CutsRequest) -> dict:
    ranges = transcript_edit.ranges_from_words(payload.words, payload.spans)
    if not ranges and payload.spans:
        raise HTTPException(status_code=404, detail="the selection no longer matches the transcript")
    return {"cuts": ranges, "removed": round(sum(b - a for a, b in ranges), 3)}


class FillersRequest(BaseModel):
    words: list[dict]
    lang: str | None = None


@router.post("/fillers")
def fillers(payload: FillersRequest) -> dict:
    ranges = transcript_edit.filler_ranges(payload.words, payload.lang)
    return {"cuts": ranges, "count": len(ranges),
            "removed": round(sum(b - a for a, b in ranges), 3)}


class JumpcutRequest(BaseModel):
    words: list[dict] = []
    silences: list[dict] = []
    remove_fillers: bool = True
    remove_silence: bool = True
    minimum_silence: float = Field(default=0.4, ge=0.1, le=5.0)


@router.post("/jumpcut")
def jumpcut(payload: JumpcutRequest) -> dict:
    if not payload.words and not payload.silences:
        raise HTTPException(status_code=400, detail="give me words or silence ranges to cut")
    return transcript_edit.jumpcut(
        payload.words, payload.silences,
        remove_fillers=payload.remove_fillers,
        remove_silence=payload.remove_silence,
        minimum_silence=payload.minimum_silence,
    )
