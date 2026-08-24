"""Packages the user downloads, kept where an update cannot delete them.

The installer replaces the whole application folder. Anything `pip` put inside
it — the 1.3 GB of CUDA libraries, for instance — is gone the next time the app
updates, and the user pays for the download again. That is not acceptable for a
project that ships most days.

So on-demand packages go to a directory beside the user's projects:

    ~/CuttingEdge/runtime/py

which the installer never touches, and which is put on `sys.path` when the
backend starts. Two consequences worth stating:

* a download happens **once**, not once per release;
* the packages there are the user's, not ours — the uninstaller leaves them, and
  deleting that folder by hand is a complete reset.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def runtime_dir() -> Path:
    """`~/CuttingEdge/runtime/py`, created on demand."""
    from app.config import settings

    path = Path(settings.cuttingedge_home) / "runtime" / "py"
    path.mkdir(parents=True, exist_ok=True)
    return path


def ensure_on_path() -> Path:
    """Make what is already downloaded importable. Safe to call repeatedly."""
    path = runtime_dir()
    text = str(path)
    if text not in sys.path:
        # Ahead of the bundled site-packages: a user who fetched a newer CUDA
        # runtime should get theirs, not ours.
        sys.path.insert(0, text)
    return path


def is_installed(module: str) -> bool:
    """Is this importable at all — from the app or from the user's runtime?"""
    import importlib.util

    ensure_on_path()
    try:
        return importlib.util.find_spec(module) is not None
    except ModuleNotFoundError:
        return False


def install(packages: list[str], on_progress=None) -> dict:
    """`pip install --target ~/CuttingEdge/runtime/py`, narrated.

    pip's own cache lives in the user's profile, so a download interrupted here
    resumes from the cache instead of starting again — which, together with the
    directory choice, is the whole point of this module.
    """
    target = ensure_on_path()
    say = on_progress or (lambda *_args, **_kwargs: None)
    say("resolve", 0.05, f"Fetching {', '.join(packages)}")

    process = subprocess.Popen(
        [
            sys.executable, "-m", "pip", "install",
            "--no-warn-script-location", "--upgrade",
            "--target", str(target), *packages,
        ],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    )

    lines: list[str] = []
    downloaded = 0
    assert process.stdout is not None
    for line in process.stdout:
        line = line.rstrip()
        if not line:
            continue
        lines.append(line)
        lowered = line.lower()
        # pip prints one "Downloading <wheel> (553.2 MB)" per package, then
        # "Installing collected packages". That is enough to move a bar
        # honestly without pretending to know byte counts we cannot see.
        if lowered.startswith("downloading") or " downloading " in lowered:
            downloaded += 1
            fraction = min(0.85, 0.1 + 0.75 * downloaded / max(1, len(packages)))
            say("download", fraction, line[:120])
        elif "installing collected packages" in lowered:
            say("install", 0.9, "Unpacking")
        elif lowered.startswith("successfully installed"):
            say("install", 0.98, line[:120])

    code = process.wait()
    if code != 0:
        raise RuntimeError("\n".join(lines[-6:]) or f"pip exited with {code}")

    say("done", 1.0, f"Installed into {target}")
    return {"target": str(target), "packages": packages, "log": lines[-6:]}
