"""Editing proxies.

Scrubbing a 4K phone video through a `<video>` element is painful: every seek
decodes a huge frame. Every desktop editor solves this the same way — edit
against a small, seek-friendly copy and render the final file from the original.

The proxy is a 720p H.264 file with a keyframe every half second, which is what
makes seeking feel instant. Nothing here ever touches the source, and the export
path deliberately does not look at proxies.
"""
from __future__ import annotations

import hashlib
import subprocess
import threading
from dataclasses import dataclass
from pathlib import Path

from app.config import settings
from core.engine.compose import ffmpeg_binary, probe_media

#: Anything narrower than this decodes fast enough to edit directly.
PROXY_TRIGGER_WIDTH = 1280
#: 1080p, not 720p. The proxy is what the monitor shows, so its resolution is
#: the preview's resolution — a 4K phone clip used to be previewed as a soft
#: 720p CRF-26 picture to save disk and encode time, and disk was never the
#: constraint. 1080p H.264 plays on anything that can run this app.
PROXY_HEIGHT = 1080


def proxy_dir() -> Path:
    path = settings.work_dir / "proxies"
    path.mkdir(parents=True, exist_ok=True)
    return path


def proxy_path(source: Path) -> Path:
    """A stable name per source file *and* modification time."""
    stat = source.stat()
    key = hashlib.sha1(f"{source.resolve()}|{stat.st_mtime_ns}|{stat.st_size}".encode()).hexdigest()[:16]
    return proxy_dir() / f"{source.stem[:40]}-{key}.mp4"


def needs_proxy(info: dict) -> bool:
    """Only big video files are worth the wait."""
    if not info.get("has_video"):
        return False
    return max(int(info.get("width") or 0), int(info.get("height") or 0)) > PROXY_TRIGGER_WIDTH


def build_command(source: Path, target: Path) -> list[str]:
    # The graphics card, when there is one: decoding is most of the work here,
    # and a proxy of a 4K clip is exactly the job it exists for.
    from core.engine import gpu

    return [
        ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
        *gpu.decode_args(),
        "-i", str(source),
        # Keep the long edge at 720 whichever way the phone was held.
        # `bicubic` instead of `fast_bilinear`: the downscale is the only place
        # detail is lost for good, and it costs milliseconds.
        "-vf", f"scale='if(gt(iw,ih),-2,{PROXY_HEIGHT})':'if(gt(iw,ih),{PROXY_HEIGHT},-2)':flags=bicubic",
        # On a machine with an NVIDIA card this is NVENC; otherwise x264 at
        # `superfast`, which measured *faster* than `veryfast` at this
        # resolution (68 s vs 80 s on a 2-minute 1440p clip) and looked better.
        *gpu.encode_args({"preset": "superfast", "crf": 21, "nvenc_cq": 23}),
        "-g", "15", "-keyint_min", "15", "-sc_threshold", "0",
        "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k", "-ac", "2",
        "-movflags", "+faststart",
        str(target),
    ]


@dataclass
class ProxyState:
    """What the UI needs to know about one proxy."""

    source: str
    status: str = "idle"           # idle | building | ready | failed | skipped
    proxy: str | None = None
    error: str | None = None


_states: dict[str, ProxyState] = {}
_running: set[str] = set()
_lock = threading.Lock()


def state_for(source: str) -> ProxyState:
    with _lock:
        return _states.get(source) or ProxyState(source=source)


def _build(source: Path, target: Path) -> None:
    key = str(source)
    try:
        subprocess.run(build_command(source, target), check=True, timeout=60 * 60)
        with _lock:
            _states[key] = ProxyState(source=key, status="ready", proxy=str(target))
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
        target.unlink(missing_ok=True)
        with _lock:
            _states[key] = ProxyState(source=key, status="failed", error=str(error))
    finally:
        with _lock:
            _running.discard(key)


def ensure(source_path: str, force: bool = False) -> ProxyState:
    """Start (or report) the proxy for a file. Never blocks the request."""
    source = Path(source_path)
    if not source.exists() or not source.is_file():
        raise FileNotFoundError(source_path)

    key = str(source)
    target = proxy_path(source)

    if target.exists() and target.stat().st_size > 0:
        ready = ProxyState(source=key, status="ready", proxy=str(target))
        with _lock:
            _states[key] = ready
        return ready

    info = probe_media(key)
    if not force and not needs_proxy(info):
        skipped = ProxyState(source=key, status="skipped")
        with _lock:
            _states[key] = skipped
        return skipped

    with _lock:
        if key in _running:
            return _states.get(key) or ProxyState(source=key, status="building")
        _running.add(key)
        _states[key] = ProxyState(source=key, status="building")

    thread = threading.Thread(target=_build, args=(source, target), daemon=True, name="ce-proxy")
    thread.start()
    return _states[key]


def build_now(source_path: str) -> ProxyState:
    """Synchronous variant — used by the tests and by batch tooling."""
    source = Path(source_path)
    target = proxy_path(source)
    if not (target.exists() and target.stat().st_size > 0):
        _build(source, target)
    return state_for(str(source))
