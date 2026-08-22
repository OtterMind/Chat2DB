"""Serving local media to the preview player.

The packaged UI runs from `file://`, where a <video src="file://..."> is
unreliable across Chromium versions and cannot be seeked consistently. Streaming
through the local API instead gives us correct Range handling — which is what
makes scrubbing work at all — and behaves identically in the browser preview.
"""
from __future__ import annotations

import asyncio
import hashlib
import mimetypes
import re
import subprocess
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse, Response, StreamingResponse

from app.config import settings
from core.engine import audio as audio_engine
from core.engine import proxy as proxies
from core.engine.compose import ffmpeg_binary

router = APIRouter(prefix="/api/media", tags=["media"])

CHUNK = 1024 * 512
_RANGE = re.compile(r"bytes=(\d*)-(\d*)")


@router.get("/file")
def stream(path: str, request: Request):
    media = Path(path)
    if not media.exists() or not media.is_file():
        raise HTTPException(status_code=404, detail="File not found")

    size = media.stat().st_size
    content_type = mimetypes.guess_type(media.name)[0] or "application/octet-stream"
    range_header = request.headers.get("range")

    if not range_header:
        return FileResponse(media, media_type=content_type, headers={"Accept-Ranges": "bytes"})

    match = _RANGE.match(range_header)
    if not match:
        raise HTTPException(status_code=416, detail="Malformed Range header")

    start = int(match.group(1) or 0)
    end = int(match.group(2)) if match.group(2) else min(start + CHUNK * 8 - 1, size - 1)
    end = min(end, size - 1)
    if start > end or start >= size:
        return Response(status_code=416, headers={"Content-Range": f"bytes */{size}"})

    def iterator():
        with media.open("rb") as handle:
            handle.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                data = handle.read(min(CHUNK, remaining))
                if not data:
                    break
                remaining -= len(data)
                yield data

    return StreamingResponse(
        iterator(),
        status_code=206,
        media_type=content_type,
        headers={
            "Content-Range": f"bytes {start}-{end}/{size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(end - start + 1),
        },
    )


# ------------------------------------------------------------------ thumbnails


def _thumb_dir() -> Path:
    path = settings.data_dir / "thumbs"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _extract(source: Path, at: float, height: int, target: Path) -> None:
    """One frame, scaled, as JPEG. Fast seek before the input keeps this cheap.

    Asking past the end of a file is normal — a clip can be longer than its
    source while media is being replaced — so that falls back to the last frame
    instead of failing. A film strip that repeats its final frame reads as "the
    material ends here"; a row of broken images reads as "this app is broken".
    """
    common = [ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y"]
    tail = ["-frames:v", "1", "-vf", f"scale=-2:{height}", "-q:v", "6", str(target)]
    try:
        subprocess.run(
            [*common, "-ss", f"{max(0.0, at):.3f}", "-i", str(source), *tail],
            check=True, timeout=25,
        )
        if target.exists():
            return
    except subprocess.CalledProcessError:
        pass
    # Last frame of the file.
    subprocess.run(
        [*common, "-sseof", "-0.5", "-i", str(source), *tail],
        check=True, timeout=25,
    )


@router.get("/thumb")
async def thumbnail(path: str, t: float = 0.0, h: int = 96):
    """A single frame of a media file, cached on disk.

    The timeline draws film strips out of these, which is what makes a clip
    recognisable at a glance instead of being a coloured rectangle.
    """
    source = Path(path)
    if not source.exists() or not source.is_file():
        raise HTTPException(status_code=404, detail="File not found")

    height = max(24, min(240, int(h)))
    # Quantise the time so scrubbing reuses the cache instead of re-encoding.
    at = round(max(0.0, float(t)), 1)
    key = hashlib.sha1(
        f"{source.resolve()}|{source.stat().st_mtime_ns}|{at}|{height}".encode()
    ).hexdigest()
    cached = _thumb_dir() / f"{key}.jpg"

    if not cached.exists():
        loop = asyncio.get_running_loop()
        try:
            await loop.run_in_executor(None, _extract, source, at, height, cached)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            raise HTTPException(status_code=422, detail=f"No frame at {at}s") from error
        if not cached.exists():
            raise HTTPException(status_code=422, detail=f"No frame at {at}s")

    return FileResponse(cached, media_type="image/jpeg", headers={"Cache-Control": "max-age=86400"})


# --------------------------------------------------------------------- proxies


@router.post("/proxy")
async def start_proxy(payload: dict):
    """Ask for an editing proxy of a file; returns immediately.

    Building runs in a worker thread, so a 4K import does not block the API. The
    UI polls `GET /api/media/proxy` and swaps the preview source when it is ready.
    """
    path = str(payload.get("path") or "")
    try:
        state = await asyncio.get_running_loop().run_in_executor(
            None, proxies.ensure, path, bool(payload.get("force"))
        )
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail="File not found") from error
    return state.__dict__


@router.get("/proxy")
def proxy_status(path: str):
    return proxies.state_for(path).__dict__


# ---------------------------------------------------------------- waveforms


@router.get("/peaks")
async def waveform(path: str, points: int = 800):
    """A min/max envelope of the audio, for drawing on the timeline."""
    try:
        return await asyncio.get_running_loop().run_in_executor(
            None, audio_engine.peaks, path, points
        )
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail="File not found") from error
    # A silent video answers with an empty envelope, not an error: the clip
    # simply draws no waveform.
