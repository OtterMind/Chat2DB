"""RIFE optical-flow frame interpolation — on-demand, experimental, degrade-safe.

Real slow-mo and smooth morph transitions need intermediate frames that only
optical-flow interpolation can invent believably; `setpts` slow-mo just holds
frames. RIFE (MIT) is the lightest credible option, and the `rife-ncnn-vulkan`
bindings run it **without torch** (ncnn, CPU or Vulkan), so it stays an on-demand
engine rather than a several-hundred-MB torch dependency.

This module is deliberately thin and defensive: it is the bridge, not the model.
It is only exercised when the user has fetched the engine; on every machine
without it, `available()` is False and the caller falls back to the existing
`setpts` slow-mo. If the upstream binding's API ever differs, `interpolate`
raises a clear error instead of silently producing garbage — experimental means
labelled, not unguarded.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

from core import runtime_packages

PACKAGE = "rife-ncnn-vulkan-python"


class RifeNotInstalled(RuntimeError):
    pass


def available() -> bool:
    return importlib.util.find_spec("rife_ncnn_vulkan_python") is not None


def fetch(on_progress=None) -> dict:
    """Fetch the ncnn RIFE bindings into the user's runtime dir."""
    return runtime_packages.install([PACKAGE], on_progress=on_progress)


def interpolate(frame_a: bytes, frame_b: bytes, width: int, height: int,
                steps: int = 1) -> list[bytes]:
    """`steps` intermediate frames between two RGB frames, or raise if absent.

    Returns raw RGB bytes per frame. Defensive: any upstream API mismatch raises
    a clear error rather than a wrong frame.
    """
    if not available():
        raise RifeNotInstalled("RIFE is not fetched — fetch it in Settings")
    try:
        import numpy as np  # noqa: PLC0415
        from rife_ncnn_vulkan_python import Rife  # noqa: PLC0415

        rife = Rife(gpuid=-1)  # CPU; a Vulkan gpu id can be passed when present
        a = np.frombuffer(frame_a, dtype=np.uint8).reshape(height, width, 3)
        b = np.frombuffer(frame_b, dtype=np.uint8).reshape(height, width, 3)
        out = []
        for i in range(1, steps + 1):
            mid = rife.process(a, b, timestep=i / (steps + 1))
            out.append(bytes(np.asarray(mid, dtype=np.uint8).tobytes()))
        return out
    except RifeNotInstalled:
        raise
    except Exception as error:  # noqa: BLE001 — surface upstream mismatch clearly
        raise RifeNotInstalled(f"RIFE binding unusable here: {error}") from error
