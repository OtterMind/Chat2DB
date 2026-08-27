"""The provider shelf: what is installed, what it may do, and whether it answers.

Read-only discovery plus two verbs. A provider is never installed by the app —
the user drops a folder in `~/CuttingEdge/providers`, and this router reports
what it found, including the reasons a folder is *not* a provider.
"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from core.providers import channel as providers

router = APIRouter(prefix="/api/providers", tags=["providers"])


@router.get("")
def list_providers() -> dict:
    rows = providers.discover()
    return {
        **providers.catalogue(),
        "providers": [
            {key: value for key, value in row.items() if key not in ("manifest", "dir")}
            for row in rows
        ],
        "count": len(rows),
    }


class EnableRequest(BaseModel):
    id: str
    enabled: bool


@router.post("/enable")
def enable(payload: EnableRequest) -> dict:
    if providers._by_id(payload.id) is None:
        raise HTTPException(status_code=404, detail=f"no provider `{payload.id}`")
    return providers.set_enabled(payload.id, payload.enabled)


class TestRequest(BaseModel):
    id: str


@router.post("/test")
def test_provider(payload: TestRequest) -> dict:
    """Start it, ask it who it is, and show exactly what came back."""
    return providers.selftest(payload.id)
