"""On-screen text: what is installed, fetch it, and read a file.

The scan endpoint is the honest form of the three questions it unlocks —
"what typography does the reference use", "does it carry hand-made titles", and
the *no on-screen text* restriction. A frame with no type returns an empty list,
which is an answer, not an error.
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core.engine import cancellation, ocr
from core.tasks import tasks

router = APIRouter(prefix="/api/ocr", tags=["ocr"])


@router.get("/status")
def status() -> dict:
    return ocr.status()


@router.post("/install/start")
def install_start() -> dict:
    """Fetch RapidOCR and its small deps once, into the user's runtime dir."""

    def work(reporter) -> dict:
        cancellation.bind(reporter.cancel_event)
        try:
            return ocr.install(
                on_progress=lambda stage, fraction, label="": reporter.stage(
                    stage, fraction, label
                )
            )
        finally:
            cancellation.bind(None)

    return tasks.start("ocr:install", work).as_dict()


class OcrRequest(BaseModel):
    path: str = Field(description="An image, or a media file to sample")
    every: float = Field(default=3.0, description="Seconds between sampled frames")


@router.post("/read")
async def read(payload: OcrRequest) -> dict:
    """The words in one image."""
    loop = asyncio.get_running_loop()
    try:
        return {"lines": await loop.run_in_executor(None, ocr.read_image, payload.path)}
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
    except RuntimeError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.post("/coverage")
async def coverage(payload: OcrRequest) -> dict:
    """How much of a video carries on-screen text, 0..1."""
    loop = asyncio.get_running_loop()
    try:
        value = await loop.run_in_executor(None, ocr.text_coverage, payload.path, payload.every)
        return {"coverage": value, "engine": "rapidocr", "every": payload.every}
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
    except RuntimeError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
