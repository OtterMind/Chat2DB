"""Auto-reframe: does the frame actually follow the face, and does it do it calmly?

The fixture is built to a recipe, so there is a right answer: a real photograph
of a face is composited onto a wide canvas and moved along a known path. The
test then asks the two questions that decide whether this feature is real:

1. does the camera end up where the face is (measured in pixels of error), and
2. does it move smoothly enough that a person would not call it broken?

Until now `Face Tracking` was a centre crop with a BETA badge.
"""
from __future__ import annotations

import subprocess
from pathlib import Path

import numpy as np
import pytest

from core.engine import compose, reframe
from tests.conftest import requires_ffmpeg

FACE = Path(__file__).resolve().parents[3] / "tests" / "assets" / "face.jpg"
requires_face = pytest.mark.skipif(not FACE.exists(), reason="face fixture missing")
requires_cv2 = pytest.mark.skipif(reframe.cv2 is None, reason="OpenCV not installed")


def _run(args: list[str]) -> None:
    subprocess.run([compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


@pytest.fixture(scope="module")
def face_crop(tmp_path_factory):
    """The photograph, cropped so the face really is in the middle of it.

    The first version of this fixture overlaid the whole portrait and assumed
    the face sat at its centre. It does not — it sits 10 % to the right — and
    the test duly "found" a constant 0.058 error that was in the *fixture*, not
    in the detector. A known answer has to be known.
    """
    import cv2

    image = cv2.imread(str(FACE))
    grey = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    classifier = cv2.CascadeClassifier(
        str(Path(cv2.data.haarcascades) / "haarcascade_frontalface_default.xml")
    )
    faces = classifier.detectMultiScale(grey, 1.1, 5, minSize=(40, 40))
    assert len(faces) > 0, "the fixture photograph has no detectable face"
    x, y, w, h = max(faces, key=lambda f: f[2] * f[3])

    # A square box around the face, centred on it, clipped to the picture.
    cx, cy = x + w / 2, y + h / 2
    half = min(w, h)
    left = int(max(0, min(image.shape[1] - 2 * half, cx - half)))
    top = int(max(0, min(image.shape[0] - 2 * half, cy - half)))
    crop = image[top : top + 2 * half, left : left + 2 * half]

    target = tmp_path_factory.mktemp("face") / "face.png"
    cv2.imwrite(str(target), crop)
    return target


@pytest.fixture(scope="module")
def moving_face(tmp_path_factory, face_crop):
    """1280×720, six seconds, a face travelling left → right on a known line.

    x(t) goes from 0.2 to 0.8 of the width, so the answer is known at every
    moment and the error can be measured rather than eyeballed.
    """
    base = tmp_path_factory.mktemp("reframe")
    target = base / "moving.mp4"
    _run([
        "-f", "lavfi", "-i", "color=c=gray:s=1280x720:r=25:d=6",
        "-loop", "1", "-i", str(face_crop),
        "-filter_complex",
        "[1:v]scale=-1:420[f];[0:v][f]overlay=x='(0.2+0.1*t)*W-w/2':y=(H-h)/2:shortest=1",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(target),
    ])
    return target


@pytest.fixture(scope="module")
def faceless(tmp_path_factory):
    base = tmp_path_factory.mktemp("reframe_none")
    target = base / "empty.mp4"
    _run(["-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=3",
          "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(target)])
    return target


@requires_ffmpeg
@requires_cv2
@requires_face
def test_the_face_is_found_where_it_really_is(moving_face):
    detections = reframe.detect_faces(str(moving_face))
    found = [d for d in detections if d.x is not None]

    assert len(found) >= len(detections) * 0.6, "the face was lost more often than not"

    errors = [abs(d.x - (0.2 + 0.1 * d.t)) for d in found]
    worst = max(errors)
    assert worst < 0.03, f"detection drifts from the known path by {worst:.3f} of the width"


@requires_ffmpeg
@requires_cv2
@requires_face
def test_the_camera_ends_up_on_the_face(moving_face):
    """The measurement that matters: how many pixels off centre is the subject?"""
    plan = reframe.plan(str(moving_face), canvas_width=1080, canvas_height=1920)

    assert not plan.fallback, plan.reason
    assert plan.coverage > 0.6
    # 16:9 into 9:16 needs the picture 3.16× the canvas width.
    assert 3.0 < plan.scale < 3.4

    worst = 0.0
    for key in plan.keyframes:
        true_face = 0.2 + 0.1 * key["t"]
        # Where the face lands on the canvas, in canvas widths from its centre.
        offset = key["x"] + (true_face - 0.5) * plan.scale
        worst = max(worst, abs(offset) * 1080)
    assert worst < 200, f"the subject sits {worst:.0f} px off centre at worst"

    centred = reframe.plan(str(moving_face), 1080, 1920)
    still = max(abs((0.2 + 0.1 * k["t"] - 0.5) * centred.scale) * 1080 for k in centred.keyframes)
    assert worst < still, "following the face is no better than not following it"


@requires_ffmpeg
@requires_cv2
@requires_face
def test_the_camera_move_is_smooth(moving_face):
    plan = reframe.plan(str(moving_face), 1080, 1920)
    keys = plan.keyframes

    speeds = [
        abs(b["x"] - a["x"]) / max(1e-6, b["t"] - a["t"])
        for a, b in zip(keys, keys[1:])
    ]
    assert max(speeds) <= reframe.MAX_SPEED + 1e-6, f"the camera jumps at {max(speeds):.2f}/s"


@requires_ffmpeg
@requires_cv2
def test_no_face_and_no_motion_means_an_honest_centre_crop(tmp_path):
    """A *still* frame with no face has nothing to follow: centred, not faked.

    (A moving faceless frame is followed by the motion fallback — see the moving
    subject test. testsrc2, the old fixture here, moves, so it no longer belongs
    in this test.)
    """
    target = tmp_path / "still.mp4"
    _run(["-f", "lavfi", "-i", "color=c=black:s=320x240:rate=25:duration=2",
          "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(target)])

    plan = reframe.plan(str(target), 1080, 1920)

    assert plan.fallback is True
    assert plan.keyframes == [{"t": 0.0, "x": 0.0}]
    assert "no face" in plan.reason


def test_the_path_is_thinned_but_keeps_its_shape():
    raw = [{"t": i * 0.25, "x": round(i * 0.01, 5)} for i in range(40)]
    thinned = reframe._thin(raw)

    assert len(thinned) < len(raw) / 4, "a straight line should collapse to its ends"
    assert thinned[0] == raw[0] and thinned[-1] == raw[-1]


def test_a_deadband_ignores_a_wobble():
    detections = [
        reframe.Detection(t=i * 0.25, x=0.5 + (0.005 if i % 2 else -0.005))
        for i in range(20)
    ]
    path = reframe.smooth(detections)
    spread = max(x for _, x in path) - min(x for _, x in path)

    assert spread < 0.01, f"a 1 % wobble moved the camera by {spread:.3f}"


@requires_ffmpeg
def test_a_moving_subject_is_followed_when_there_is_no_face(tmp_path):
    """A rally has no face to the Haar cascade; the moving region is the subject.

    The fixture is a white block sweeping a black frame — nothing a face detector
    will ever claim, but exactly the kind of moving bulk a sport puts in frame.
    """
    import subprocess

    from core.engine import compose, reframe

    mover = tmp_path / "mover.mp4"
    subprocess.run([
        compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=black:s=320x240:rate=25:duration=4",
        "-f", "lavfi", "-i", "color=c=white:s=40x80:rate=25:duration=4",
        "-filter_complex", "[0][1]overlay=eval=frame:x='20+220*t/4':y=80",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(mover),
    ], check=True)

    plan = reframe.plan(str(mover), 1080, 1920)

    assert plan.tracker == "motion", f"expected the motion fallback, got {plan.tracker}"
    assert not plan.fallback
    xs = [k["x"] for k in plan.keyframes]
    assert max(xs) - min(xs) > 0.3, "the camera did not actually follow the movement"


@requires_ffmpeg
def test_a_still_frame_with_no_face_stays_centred(tmp_path):
    """No face and no movement is an answer of 'centred', not an error."""
    import subprocess

    from core.engine import compose, reframe

    still = tmp_path / "still.mp4"
    subprocess.run([
        compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=black:s=320x240:rate=25:duration=2",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(still),
    ], check=True)

    plan = reframe.plan(str(still), 1080, 1920)

    assert plan.tracker == "none"
    assert plan.fallback
    assert [k["x"] for k in plan.keyframes] == [0.0]
