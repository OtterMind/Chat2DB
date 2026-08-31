"""Motion package switcher endpoints (runtime, differential-update friendly)."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from core import motion_packages

router = APIRouter(prefix="/api/motion", tags=["motion"])


@router.get("/list")
def list_motion() -> dict:
    return {"packages": motion_packages.list_packages(),
            "active": motion_packages.get_active()}


@router.get("/params")
def params() -> dict:
    return motion_packages.get_params()


class SetRequest(BaseModel):
    id: str


@router.post("/set")
def set_motion(payload: SetRequest) -> dict:
    try:
        return motion_packages.set_active(payload.id)
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error


class RecommendRequest(BaseModel):
    #: The signals the brain already measured (bpm/action/emotion/speech_ratio).
    #: All optional: with nothing measured the answer says so instead of guessing.
    bpm: float | None = None
    action: float | None = None
    emotion: float | None = None
    speech_ratio: float | None = None


@router.post("/recommend")
def recommend(payload: RecommendRequest) -> dict:
    """The package this material argues for, with the reason, plus whether it
    is already the active one — so the UI can offer one click instead of a
    lecture."""
    signals = payload.model_dump(exclude_none=True)
    rec = motion_packages.recommend(signals)
    rec["active"] = motion_packages.get_active()
    rec["applied"] = rec["id"] == rec["active"]
    return rec
