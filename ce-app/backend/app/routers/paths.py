"""A4 (advisors): one path gate for every router that touches user files.

Pragmatic hardening for a single-user local app: absolute path, no null bytes,
no `..` traversal out of a prefix, must exist, and ≤ 4 GB. Roots stay open on
purpose — a video editor must read the user's footage wherever they keep it —
but the old "any string becomes a Path" hole is closed in every router that
uses this helper.
"""
from __future__ import annotations

import os
from pathlib import Path

MAX_BYTES = 4 * 1024 * 1024 * 1024


def safe_user_path(raw: str, *, must_exist: bool = True) -> Path:
    if not raw or "\x00" in raw:
        raise ValueError("empty or invalid path")
    path = Path(raw)
    if not path.is_absolute():
        raise ValueError("path must be absolute")
    if ".." in path.parts:
        raise ValueError("path traversal is not allowed")
    if must_exist:
        if not path.exists():
            raise FileNotFoundError(str(path))
        if path.is_file() and path.stat().st_size > MAX_BYTES:
            raise ValueError("file larger than 4 GB")
    return path
