"""What the graphics card is doing for you, measured on your own machine."""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel, Field

from core.engine import gpu

router = APIRouter(prefix="/api/gpu", tags=["gpu"])


@router.get("/status")
def status(deep: bool = False) -> dict:
    """Card, encoder, decoder, and what speech recognition will really use.

    `deep=true` also loads a Whisper model to find out whether the CUDA path
    works — that costs seconds, so the screen asks for it on a button press.
    """
    return gpu.capabilities(deep=deep).as_dict()


class BenchmarkRequest(BaseModel):
    seconds: int = Field(default=5, ge=1, le=30)
    width: int = Field(default=1920, ge=320, le=3840)
    height: int = Field(default=1080, ge=240, le=2160)


@router.get("/preference")
def preference() -> dict:
    """Does Windows already prefer the discrete card for our executables?"""
    return gpu.gpu_preference()


@router.post("/preference")
def set_preference() -> dict:
    """One button: ask Windows to run us on the graphics card.

    This writes the same per-application preference as Settings → System →
    Display → Graphics, under `HKEY_CURRENT_USER`, so it needs **no
    administrator rights** — and it covers the executables that page cannot
    reach: the Python backend and FFmpeg, which is the process that actually
    opens the encoder.
    """
    result = gpu.prefer_discrete_card()
    # The probes are cached for the life of the process; after a change they are
    # stale by definition.
    gpu.can_encode.cache_clear()
    gpu.probe_encoders.cache_clear()
    gpu.best_decoder.cache_clear()
    gpu.can_decode.cache_clear()
    return result


@router.post("/benchmark")
def benchmark(payload: BenchmarkRequest) -> dict:
    """Encode the same clip on the processor and on the card, and time both."""
    return gpu.benchmark(payload.seconds, payload.width, payload.height)
