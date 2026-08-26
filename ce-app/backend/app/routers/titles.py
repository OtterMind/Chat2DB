"""The title pack — animation the exporter can actually reproduce."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException

from core.engine import titles

router = APIRouter(prefix="/api/titles", tags=["titles"])


@router.get("")
def pack() -> dict:
    """The whole catalogue, validated before it is served.

    Served rather than copied into the renderer because `titles.validate()` runs
    here: a list the backend checks and the frontend duplicates is a list that
    drifts the first time either side changes.
    """
    return titles.catalogue()


@router.get("/{preset_id}")
def one(preset_id: str) -> dict:
    preset = titles.get(preset_id)
    if preset is None:
        raise HTTPException(status_code=404, detail=f"No title preset called {preset_id}")
    return preset.as_dict()
