"""TransNetV2 shot boundaries + junction typing — on-demand, degrade-safe.

Style Match templates want to know not just *where* shots change but *how* —
a hard cut, a dissolve, or a fade reads as a different rhythm. TransNetV2 (MIT,
wheel verified on PyPI, top-level module `transnetv2_pytorch`) is the accepted
boundary detector; the junction **typing** is our own measurement on the pixels
(a cut is one violent frame, a dissolve is a ramp over several, a fade dips
towards black), so typing works on any machine — with or without the engine —
using the boundaries our scene detector already finds.

Like the other bridges this module never raises into the caller for the
no-engine case: `detect()` reports `status: no-engine` and hands back empty
segments, and the caller keeps its existing detector.
"""
from __future__ import annotations

import importlib.util

from core import runtime_packages

PACKAGE = "transnetv2-pytorch"

#: How many frames either side of a boundary we look at when typing a junction.
WINDOW = 6


class TransNetNotInstalled(RuntimeError):
    pass


def available() -> bool:
    return importlib.util.find_spec("transnetv2_pytorch") is not None


def fetch(on_progress=None) -> dict:
    return runtime_packages.install([PACKAGE], on_progress=on_progress)


def _to_segments(raw_scenes, fps: float) -> list[dict]:
    """Model scene rows `(start_frame, end_frame)` → second ranges.

    Kept separate so the conversion is testable without the engine.
    """
    out = []
    for row in raw_scenes or []:
        try:
            start_frame, end_frame = int(row[0]), int(row[1])
        except (TypeError, ValueError, IndexError):
            continue
        if fps <= 0 or end_frame < start_frame:
            continue
        out.append({"start": round(start_frame / fps, 3), "end": round(end_frame / fps, 3)})
    return out


def type_junction(diffs: list[float], luma: list[float],
                  spike: float = 30.0, ramp: float = 8.0, black: float = 16.0) -> str:
    """Classify one junction from the measured frame-to-frame difference profile
    and mean-luma profile across it.

    * **cut** — one violent jump: a single diff far above its neighbours;
    * **dissolve** — a ramp: several consecutive elevated diffs, none dominant;
    * **fade** — the picture dips towards black at the junction.
    The floors are deliberate parameters, not magic numbers hidden in code.
    """
    if not diffs:
        return "cut"
    if luma and min(luma) < black:
        return "fade"
    ramps = [d for d in diffs if d > ramp]
    if len(ramps) >= 3 and max(diffs) < 3 * (sum(diffs) / len(diffs)):
        return "dissolve"
    if max(diffs) >= spike:
        return "cut"
    return "dissolve" if len(ramps) >= 2 else "cut"


def classify_junctions(path: str, boundaries: list[float]) -> list[dict]:
    """Type each boundary as cut/dissolve/fade by looking at the pixels.

    Uses OpenCV (already pinned and shipped) to read `WINDOW` frames either side
    of each boundary. A boundary we cannot read is typed `unknown`, never guessed.
    """
    try:
        import cv2  # noqa: PLC0415
    except Exception:  # noqa: BLE001
        return [{"at": b, "kind": "unknown"} for b in boundaries]

    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        return [{"at": b, "kind": "unknown"} for b in boundaries]
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0

    out = []
    for at in boundaries:
        diffs: list[float] = []
        luma: list[float] = []
        start = max(0, int((at - WINDOW / fps) * fps))
        cap.set(cv2.CAP_PROP_POS_FRAMES, start)
        prev = None
        for _ in range(2 * WINDOW):
            ok, frame = cap.read()
            if not ok:
                break
            small = cv2.resize(frame, (64, 36))
            luma.append(float(small.mean()))
            if prev is not None:
                diffs.append(float(cv2.absdiff(small, prev).mean()))
            prev = small
        out.append({"at": round(at, 3),
                    "kind": type_junction(diffs, luma) if diffs else "unknown"})
    cap.release()
    return out


def detect(path: str) -> dict:
    """Shot segments from TransNetV2, typed junctions from our own pixels.

    Without the engine the boundaries come from the existing scene detector, so
    junction typing — the part Style Match actually reads — still works; the
    `detector` field says honestly which one ran.
    """
    if available():
        try:
            import cv2  # noqa: PLC0415
            from transnetv2_pytorch import TransNetV2  # noqa: PLC0415

            cap = cv2.VideoCapture(path)
            fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
            frames = []
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                frames.append(cv2.cvtColor(cv2.resize(frame, (64, 36)), cv2.COLOR_BGR2RGB))
            cap.release()
            model = TransNetV2()
            scenes = model.predict_frames(frames)
            segments = _to_segments(scenes, fps)
            detector = "transnetv2"
            boundaries = [s["start"] for s in segments[1:]] or []
        except Exception as error:  # noqa: BLE001 — upstream mismatch must not break style
            return {"segments": [], "junctions": [], "status": f"error: {error}",
                    "detector": None}
    else:
        from core.engine import analyze  # noqa: PLC0415

        boundaries = analyze.detect_scenes(path)
        segments = []
        detector = "scenedetect"

    junctions = classify_junctions(path, boundaries)
    return {"segments": segments, "junctions": junctions,
            "status": "ok", "detector": detector}
