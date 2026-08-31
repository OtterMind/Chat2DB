"""The BlazeFace bridge: geometry is testable without MediaPipe, and the bridge
must degrade to "not available" on a machine without the package."""
from __future__ import annotations

import importlib.util

from core.engine import face


def test_relative_box_to_centre():
    x, y, size = face.centre_from_box({"xmin": 0.2, "ymin": 0.3, "width": 0.4, "height": 0.5})
    assert x == 0.4 and y == 0.55
    assert size == 0.4


def test_tiny_box_gets_a_floor_size():
    _, _, size = face.centre_from_box({"xmin": 0, "ymin": 0, "width": 0.0, "height": 0.1})
    assert size == 0.02  # a degenerate box still yields a sane subject size


def test_available_reflects_the_package():
    assert face.available() is (importlib.util.find_spec("mediapipe") is not None)


def test_detector_without_mediapipe_is_broken_safe(monkeypatch):
    monkeypatch.setattr(face, "available", lambda: False)
    # When the package is absent the caller must not build a detector at all;
    # and a detector whose build fails never raises, it reports None per frame.
    det = face.FaceDetector()
    det._broken = True
    assert det.detect(object()) is None
