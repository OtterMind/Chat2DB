"""Fetch wheels from PyPI **without pip**.

The packaged backend runs on an embeddable CPython that has **no pip** (the
embeddable distribution strips it), so every on-demand install that shelled out
to `python -m pip` died on the user's machine with "No module named pip". A wheel
is just a zip, and PyPI's JSON API tells us the download URL, so we can fetch and
unpack a package with nothing but the standard library.

Only the two wheel flavours we can actually load are chosen: a pure
`py3-none-any` wheel, or a platform wheel matching this interpreter
(`win_amd64` + our `cpXY`/`abi3`). Transitive dependencies are NOT resolved here —
callers pass the explicit list they need, which for our on-demand engines is a
short, known set.
"""
from __future__ import annotations

import json
import sys
import urllib.request
import zipfile
from pathlib import Path

_PYPI = "https://pypi.org/pypi/{name}/json"


def _release(name: str) -> dict:
    with urllib.request.urlopen(_PYPI.format(name=name), timeout=30) as response:
        return json.load(response)


def pick_wheel(name: str) -> dict | None:
    """The most-loadable wheel for this interpreter, or None."""
    try:
        data = _release(name)
    except Exception:  # noqa: BLE001 — unreachable registry is a clean failure
        return None
    wheels = [u for u in data.get("urls", []) if u.get("filename", "").endswith(".whl")]
    pure = [u for u in wheels if ("-py3-none-any" in u["filename"]
                                 or "-py2.py3-none-any" in u["filename"])]
    if pure:
        return pure[0]
    tag = f"cp{sys.version_info[0]}{sys.version_info[1]}"
    platform = [u for u in wheels
                if "win_amd64" in u["filename"]
                and (tag in u["filename"] or "abi3" in u["filename"])]
    return platform[0] if platform else (wheels[0] if wheels else None)


def download_wheel(name: str, dest: Path) -> Path:
    wheel = pick_wheel(name)
    if wheel is None:
        raise RuntimeError(f"no loadable wheel on PyPI for {name}")
    dest.mkdir(parents=True, exist_ok=True)
    target = dest / wheel["filename"]
    with urllib.request.urlopen(wheel["url"], timeout=600) as response, open(target, "wb") as out:
        out.write(response.read())
    return target


def extract_wheel(wheel: Path, target_dir: Path) -> None:
    """Unpack a wheel's importable files; skip scripts/data we do not run."""
    target_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(wheel) as archive:
        for member in archive.namelist():
            if member.endswith("/") or ".data/" in member:
                continue
            archive.extract(member, target_dir)


def parse_name(spec: str) -> str:
    import re  # noqa: PLC0415

    return re.split(r"[=<>!~\[]", spec, maxsplit=1)[0].strip()
