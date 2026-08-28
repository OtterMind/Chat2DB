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


class TtsRequest(BaseModel):
    text: str
    lang: str = "fa"


@router.post("/tts")
def tts(payload: TtsRequest) -> dict:
    """Dub / voice-over via an out-of-process TTS provider (e.g. a piper plugin).

    No in-process TTS ships (the good local Persian voices are GPL on PyPI), so the
    dub door is a provider. Without one this is a clear 409, not a dead button.
    """
    if not payload.text.strip():
        raise HTTPException(status_code=400, detail="nothing to say")
    answers = providers.hook("tts.synthesize", {"text": payload.text, "lang": payload.lang})
    if not answers:
        raise HTTPException(
            status_code=409,
            detail="No TTS provider installed. Add one in Settings → Providers "
                   "(a piper-style plugin runs out-of-process).",
        )
    result = answers[0]
    path = result.get("path")
    if not path:
        raise HTTPException(status_code=422, detail="the TTS provider returned no audio path")
    return {"path": path, "provider": result.get("provider")}
