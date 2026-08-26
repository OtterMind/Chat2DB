"""Junction typing is a measurement; the bridge degrades like the others."""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app
from core.engine import transnet

client = TestClient(app)


def test_a_single_violent_jump_is_a_cut():
    diffs = [2, 3, 2, 90, 2, 3]
    luma = [80, 82, 81, 79, 80, 81]

    assert transnet.type_junction(diffs, luma) == "cut"


def test_a_ramp_of_similar_jumps_is_a_dissolve():
    diffs = [3, 14, 15, 16, 14, 3]
    luma = [80, 80, 80, 80, 80, 80]

    assert transnet.type_junction(diffs, luma) == "dissolve"


def test_a_dip_towards_black_is_a_fade():
    diffs = [10, 12, 10]
    luma = [70, 4, 65]

    assert transnet.type_junction(diffs, luma) == "fade"


def test_model_scene_rows_convert_to_seconds():
    rows = [(25, 75), (100, 150)]

    out = transnet._to_segments(rows, fps=25.0)

    assert out == [{"start": 1.0, "end": 3.0}, {"start": 4.0, "end": 6.0}]


def test_garbage_rows_are_dropped_not_crashed_on():
    rows = [(None, 5), (10,), "nope", (50, 100)]

    out = transnet._to_segments(rows, fps=25.0)

    assert out == [{"start": 2.0, "end": 4.0}]


def test_without_the_engine_the_bridge_says_so():
    if transnet.available():
        return  # on a fetched machine this is not the case under test

    assert transnet.detect.__doc__  # exists
    # the endpoint must 404 a missing file rather than blow up
    assert client.post("/api/engines/transnet/detect",
                       json={"path": "/nonexistent/x.mp4"}).status_code == 404


def test_transnet_module_name_matches_the_wheel():
    from core.engine import engines

    engine = next(e for e in engines.ENGINES if e["id"] == "transnet")
    # verified by unpacking the PyPI wheel: top-level package is
    # transnetv2_pytorch; a wrong name would make available() lie forever.
    assert engine["module"] == "transnetv2_pytorch"
