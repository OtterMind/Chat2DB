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
import time
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


def _pip_available() -> bool:
    try:
        run = subprocess.run([sys.executable, "-m", "pip", "--version"],
                             capture_output=True, timeout=30)
        return run.returncode == 0
    except Exception:  # noqa: BLE001
        return False


def _install_pip_free(packages: list[str], say) -> dict:
    """The packaged embeddable Python has no pip, so fetch wheels directly.

    A wheel is a zip and PyPI's JSON API gives the URL; the stdlib is enough.
    Callers pass the explicit dependency list, so no resolution is needed.
    When a project publishes **no wheel** (source only), a pure-Python sdist is
    unpacked by our own extractor; one that carries C/C++ code is refused with a
    plain reason instead of dying in a zip error.
    """
    from core.engine import _pypi  # noqa: PLC0415

    target = ensure_on_path()
    count = max(1, len(packages))
    for index, spec in enumerate(packages):
        name = _pypi.parse_name(spec)
        base = 0.1 + 0.8 * index / count
        span = 0.8 / count

        def report(done: int, total: int, _name=name, _base=base, _span=span) -> None:
            mb = f"{done // 1_000_000}/{total // 1_000_000} MB" if total else f"{done // 1_000_000} MB"
            frac = _base + _span * (done / total if total else 0.0)
            say("download", frac, f"{_name} {mb}")

        say("download", base, f"Fetching {name}")
        try:
            wheel = _pypi.download_wheel(name, target / "_wheels", on_bytes=report)
            _pypi.extract_wheel(wheel, target)
        except RuntimeError:
            sdist = _pypi.download_sdist(name, target / "_wheels", on_bytes=report)
            if not _pypi.sdist_is_pure(sdist):
                raise RuntimeError(
                    f"{name} is source-only on PyPI and contains compiled code; "
                    "the packaged runtime cannot build it (needs pip + a C++ toolchain)"
                ) from None
            say("install", 0.15 + 0.8 * index / max(1, len(packages)),
                f"{name}: no wheel — unpacking the pure-Python source")
            _pypi.extract_sdist(sdist, target)
    say("done", 1.0, f"Installed into {target} (no pip)")
    return {"target": str(target), "packages": packages, "log": ["pip-free install"]}


def install(packages: list[str], on_progress=None) -> dict:
    """`pip install --target ~/CuttingEdge/runtime/py`, narrated.

    pip's own cache lives in the user's profile, so a download interrupted here
    resumes from the cache instead of starting again — which, together with the
    directory choice, is the whole point of this module.
    """
    target = ensure_on_path()
    say = on_progress or (lambda *_args, **_kwargs: None)

    # Never re-install what already imports. Overwriting a library the running
    # backend has loaded is exactly what raises "[WinError 5] Permission denied"
    # on Windows (DLL in use), and re-downloading torch the user already has only
    # burns their bandwidth. So a second "download all" is a fast no-op, not a
    # fight with the operating system.
    import importlib.util  # noqa: PLC0415

    from core.engine import _pypi  # noqa: PLC0415

    def _present(spec: str) -> bool:
        module = _pypi.parse_name(spec).replace("-", "_").split("[")[0]
        try:
            return importlib.util.find_spec(module) is not None
        except Exception:  # noqa: BLE001 — an unimportable name is "not present"
            return False

    packages = [p for p in packages if not _present(p)]
    if not packages:
        say("done", 1.0, "Everything requested is already installed")
        return {"target": str(target), "packages": [], "log": ["nothing to do"]}

    say("resolve", 0.05, f"Fetching {', '.join(packages)}")

    if not _pip_available():
        return _install_pip_free(packages, say)

    process = subprocess.Popen(
        [
            sys.executable, "-m", "pip", "install",
            "--no-warn-script-location", "--upgrade",
            "--target", str(target), *packages,
        ],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    )

    # A2 (advisors): a pip that hangs on a dead network must not hang the task
    # forever — 20 silent minutes kill the child and surface a real error.
    import threading  # noqa: PLC0415

    last_activity = time.monotonic()

    def watchdog() -> None:
        while process.poll() is None:
            if time.monotonic() - last_activity > 1200:
                process.kill()
                return
            time.sleep(5)

    threading.Thread(target=watchdog, daemon=True).start()

    lines: list[str] = []
    downloaded = 0
    assert process.stdout is not None
    for line in process.stdout:
        last_activity = time.monotonic()
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
