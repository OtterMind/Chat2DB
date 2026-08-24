"""The backend's identity — and where its version number comes from.

The version lives in `frontend/package.json`: that is what the updater checks,
what the installer is built from and what a release is named after. Reading it
here rather than keeping a second copy is the difference between `/api/health`,
the Diagnostics screen and the FastAPI docs agreeing with the app they belong to,
and quietly reporting the previous release forever. It is the same class of bug
as a Settings card that said `base` while the engine loaded `small` (§4.44): a
label that can contradict the thing it labels will, eventually, in front of a
user who is trying to tell us which build they are on.

Two fallbacks, because the packaged install cannot read the frontend: it lives
inside an asar, which is not a filesystem Python can walk. `CE_VERSION`, set by
the Electron main process, wins there, and the constant is the last resort.
"""
from __future__ import annotations

import json
import os
from pathlib import Path

__app_name__ = "Cutting Edge"

#: Only used when neither the frontend nor `CE_VERSION` can be read.
_FALLBACK = "0.9.7"


def _read_version() -> str:
    override = os.environ.get("CE_VERSION", "").strip()
    if override:
        return override
    candidates = (
        # development: backend/app/__init__.py → ce-app/frontend/package.json
        Path(__file__).resolve().parents[2] / "frontend" / "package.json",
    )
    for path in candidates:
        try:
            if path.exists():
                version = str(json.loads(path.read_text(encoding="utf-8")).get("version", "")).strip()
                if version:
                    return version
        except Exception:  # noqa: BLE001 — an unreadable label must not stop the app
            continue
    return _FALLBACK


__version__ = _read_version()
