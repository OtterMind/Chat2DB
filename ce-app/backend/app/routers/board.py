"""Tier 1/2 — the board: emotional arc, hook score, ranked clips, markers, and the
cut inspector ("why this cut?").

Every endpoint returns the numbers *and* the terms that produced them, so the UI
can always show its working — the advisors' explainability demand, honoured at the
API level rather than bolted on.
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.routers.paths import safe_user_path
from core.brain import objective
from core.engine import arc_hook, clips_board

router = APIRouter(prefix="/api/board", tags=["board"])


def _checked(path: str) -> str:
    try:
        return str(safe_user_path(path))
    except (ValueError, FileNotFoundError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


class ArcRequest(BaseModel):
    path: str
    fps: float = Field(default=2.0, ge=0.5, le=8.0)


@router.post("/arc")
async def arc(payload: ArcRequest) -> dict:
    path = _checked(payload.path)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, arc_hook.emotional_arc, path, payload.fps)


class HookRequest(BaseModel):
    path: str
    start: float = 0.0
    end: float = Field(default=3.0, ge=0.5, le=30.0)


@router.post("/hook")
async def hook(payload: HookRequest) -> dict:
    path = _checked(payload.path)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, arc_hook.hook_score, path, payload.start, payload.end)


class ProposeRequest(BaseModel):
    path: str
    n: int = Field(default=8, ge=1, le=24)
    persona: str = Field(default="sport", pattern="^(sport|vlog|gym)$")
    intensity: float = Field(default=0.5, ge=0.0, le=1.0)


@router.post("/propose")
async def propose(payload: ProposeRequest) -> dict:
    path = _checked(payload.path)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        None, clips_board.propose, path, payload.n, payload.persona, payload.intensity)


class HookLabRequest(BaseModel):
    path: str
    intensity: float = Field(default=0.5, ge=0.0, le=1.0)
    window: float = Field(default=3.0, ge=1.0, le=10.0)


@router.post("/hook-lab")
async def hook_lab(payload: HookLabRequest) -> dict:
    """Tier 2: five cold-open variants for the strongest measured moment."""
    path = _checked(payload.path)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        None, clips_board.hook_lab, path, payload.intensity, payload.window)


class MarkersRequest(BaseModel):
    path: str
    fps: float = Field(default=4.0, ge=1.0, le=10.0)


@router.post("/markers")
async def markers(payload: MarkersRequest) -> dict:
    path = _checked(payload.path)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, clips_board.sports_markers, path, payload.fps)


class ExplainRequest(BaseModel):
    start: float
    end: float
    duration: float = 0.0
    beats: list[float] = []
    speech: list[list[float]] = []


@router.post("/explain-cut")
def explain_cut(payload: ExplainRequest) -> dict:
    """The cut inspector: score one pick against the ten objective terms."""
    context = objective.Context(
        duration=payload.duration or payload.end,
        beats=payload.beats,
        speech=[(s[0], s[1]) for s in payload.speech if len(s) >= 2],
        best_highlight=1.0,
    )
    score = objective.score_plan([objective.Pick(payload.start, payload.end)], context)
    terms = sorted(score.terms.items(), key=lambda kv: -kv[1])
    top = [f"{name} {value:.2f}" for name, value in terms[:3]]
    return {
        "total": score.total,
        "terms": score.terms,
        "weights": score.weights,
        "skipped": score.skipped,
        "headline": "carried by " + ", ".join(top) if top else "too little measured to explain",
    }
