"""Sound effects from Freesound — searched, licensed, and downloaded on demand.

The sound pack is the last content piece of 0.8.1. Freesound is the source
because every result carries a licence (we only take Creative-Commons-0 /
Attribution that a video may legally carry), and its previews let the user hear
before a byte is downloaded.

It is an online, key-required engine, so the honest shape is the same as the
other opt-ins: without a key `status()` says "not configured" and the editor
keeps working; nothing about it is in the installer. What cannot be verified in
a sandbox without a key — the real search and download — is left to the user's
own account, exactly as the GPU benchmark left its verdict to the user's card
(§4.57).
"""
from __future__ import annotations

import json
from pathlib import Path

from app.config import settings

API = "https://freesound.org/apiv2"

#: Only licences a video may legally carry.
ALLOWED = ("Creative Commons 0", "Attribution", "Attribution NonCommercial")


def sounds_dir() -> Path:
    from app.config import settings as s

    path = Path(s.cuttingedge_home) / "sounds"
    path.mkdir(parents=True, exist_ok=True)
    return path


def configured() -> bool:
    return bool(settings.freesound_api_key)


def status() -> dict:
    return {"configured": configured(), "source": "freesound", "allowedLicences": list(ALLOWED)}


def search(query: str, limit: int = 8) -> list[dict]:
    """CC sound effects matching the query; [] without a key or on any failure.

    An empty list on failure is deliberate: a sound picker that shows broken
    rows is worse than one that says "configure me".
    """
    if not configured():
        return []
    try:
        import requests  # noqa: PLC0415

        response = requests.get(
            f"{API}/search/text/",
            params={"query": query, "filter": "type:(wav aiff)", "fields":
                    "id,name,previews,license,duration,type", "page_size": limit},
            headers={"Authorization": f"Token {settings.freesound_api_key}"},
            timeout=15,
        )
        if not response.ok:
            return []
        results = []
        for item in response.json().get("results", []):
            licence = item.get("license", "")
            if not any(licence.startswith(a) for a in ALLOWED):
                continue
            previews = item.get("previews", {})
            results.append({
                "id": item.get("id"),
                "name": item.get("name", ""),
                "licence": licence,
                "duration": round(float(item.get("duration", 0.0)), 2),
                "preview": (previews.get("preview-hq-mp3") or previews.get("preview-lq-mp3")),
            })
        return results
    except Exception:  # noqa: BLE001 — network trouble is an empty shelf, not a crash
        return []


def download(preview_url: str, name: str) -> Path | None:
    """Save a preview MP3 beside the project for the timeline to use."""
    if not configured() or not preview_url:
        return None
    try:
        import requests  # noqa: PLC0415

        response = requests.get(preview_url, timeout=60)
        if not response.ok:
            return None
        target = sounds_dir() / f"{name}.mp3"
        target.write_bytes(response.content)
        return target
    except Exception:  # noqa: BLE001
        return None
