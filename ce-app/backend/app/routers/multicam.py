"""Multi-cam: measure the offsets, then return a switch plan to accept or edit.

Two endpoints and no hidden automation — the plan is data, and the editor is the
one that puts it on the timeline (or does not).
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.routers.paths import safe_user_path
from core.engine import multicam

router = APIRouter(prefix="/api/multicam", tags=["multicam"])


def _checked(paths: list[str]) -> list[str]:
    if len(paths) < 2:
        raise HTTPException(status_code=400, detail="multi-cam needs at least two angles")
    if len(paths) > 8:
        raise HTTPException(status_code=400, detail="eight angles is the limit")
    for path in paths:
        try:
            safe_user_path(path)
        except (ValueError, FileNotFoundError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
    return paths


class AlignRequest(BaseModel):
    paths: list[str] = Field(description="One path per camera angle; the first is the reference")


@router.post("/align")
async def align(payload: AlignRequest) -> dict:
    paths = _checked(payload.paths)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, multicam.align, paths)


class PlanRequest(BaseModel):
    paths: list[str]
    offsets: list[float] | None = None
    mode: str = Field(default="balanced", pattern="^(balanced|speech|crowd)$")
    dwell: float = Field(default=1.2, ge=0.4, le=10.0)
    beats: list[float] | None = None


@router.post("/plan")
async def plan(payload: PlanRequest) -> dict:
    paths = _checked(payload.paths)
    loop = asyncio.get_running_loop()

    def run() -> dict:
        return multicam.switch_plan(paths, payload.offsets, mode=payload.mode,
                                    dwell=payload.dwell, beats=payload.beats)

    return await loop.run_in_executor(None, run)
