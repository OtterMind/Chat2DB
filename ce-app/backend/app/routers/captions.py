"""Transcription and caption generation."""
from __future__ import annotations

import asyncio
from functools import partial
from pathlib import Path

from fastapi import APIRouter, HTTPException
from app.routers.paths import safe_user_path
from pydantic import BaseModel, Field

from core.engine import transcribe as engine

router = APIRouter(prefix="/api/captions", tags=["captions"])


class TranscribeRequest(BaseModel):
    path: str
    language: str | None = Field(default=None, description="ISO code; auto-detected when omitted")
    max_chars: int = Field(default=42, description="Soft limit per caption line")
    quality: str = Field(default="auto",
                         description="auto | fast(base) | balanced(medium) | best(large-v3)")
    align: bool = Field(default=False, description="whisperX forced alignment when fetched")
    align: bool = Field(default=False,
                        description="Snap word edges to the audio with whisperX when fetched")


@router.post("/transcribe")
async def transcribe(payload: TranscribeRequest) -> dict:
    """Whisper on a worker thread.

    Minutes of work on a CPU-only machine: as a sync `def` it held the event loop
    for the whole transcription, so the health poll, the WebSocket and every task
    progress event stopped with it (STATE.md §4.7).
    """
    try:
        media = safe_user_path(payload.path)
    except (ValueError, FileNotFoundError) as exc:
        raise HTTPException(status_code=400 if isinstance(exc, ValueError) else 404, detail=str(exc)) from exc
    loop = asyncio.get_running_loop()
    try:
        return await loop.run_in_executor(None, partial(
            engine.transcribe_to_cues,
            str(media), language=payload.language, max_chars=payload.max_chars,
            align=payload.align, quality=payload.quality,
        ))
    except engine.TranscriberUnavailable as exc:
        # A missing model must say so plainly instead of looking like a crash.
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except engine.ModelNotDownloaded as exc:
        # The asked rung is not on disk: 409 + the size, so the UI offers its fetch.
        raise HTTPException(status_code=409,
                            detail=f"model {exc} not downloaded — fetch it in Settings") from exc
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/status")
def status() -> dict:
    return engine.availability()


class AssImportRequest(BaseModel):
    path: str


class AssExportRequest(BaseModel):
    path: str
    cues: list[dict]
    width: int = 1080
    height: int = 1920


@router.post("/ass/import")
def ass_import(payload: AssImportRequest) -> dict:
    """A `.ass` edited in Aegisub comes back as cues, word timings from `\\kf`."""
    from core.engine import assfile

    try:
        return assfile.import_cues(payload.path)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error


@router.post("/ass/export")
def ass_export(payload: AssExportRequest) -> dict:
    from core.engine import assfile

    return assfile.export(payload.cues, payload.path, payload.width, payload.height)


@router.get("/align-status")
def align_status() -> dict:
    """Is word-level forced alignment available? Honest, so the button can say."""
    from core.engine import whisperx_align

    return {"available": whisperx_align.available(),
            "aligner": whisperx_align.PERSIAN_ALIGNER,
            "note": "Refines faster-whisper word timings for tighter karaoke; "
                    "captions work without it."}


# ------------------------------------------------------------------ SRT / LLM


class SrtExportRequest(BaseModel):
    path: str
    cues: list[dict]


class SrtImportRequest(BaseModel):
    path: str


@router.post("/srt/export")
def srt_export(payload: SrtExportRequest) -> dict:
    import os
    from pathlib import Path as _Path

    from core.engine import subtitles

    dest = _Path(os.path.expanduser(payload.path))
    if ".." in dest.parts:
        raise HTTPException(status_code=400, detail="path traversal is not allowed")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(subtitles.build_srt(payload.cues), encoding="utf-8")
    return {"path": str(dest), "cues": len(payload.cues)}


@router.post("/srt/import")
def srt_import(payload: SrtImportRequest) -> dict:
    import os
    from pathlib import Path as _Path

    from core.engine import subtitles

    src = _Path(os.path.expanduser(payload.path))
    if ".." in src.parts or not src.exists():
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}")
    return {"cues": subtitles.parse_srt(src.read_text(encoding="utf-8-sig", errors="replace"))}


class RefineRequest(BaseModel):
    cues: list[dict]
    model: str | None = None


@router.post("/refine")
def refine(payload: RefineRequest) -> dict:
    """Local-LLM proof-read with the similarity guard — timings never move."""
    from core.engine import captions_llm

    return captions_llm.refine_cues(payload.cues, model=payload.model)


class TranslateRequest(BaseModel):
    cues: list[dict]
    target: str = "English"
    model: str | None = None


@router.post("/translate")
def translate(payload: TranslateRequest) -> dict:
    from core.engine import captions_llm

    return captions_llm.translate_cues(payload.cues, payload.target, model=payload.model)


class ChaptersRequest(BaseModel):
    cues: list[dict]
    duration: float = 0.0


@router.post("/chapters")
def chapters(payload: ChaptersRequest) -> dict:
    from core.engine import chapters

    return {"chapters": chapters.suggest_chapters(payload.cues, payload.duration)}


class HookTitleRequest(BaseModel):
    cues: list[dict]
    model: str | None = None


@router.post("/hook-title")
def hook_title(payload: HookTitleRequest) -> dict:
    from core.engine import captions_llm

    return captions_llm.hook_title(payload.cues, model=payload.model)
