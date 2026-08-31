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
