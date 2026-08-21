"""Serving local media to the preview player.

The packaged UI runs from `file://`, where a <video src="file://..."> is
unreliable across Chromium versions and cannot be seeked consistently. Streaming
through the local API instead gives us correct Range handling — which is what
makes scrubbing work at all — and behaves identically in the browser preview.
"""
from __future__ import annotations

import mimetypes
import re
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse, Response, StreamingResponse

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
