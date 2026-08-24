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


@router.post("/benchmark")
def benchmark(payload: BenchmarkRequest) -> dict:
    """Encode the same clip on the processor and on the card, and time both."""
    return gpu.benchmark(payload.seconds, payload.width, payload.height)
