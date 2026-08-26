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


def pick_sdist(name: str) -> dict | None:
    """The source distribution of the latest release, or None."""
    try:
        data = _release(name)
    except Exception:  # noqa: BLE001
        return None
    sdists = [u for u in data.get("urls", [])
              if u.get("filename", "").endswith((".tar.gz", ".zip"))]
    return sdists[0] if sdists else None


def classify(name: str) -> str:
    """How installable is this package for us, without looking inside archives?

    * ``wheel``       — a loadable wheel exists (pure or matching this interpreter);
    * ``sdist``       — source only; installable when pure Python (we unpack it
                        ourselves) or with pip plus a toolchain;
    * ``none``        — nothing on PyPI under this name (a 404).
    """
    try:
        data = _release(name)
    except Exception:  # noqa: BLE001
        return "none"
    wheels = [u for u in data.get("urls", []) if u.get("filename", "").endswith(".whl")]
    tag = f"cp{sys.version_info[0]}{sys.version_info[1]}"
    loadable = [u for u in wheels if ("-py3-none-any" in u["filename"]
                                      or "-py2.py3-none-any" in u["filename"]
                                      or ("win_amd64" in u["filename"]
                                          and (tag in u["filename"] or "abi3" in u["filename"])))]
    if loadable:
        return "wheel"
    if any(u.get("filename", "").endswith((".tar.gz", ".zip")) for u in data.get("urls", [])):
        return "sdist"
    return "none" if not wheels else "wheel"


#: File types that mean "a compiler is required" — a sdist carrying any of these
#: cannot be installed by the pip-free extractor in the packaged runtime.
_C_SOURCE = (".c", ".cc", ".cpp", ".cxx", ".pyx", ".rs", ".go", ".m", ".mm")


def sdist_is_pure(path: Path) -> bool:
    """Does this source distribution contain no compiled code?"""
    import tarfile  # noqa: PLC0415

    if str(path).endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            return not any(n.lower().endswith(_C_SOURCE) for n in archive.namelist())
    with tarfile.open(path) as archive:
        return not any(n.lower().endswith(_C_SOURCE) for n in archive.getnames())


def download_sdist(name: str, dest: Path) -> Path:
    sdist = pick_sdist(name)
    if sdist is None:
        raise RuntimeError(f"no source distribution on PyPI for {name}")
    dest.mkdir(parents=True, exist_ok=True)
    target = dest / sdist["filename"]
    with urllib.request.urlopen(sdist["url"], timeout=600) as response, open(target, "wb") as out:
        out.write(response.read())
    return target


def _sdist_members(path: Path):
    import tarfile  # noqa: PLC0415

    if str(path).endswith(".zip"):
        archive = zipfile.ZipFile(path)
        return archive, archive.namelist(), True
    archive = tarfile.open(path)
    return archive, [m for m in archive.getnames()], False


def extract_sdist(path: Path, target_dir: Path) -> None:
    """Unpack a *pure-Python* sdist's importable files into `target_dir`.

    sdists keep everything under one `name-version/` root. We copy the top-level
    packages (folders with an `__init__.py`) and top-level modules, skipping the
    usual non-importable neighbours (tests, docs, examples) and the metadata.
    Anything with compiled code is refused here too, not only at the caller —
    silently dropping the binaries would produce a package that imports and
    then dies, which is worse than a plain refusal.
    """
    if not sdist_is_pure(path):
        raise RuntimeError(f"{path.name} contains compiled code; not unpackable pip-free")

    archive, names, is_zip = _sdist_members(path)
    roots = sorted({n.split("/")[0] for n in names if "/" in n})
    if len(roots) != 1:
        raise RuntimeError(f"{path.name}: unexpected sdist layout ({len(roots)} roots)")
    root = roots[0]
    skip = {"tests", "test", "docs", "doc", "examples", "example", "scripts",
            "benchmarks", ".github", "tools"}

    def read(member: str) -> bytes:
        if is_zip:
            return archive.read(member)
        fileobj = archive.extractfile(member)
        return fileobj.read() if fileobj else b""

    target_dir.mkdir(parents=True, exist_ok=True)
    copied = 0
    for member in names:
        if member.endswith("/"):
            continue
        rel = member[len(root) + 1:]
        if not rel or not rel.endswith((".py", ".pyi")):
            continue  # importable files only; package data stays out
        top = rel.split("/")[0]
        if top in skip or top.endswith((".egg-info", ".dist-info")):
            continue
        dest = target_dir / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        with open(dest, "wb") as out:
            out.write(read(member))
        copied += 1
    archive.close()
    if copied == 0:
        raise RuntimeError(f"{path.name}: nothing importable found in the sdist")


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
