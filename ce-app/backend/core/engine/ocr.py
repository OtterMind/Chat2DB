"""On-screen text via RapidOCR — what a frame *says*, not what it looks like.

Three items in the plan were blocked by the same missing pass: reading the
reference's caption typography, seeing hand-made titles and graphics, and the
"no on-screen text" restriction. All three start here.

Why RapidOCR 1.4.4, checked from the wheel rather than the README:

* **Apache-2.0** in the PyPI `License` field; the models are Baidu's PaddleOCR,
  also Apache-2.0. (The newer `rapidocr` 3.x declares no licence field and pulls
  its models from a CDN at runtime — both worse, so it was passed over.)
* The models are **bundled in the wheel** — three ONNX files, 15.4 MB — so unlike
  DeepFilterNet there is **no runtime download** and no dependency on a host we
  have watched fail. Once fetched, it works with the network unplugged.
* It runs on `onnxruntime` and `numpy`, **both already in the installer**.

The honest costs, also from the wheel: it hard-imports `Pillow` and wants
`pyclipper` and `Shapely` (and nominally `opencv-python`, satisfied by the
headless build we ship). So this is an **on-demand engine**: `install()` does
`pip install --target ~/CuttingEdge/runtime/py` (the directory an update cannot
delete, §4.67), and nothing about it is in the shipped installer.

A known quirk, named so a caller cannot be surprised by it: the detector drops
spaces between words on tightly-set type — "Open the editor" reads as
"Opentheeditor". `normalise()` strips whitespace for matching; do not compare
raw output with `in` on spaced phrases.
"""
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

import numpy as np

from core import runtime_packages
from core.engine.compose import ffmpeg_binary, probe_media

#: Pulled once into ~/CuttingEdge/runtime/py. onnxruntime and numpy ship;
#: opencv-python is satisfied by the headless build the app already has.
DEPS = [
    "rapidocr-onnxruntime==1.4.4",
    "Pillow",
    "pyclipper",
    "Shapely!=2.0.4,>=1.7.1",
    "six",
    "PyYAML",
]

LICENCE = "Apache-2.0"


def installed() -> bool:
    return runtime_packages.is_installed("rapidocr_onnxruntime")


def status() -> dict:
    return {
        "installed": installed(),
        "licence": LICENCE,
        "runtimeDir": str(runtime_packages.runtime_dir()),
        "modelsBundled": True,
    }


def install(on_progress=None) -> dict:
    return runtime_packages.install(DEPS, on_progress=on_progress)


def normalise(text: str) -> str:
    """Whitespace-insensitive, for the space-dropping quirk described above."""
    return "".join(text.split()).casefold()


def _engine():
    runtime_packages.ensure_on_path()
    from rapidocr_onnxruntime import RapidOCR  # type: ignore

    return RapidOCR()


def read_image(path: str) -> list[dict]:
    """The words on one image, with the detector's confidence.

    An empty list is a real answer — a frame with no type on it — not an error.
    """
    if not installed():
        raise RuntimeError("the OCR engine is not installed")
    result, _ = _engine()(path)
    if not result:
        return []
    return [
        {"text": str(item[1]), "score": round(float(item[2]), 3),
         "box": [[int(v) for v in point] for point in item[0]]}
        for item in result
    ]


def contains(path: str, phrase: str) -> bool:
    """Does this image carry the phrase, once the space-quirk is normalised?"""
    want = normalise(phrase)
    return any(want in normalise(item["text"]) for item in read_image(path))


# --------------------------------------------------------------------- video


def _frame(path: str, at: float, out: Path) -> bool:
    run = subprocess.run(
        [ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
         "-ss", f"{max(0.0, at):.3f}", "-i", path, "-frames:v", "1", str(out)],
        capture_output=True,
    )
    return run.returncode == 0 and out.exists() and out.stat().st_size > 0


def screen_text(path: str, *, every: float = 3.0) -> list[dict]:
    """What is written on the video over time, sampled.

    Frames are extracted as PNGs (the detector reads a file, and the bundled
    FFmpeg here cannot hand it pixels in-process). `every` controls the sample
    rate; a title card that appears for a second between samples is missed, so
    for "does this video carry on-screen text" a smaller step is safer.
    """
    duration = float(probe_media(path).get("duration") or 0.0)
    if duration <= 0 or not installed():
        return []
    times = [t for t in np.arange(0.0, duration, every)] or [0.0]
    out: list[dict] = []
    with tempfile.TemporaryDirectory(prefix="ce-ocr-") as tmp:
        folder = Path(tmp)
        for at in times:
            frame = folder / f"f{int(at * 1000)}.png"
            if not _frame(path, at, frame):
                continue
            lines = read_image(str(frame))
            if lines:
                out.append({"time": round(at, 2),
                            "text": " ".join(line["text"] for line in lines)})
    return out


def text_coverage(path: str, *, every: float = 3.0) -> float:
    """Share of sampled frames that carry any type — 0..1.

    This is the number the "no on-screen text" restriction and the caption-
    typography question both reduce to.
    """
    samples = screen_text(path, every=every)
    duration = float(probe_media(path).get("duration") or 0.0)
    if duration <= 0:
        return 0.0
    total = len([t for t in np.arange(0.0, duration, every)] or [0.0])
    return round(len(samples) / total, 3) if total else 0.0
