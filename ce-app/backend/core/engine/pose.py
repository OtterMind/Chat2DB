"""MediaPipe pose landmarks — the sports subject when no face looks at the camera.

The Haar cascade needs a ≥24 px face turned roughly to the lens; a volleyball
player mid-rally, a back on a pull-up bar, a jumper mid-rope have none of that,
and the motion centroid — the honest fallback we ship — follows *anything* that
moves, including a waving crowd. **MediaPipe Pose** (google-ai-edge/mediapipe,
Apache-2.0) gives 33 body landmarks on CPU, so the subject's centre becomes the
mid-hip/mid-shoulder point of an actual person, not a blob of change.

Like every other bridge here it is thin and defensive: absent engine means
`available()` is False and the caller keeps the centroid; an upstream API
mismatch returns None per frame rather than a wrong centre. The pin matches the
engine registry (`mediapipe==0.10.21` — the newest release with a win_amd64
cp311 wheel, verified against PyPI), and nothing ships in the installer.
"""
from __future__ import annotations

import importlib.util

from core import runtime_packages

PACKAGE = "mediapipe==0.10.21"

#: The four torso landmarks whose visible average is "the person", in MediaPipe's
#: 33-point layout. Limbs wave; hips and shoulders stay with the body.
_TORSO = (11, 12, 23, 24)
_MIN_VISIBILITY = 0.5


class PoseNotInstalled(RuntimeError):
    pass


def available() -> bool:
    return importlib.util.find_spec("mediapipe") is not None


def fetch(on_progress=None) -> dict:
    return runtime_packages.install([PACKAGE], on_progress=on_progress)


_POSE = None


def _pose():
    global _POSE
    if _POSE is None:
        import mediapipe as mp  # noqa: PLC0415

        _POSE = mp.solutions.pose.Pose(static_image_mode=True, model_complexity=0,
                                       enable_segmentation=False)
    return _POSE


def centre_from_landmarks(landmarks) -> tuple[float, float] | None:
    """Normalised (x, y) of the visible torso, or None when nobody is detected.

    Kept separate from the model call so the geometry is testable without
    MediaPipe: a centre built from invisible landmarks would be a guess, and a
    guess wearing a measurement's clothes is the bug this module exists to avoid.
    """
    xs, ys = [], []
    for index in _TORSO:
        try:
            point = landmarks[index]
        except (IndexError, TypeError):
            continue
        if float(getattr(point, "visibility", 1.0) or 0.0) < _MIN_VISIBILITY:
            continue
        xs.append(float(point.x))
        ys.append(float(point.y))
    if len(xs) < 2:  # one visible joint is a point, not a person
        return None
    return sum(xs) / len(xs), sum(ys) / len(ys)


def track_frame(gray_frame) -> tuple[float, float] | None:
    """The person's centre in one (grayscale) analysis frame, or None.

    Grayscale in, three channels out: pose does not need colour to find a body,
    and the analysis strip is already gray. Never raises into the caller.
    """
    if not available():
        return None
    try:
        import cv2  # noqa: PLC0415

        rgb = cv2.cvtColor(gray_frame, cv2.COLOR_GRAY2RGB)
        result = _pose().process(rgb)
        landmarks = getattr(result, "pose_landmarks", None)
        if landmarks is None:
            return None
        return centre_from_landmarks(landmarks.landmark)
    except Exception:  # noqa: BLE001 — upstream mismatch degrades, never crashes
        return None
