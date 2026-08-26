"""The attribution screen's data — every shipped library, named with its licence.

A video editor that bundles FFmpeg, a Python runtime, a speech model and a dozen
libraries owes the people who wrote them a credit screen, and the 1.0 criterion
asks for "every shipped package listed with its licence". This module is the
single source for that list, read from the *installed* package metadata so the
screen can never drift from what is actually in the runtime — the same honesty
the version string got in §4.75's family of fixes.

It also reads the front end's dependency manifest (the curated licence map in
`frontend/src/attribution.ts`) and the bundled tools, but those live on the
renderer side; here we answer for the Python half.
"""
from __future__ import annotations

import importlib.metadata as metadata
import re
from pathlib import Path

#: requirement name → the module it is imported as, when they differ.
MODULE_NAMES = {
    "faster-whisper": "faster_whisper",
    "opencv-python-headless": "cv2",
    "opencv-python": "cv2",
    "yt-dlp": "yt_dlp",
    "pydantic-settings": "pydantic_settings",
    "python-multipart": "multipart",
    "pillow": "PIL",
}


def _requirements() -> list[str]:
    path = Path(__file__).resolve().parents[2] / "requirements.txt"
    names = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        names.append(re.split(r"[=<>\\[]", line, maxsplit=1)[0].strip().lower())
    return names


def _licence_of(meta: metadata.PackageMetadata) -> str:
    """The licence, preferring the explicit fields over a wall of classifiers."""
    for field in ("License-Expression", "License"):
        value = (meta.get(field) or "").strip()
        if value and "UNKNOWN" not in value.upper() and len(value) < 60:
            return value
    for classifier in meta.get_all("Classifier") or []:
        if classifier.startswith("License ::"):
            return classifier.split("::")[-1].strip()
    return "see package"


def backend_attribution() -> list[dict]:
    """Every pinned backend dependency, with version and licence, from metadata."""
    out = []
    for name in _requirements():
        try:
            meta = metadata.metadata(name)
        except Exception:  # noqa: BLE001 — a test-only pin may not be installed
            continue
        out.append({
            "name": name,
            "version": meta.get("Version") or "",
            "licence": _licence_of(meta),
            "role": "backend",
        })
    return sorted(out, key=lambda item: item["name"])


#: The non-Python pieces the installer carries, named so the credit is complete.
BUNDLED = [
    {"name": "FFmpeg", "version": "static build", "licence": "LGPL-2.1+ / GPL (build)",
     "role": "bundled", "why": "decoding, encoding, proxies, analysis"},
    {"name": "Electron", "version": "31", "licence": "MIT", "role": "bundled",
     "why": "the desktop shell"},
    {"name": "Embeddable CPython", "version": "3.11", "licence": "PSF", "role": "bundled",
     "why": "the backend runtime"},
    {"name": "silero-vad", "version": "on demand", "licence": "MIT", "role": "optional",
     "why": "speech map (fetched by the user, not shipped)"},
    {"name": "RapidOCR", "version": "on demand", "licence": "Apache-2.0", "role": "optional",
     "why": "on-screen text (fetched by the user, not shipped)"},
]


def full() -> dict:
    return {"backend": backend_attribution(), "bundled": BUNDLED}
