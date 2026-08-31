"""MediaPipe Face Detection — the professional tracker the Haar cascade is not.

The reframe used to rely on OpenCV's `haarcascade_frontalface_default`, which
needs a roughly frontal, ≥24 px face and drops out on the footage this app is
for: a spiker seen from the side, a back on a pull-up bar, a jumper mid-rope.
MediaPipe's **BlazeFace** short-range detector (part of the same `mediapipe`
package the pose bridge ships, Apache-2.0, pinned `0.10.21` in the engine
registry) detects faces at any orientation on CPU, so the subject centre is a
real face, not a guess.

The bridge is thin and degrade-safe, exactly like `pose.py`: without the
package `available()` is False and the caller keeps the Haar cascade; an
upstream API mismatch returns None per frame instead of a wrong centre. Nothing
ships in the installer — the engine is fetched on demand.
"""
from __future__ import annotations

import importlib.util

PACKAGE = "mediapipe==0.10.21"  # matches the engine registry and pose.py


def available() -> bool:
    return importlib.util.find_spec("mediapipe") is not None


class FaceDetector:
    """A lazily-created BlazeFace short-range detector, reused across frames.

    Kept as a class (not module globals) so the caller owns its lifecycle and a
    second detector is never created by accident.
    """

    def __init__(self) -> None:
        self._det = None
        self._broken = False

    def _build(self):
        if self._det is not None or self._broken:
            return self._det
        try:
            import mediapipe as mp  # noqa: PLC0415

            self._det = mp.solutions.face_detection.FaceDetection(
                model_selection=0,  # short-range: the subject is near the lens
                min_detection_confidence=0.5,
            )
        except Exception:  # noqa: BLE001 — an API mismatch must not crash tracking
            self._broken = True
            self._det = None
        return self._det

    def detect(self, frame) -> tuple[float, float, float] | None:
        """Normalised (x, y, size) centre of the most-confident face, or None."""
        det = self._build()
        if det is None:
            return None
        try:
            result = det.process(frame)
        except Exception:  # noqa: BLE001
            return None
        if not result.detections:
            return None
        best = max(result.detections, key=lambda d: d.score[0])
        box = best.location_data.relative_bounding_box
        h, w = frame.shape[:2]
        return ((box.xmin + box.width / 2), (box.ymin + box.height / 2),
                max(0.02, box.width))


def centre_from_box(box: dict) -> tuple[float, float, float]:
    """Pure conversion, testable without MediaPipe: relative box → (x, y, size)."""
    x = float(box["xmin"]) + float(box["width"]) / 2
    y = float(box["ymin"]) + float(box["height"]) / 2
    return (x, y, max(0.02, float(box["width"])))
