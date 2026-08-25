"""Auto-reframe: keep the speaker in the frame when the shape changes.

Turning a 16:9 recording into 9:16 throws away two thirds of the width. Until
now we threw away the *sides*, which is right only when the subject stands in
the middle — the honest name for that is a centre crop, and it is what the
`Face Tracking` tile has been doing behind a `BETA` badge.

This module measures where the face actually is and produces a **camera path**
for it. Three deliberate choices:

* **Detection is offline and bundled.** OpenCV's Haar cascades ship inside the
  `opencv-python-headless` wheel we already pin (17 of them, 930 KB for the
  frontal-face one), so nothing is downloaded on the user's machine and the
  feature works on a laptop with no network and no GPU. MediaPipe would be more
  accurate; it also drags in ~160 MB of transitive wheels and a model file
  fetched at runtime, which is a different trade than the one we want here.
* **The result is keyframes, not a special render path.** The path becomes `x`
  keyframes on the clip — the same five channels the editor already animates and
  the exporter already reproduces. So the user can *see* the camera move on the
  timeline, drag a key, or delete the whole thing. An auto-reframe that cannot
  be corrected by hand is a worse feature than one that can.
* **A jittery path is worse than no path.** Raw per-frame detections wobble by a
  few pixels; a camera that wobbles reads as broken. The path is smoothed with a
  deadband (small movements are ignored entirely), an exponential filter, and a
  speed limit, and the result is measured in `tests/test_reframe.py`.
"""
from __future__ import annotations

import subprocess
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from core.engine.compose import ffmpeg_binary, probe_media

try:  # OpenCV is optional at import time, like everywhere else in this codebase.
    import cv2  # type: ignore
except Exception:  # pragma: no cover - a machine without the wheel
    cv2 = None  # type: ignore

#: Frames per second to look at. Faces do not move fast enough to need more, and
#: this is the difference between two seconds of analysis and twenty.
SAMPLE_FPS = 4.0
#: Width the frames are analysed at. Haar needs the face to be ≥ ~24 px.
ANALYSIS_WIDTH = 480
#: Movements smaller than this fraction of the width are not worth a camera move.
DEADBAND = 0.02
#: How fast the smoothed path may chase a new position (0..1 per sample).
FOLLOW = 0.22
#: Hard speed limit, in fractions of the frame width per second.
MAX_SPEED = 0.35


@dataclass
class Detection:
    t: float
    x: float | None  # subject centre, 0..1 across the frame; None = nothing found
    kind: str = "face"  # how the subject was found: face, body, or none
    y: float | None = None
    size: float = 0.0


@dataclass
class ReframePlan:
    """A camera path, expressed the way the editor already understands."""

    scale: float = 1.0
    keyframes: list[dict] = field(default_factory=list)
    faces_found: int = 0
    samples: int = 0
    fallback: bool = False
    reason: str = ""
    #: Which signal the camera followed: a face, the moving region, or nothing.
    tracker: str = "none"

    @property
    def coverage(self) -> float:
        return round(self.faces_found / self.samples, 3) if self.samples else 0.0

    def as_dict(self) -> dict:
        return {
            "scale": round(self.scale, 4),
            "keyframes": self.keyframes,
            "facesFound": self.faces_found,
            "samples": self.samples,
            "coverage": self.coverage,
            "fallback": self.fallback,
            "reason": self.reason,
            "tracker": self.tracker,
        }


def _cascade():
    if cv2 is None:
        return None
    path = Path(cv2.data.haarcascades) / "haarcascade_frontalface_default.xml"
    if not path.exists():  # a trimmed OpenCV build
        return None
    classifier = cv2.CascadeClassifier(str(path))
    return None if classifier.empty() else classifier


