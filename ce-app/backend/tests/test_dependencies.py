"""Nothing ships that nothing imports.

The installer is ~480 MB and half of it was never used. `mediapipe` was pinned,
shipped to every user, and imported exactly nowhere — and it dragged in jaxlib
(61.2 MB), opencv-contrib-python (46.2 MB), scipy (36.6 MB) and matplotlib
(9.3 MB) behind it. The four cloud AI SDKs were dead too: every provider is
called with plain `requests`.

Measured with `uv pip compile --python-platform windows`:

    before   378.3 MB across 108 packages
    after    137.9 MB across  50 packages

This test is the ratchet. A new dependency is fine — it just has to be imported
by the code, or named here with the reason it cannot be.
"""
from __future__ import annotations

import re
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]

#: Packages that are genuinely used without ever appearing in an `import`.
INDIRECT = {
    "uvicorn": "the ASGI server, started by run_backend.py as a module",
    "python-multipart": "FastAPI needs it to parse uploads",
    "websockets": "uvicorn's WebSocket implementation for /ws",
    "pydantic-settings": "imported as pydantic_settings by app.config",
    "pytest": "test tooling, stripped from the packaged runtime",
    "pytest-asyncio": "test tooling, stripped from the packaged runtime",
}

#: requirement name → the module it is imported as, when they differ.
MODULE_NAMES = {
    "faster-whisper": "faster_whisper",
    "opencv-python-headless": "cv2",
    "opencv-python": "cv2",
    "yt-dlp": "yt_dlp",
    "pydantic-settings": "pydantic_settings",
    "python-multipart": "multipart",
    "pillow": "PIL",
    "google-api-python-client": "googleapiclient",
    "google-auth-oauthlib": "google_auth_oauthlib",
    "google-generativeai": "google.generativeai",
    "pexels-api": "pexels_api",
    "edge-tts": "edge_tts",
}


def _requirements() -> list[str]:
    names = []
    for line in (BACKEND / "requirements.txt").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name = re.split(r"[=<>\[]", line, maxsplit=1)[0].strip().lower()
        names.append(name)
    return names


def _sources() -> str:
    text = []
    for folder in ("app", "core", "uploaders"):
        for file in (BACKEND / folder).rglob("*.py"):
            text.append(file.read_text(encoding="utf-8"))
    text.append((BACKEND / "run_backend.py").read_text(encoding="utf-8"))
    return "\n".join(text)


def test_every_shipped_package_is_actually_imported():
    sources = _sources()
    unused = []
    for name in _requirements():
        if name in INDIRECT:
            continue
        module = MODULE_NAMES.get(name, name.replace("-", "_"))
        root = module.split(".")[0]
        if re.search(rf"^\s*(?:import|from)\s+{re.escape(root)}\b", sources, re.MULTILINE):
            continue
        unused.append(f"{name} (looked for `import {root}`)")

    assert not unused, (
        "these packages ship to every user and are never imported — either use "
        "them, drop them, or explain them in INDIRECT:\n  " + "\n  ".join(unused)
    )


def test_the_heavy_ones_stayed_out():
    """The specific ballast that cost 240 MB, named so it cannot creep back."""
    pinned = set(_requirements())
    for banned in (
        "mediapipe",            # 50.8 MB + jaxlib 61.2 + opencv-contrib 46.2 + scipy 36.6
        "opencv-python",        # the GUI build; we use the headless one
        "pillow",               # never imported
        "google-api-python-client",  # returns with YouTube publishing, on demand
        "openai", "anthropic", "google-generativeai", "ollama",  # all called over HTTP
        "edge-tts", "pexels-api",
        "sqlalchemy",           # the database is plain sqlite3 from the standard library
    ):
        assert banned not in pinned, (
            f"{banned} is back in requirements.txt — if a feature now needs it, "
            "fetch it on demand instead of shipping it to everyone"
        )