def detect_faces(path: str, fps: float = SAMPLE_FPS, width: int = ANALYSIS_WIDTH) -> list[Detection]:
    """Where the largest face sits, a few times a second.

    One FFmpeg process for the whole file (the lesson from `style.sample_strip`:
    a process per frame costs more in startup than in decoding).
    """
    classifier = _cascade()
    if classifier is None:
        return []

    info = probe_media(path)
    source_w = int(info.get("width") or 0)
    source_h = int(info.get("height") or 0)
    if source_w <= 0 or source_h <= 0:
        return []
    height = max(2, int(round(width * source_h / source_w / 2)) * 2)

    process = subprocess.run(
        [
            ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-i", str(path),
            "-vf", f"fps={fps:.3f},scale={width}:{height},format=gray",
            "-f", "rawvideo", "-",
        ],
        capture_output=True,
    )
    frame_bytes = width * height
    total = len(process.stdout) // frame_bytes
    detections: list[Detection] = []
    prev: np.ndarray | None = None
    for index in range(total):
        frame = np.frombuffer(
            process.stdout[index * frame_bytes : (index + 1) * frame_bytes], dtype=np.uint8
        ).reshape(height, width)
        faces = classifier.detectMultiScale(frame, scaleFactor=1.1, minNeighbors=5,
                                            minSize=(max(24, width // 20), max(24, width // 20)))
        moment = index / fps
        if len(faces) == 0:
            # No face — but a sport rarely shows one. Follow the moving region
            # instead, so a rally or a rep still has a subject.
            centre = _motion_centre(prev, frame, width)
            if centre is not None:
                detections.append(
                    Detection(t=moment, x=centre[0], y=centre[1], size=0.3, kind="motion")
                )
            else:
                detections.append(Detection(t=moment, x=None, kind="none"))
            prev = frame
            continue
        prev = frame
        # The biggest face is the subject; a face in the background is not the shot.
        fx, fy, fw, fh = max(faces, key=lambda f: f[2] * f[3])
        detections.append(
            Detection(t=moment, x=(fx + fw / 2) / width, y=(fy + fh / 2) / height, size=fw / width)
        )
    return detections




#: The Haar cascade needs a face ≥ ~24 px looking roughly at the camera. A
#: volleyball player mid-rally, a back turned on a pull-up bar, a jumper mid-rope
#: have no detectable face — but they are exactly the region of the frame that
#: *moves*. So when the face cascade gives up, follow the centroid of motion
#: (the frame-to-frame difference above a floor). It is OpenCV-only, needs no
#: download, and it is the fallback that makes reframe useful for the sports the
#: owner films. Camera shake moves the whole frame (a diffuse difference), while
#: an athlete moves a compact region — the centroid still tracks the bulk of it.
def _motion_centre(prev: np.ndarray, frame: np.ndarray, width: int):
    if prev is None:
        return None
    diff = np.abs(frame.astype(np.int16) - prev.astype(np.int16))
    mask = (diff > 18).astype(np.float32)
    mass = float(mask.sum())
    if mass < (width * prev.shape[0]) * 0.004:  # nothing really moved
        return None
    ys, xs = np.nonzero(mask)
    return (float(xs.mean()) / width, float(ys.mean()) / prev.shape[0], mass)


def smooth(detections: list[Detection], fps: float = SAMPLE_FPS) -> list[tuple[float, float]]:
    """Turn wobbling detections into a camera path a person would not notice.

    **Zero-phase, on purpose.** The first version was a causal exponential
    follow — the obvious choice if the frames arrived live — and it lagged the
    subject by a measured 268 px on a face crossing the frame in six seconds.
    We are not live: the whole clip is on disk, so the filter can look forwards
    as well as backwards and the camera can be *with* the face instead of
    behind it.

    Three passes, in this order:

    1. gaps are interpolated between the detections on either side (a face lost
       for a moment must not send the camera to the middle and back),
    2. a median of three kills single-frame outliers,
    3. a centred moving average of about a second smooths what is left,
    4. a deadband holds the camera still for movements too small to be
       intentional, and a speed limit keeps the pan watchable.
    """
    if not any(d.x is not None for d in detections):
        return []

    times = [d.t for d in detections]
    series = _fill_gaps([d.x for d in detections])
    series = _median3(series)
    series = _moving_average(series, window=max(3, int(round(fps))) | 1)
    series = _hold_still(series)
    series = _limit_speed(series, fps)
    return [(round(t, 3), round(x, 5)) for t, x in zip(times, series)]


def _fill_gaps(values: list[float | None]) -> list[float]:
    known = [(i, v) for i, v in enumerate(values) if v is not None]
    first, last = known[0], known[-1]
    filled: list[float] = []
    for index, value in enumerate(values):
        if value is not None:
            filled.append(float(value))
            continue
        if index < first[0]:
            filled.append(float(first[1]))
        elif index > last[0]:
            filled.append(float(last[1]))
        else:
            before = max(k for k in known if k[0] < index)
            after = min(k for k in known if k[0] > index)
            span = after[0] - before[0]
            ratio = (index - before[0]) / span
            filled.append(float(before[1]) + (float(after[1]) - float(before[1])) * ratio)
    return filled


def _median3(values: list[float]) -> list[float]:
    if len(values) < 3:
        return list(values)
    out = [values[0]]
    for a, b, c in zip(values, values[1:], values[2:]):
        out.append(float(np.median([a, b, c])))
    out.append(values[-1])
    return out


def _moving_average(values: list[float], window: int) -> list[float]:
    if window <= 1 or len(values) < 3:
        return list(values)
    half = window // 2
    out = []
    for index in range(len(values)):
        low = max(0, index - half)
        high = min(len(values), index + half + 1)
        out.append(float(np.mean(values[low:high])))
    return out


def _hold_still(values: list[float]) -> list[float]:
    """Ignore movement smaller than the deadband — a camera that never rests reads as broken."""
    out = [values[0]]
    for value in values[1:]:
        out.append(out[-1] if abs(value - out[-1]) < DEADBAND else value)
    return out


def _limit_speed(values: list[float], fps: float) -> list[float]:
    limit = MAX_SPEED / max(0.001, fps)
    out = [values[0]]
    for value in values[1:]:
        delta = max(-limit, min(limit, value - out[-1]))
        out.append(out[-1] + delta)
    return out


def plan(path: str, canvas_width: int, canvas_height: int, fps: float = SAMPLE_FPS) -> ReframePlan:
    """A reframe as `x` keyframes on the clip, plus the scale that fills the canvas.

    The arithmetic, once, so it can be checked:

    * the clip chain scales the picture to `scale × canvas_width`,
    * to fill a taller canvas we need `scale = canvas_height × source_aspect / canvas_width`,
    * a face at `fx` (0..1 across the source) sits at `canvas_width/2 + x·canvas_width
      + (fx − 0.5)·scale·canvas_width`, so centring it means `x = −(fx − 0.5)·scale`,
    * and `x` is clamped to ±(scale − 1)/2 so the frame never runs off the picture.
    """
    info = probe_media(path)
    source_w = int(info.get("width") or 0)
    source_h = int(info.get("height") or 0)
    if source_w <= 0 or source_h <= 0 or canvas_width <= 0 or canvas_height <= 0:
        return ReframePlan(fallback=True, reason="the file has no picture to reframe")

    source_aspect = source_w / source_h
    canvas_aspect = canvas_width / canvas_height
    # Fill the canvas: whichever dimension is short decides the scale.
    scale = max(1.0, source_aspect / canvas_aspect) if source_aspect > canvas_aspect else 1.0
    room = max(0.0, (scale - 1.0) / 2.0)

    if cv2 is None:
        return ReframePlan(scale=scale, keyframes=[{"t": 0.0, "x": 0.0}], fallback=True,
                           reason="OpenCV is not available, so the crop stays centred")

    if _cascade() is None:
        # A different failure from "no face in this clip", and it used to be
        # reported as "no frames could be read", which sent the reader looking
        # at the video file instead of at the OpenCV build.
        return ReframePlan(scale=scale, keyframes=[{"t": 0.0, "x": 0.0}], fallback=True,
                           reason="this OpenCV build ships no face detector — the crop stays centred")

    detections = detect_faces(path, fps=fps)
    found = [d for d in detections if d.x is not None]
    kinds = {d.kind for d in found}
    tracker = "face" if "face" in kinds else ("motion" if "motion" in kinds else "none")
    if not detections:
        return ReframePlan(scale=scale, keyframes=[{"t": 0.0, "x": 0.0}], fallback=True,
                           reason="no frames could be read")
    if not found:
        return ReframePlan(
            scale=scale, keyframes=[{"t": 0.0, "x": 0.0}], samples=len(detections),
            fallback=True, tracker="none",
            reason="no face and no movement to follow — the crop stays centred",
        )

    keyframes = [
        {"t": moment, "x": round(max(-room, min(room, -(fx - 0.5) * scale)), 5)}
        for moment, fx in smooth(detections, fps=fps)
    ]
    keyframes = _thin(keyframes)
    noun = "face" if tracker == "face" else "the moving subject"
    return ReframePlan(
        scale=scale, keyframes=keyframes, faces_found=len(found), samples=len(detections),
        fallback=False, tracker=tracker,
        reason=f"followed {noun} in {len(found)} of {len(detections)} sampled frames",
    )


def _thin(keyframes: list[dict], tolerance: float = 0.004) -> list[dict]:
    """Drop keys the eye cannot tell from the straight line through them.

    A four-per-second path over two minutes is 480 keys; the timeline would be
    unreadable and the exported expression enormous. Collinear points go.
    """
    if len(keyframes) < 3:
        return keyframes
    kept = [keyframes[0]]
    for previous, current, following in zip(keyframes, keyframes[1:], keyframes[2:]):
        span = following["t"] - previous["t"]
        if span <= 0:
            continue
        ratio = (current["t"] - previous["t"]) / span
        straight = previous["x"] + (following["x"] - previous["x"]) * ratio
        if abs(current["x"] - straight) > tolerance:
            kept.append(current)
    kept.append(keyframes[-1])
    return kept
