"""Nothing may require a graphics card — not one path, not one endpoint.

Hardware acceleration arrived late, and it arrived as an *optimisation*: a card
makes proxies and exports faster, and Whisper more accurate. The failure mode to
guard against is the quiet one, where a machine without a card gets a command
FFmpeg refuses, an endpoint that 500s, or a Whisper that reaches for cuBLAS and
dies (`cublas64_12.dll is not found`, STATE.md §4.13).

Every assertion here is written to hold on **both** kinds of machine. That is
deliberate: a test that only passes where there is no card is a test that stops
protecting the machine it was written on the day someone installs one — the same
mistake as `test_ai.py` hard-coding `whisper.installed is False` (§4.39).
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from core.engine import compose, gpu
from tests.conftest import requires_ffmpeg

client = TestClient(app)


def test_the_encoder_is_always_something_that_exists():
    """`encode_args()` must name a real encoder on every machine."""
    args = gpu.encode_args()
    assert "-c:v" in args, "no video encoder in the arguments at all"

    codec = args[args.index("-c:v") + 1]
    if gpu.best_encoder() is None:
        assert codec == "libx264", (
            f"no hardware encoder was usable but the command asked for {codec}"
        )
    else:
        assert codec != "libx264", "a working card was ignored"


def test_decode_args_never_name_a_backend_that_failed():
    """The `-hwaccel` flag goes before `-i`, and only when a decoder answered."""
    args = gpu.decode_args()

    if not gpu.can_decode():
        assert args == [], f"no decoder works, yet the command carries {args}"
    else:
        assert args[0] == "-hwaccel" and args[1] == gpu.best_decoder()


def test_the_compositor_does_not_grep_for_nvenc_any_more():
    """The card is asked to encode, not asked whether it is on a list."""
    assert compose._has_nvenc(compose.ffmpeg_binary()) == gpu.can_encode()


def test_whisper_never_claims_a_device_it_cannot_use():
    device, detail = gpu.whisper_status()

    assert device in ("cpu", "cuda", "auto", ""), f"unknown device {device!r}"
    if not gpu.nvidia_smi().get("present"):
        assert device != "cuda", "no NVIDIA card, yet Whisper was promised CUDA"
    assert isinstance(detail, str)


# ------------------------------------------------------------------ the doors


def test_the_gpu_endpoints_answer_on_a_machine_with_no_card():
    """A 500 here is worse than "no card": the settings screen goes blank."""
    status = client.get("/api/gpu/status")
    assert status.status_code == 200
    body = status.json()
    assert body["encoder"], "the screen cannot show an empty encoder"
    assert body["whisperDevice"]

    preference = client.get("/api/gpu/preference")
    assert preference.status_code == 200
    assert "supported" in preference.json()

    cuda = client.get("/api/ai/cuda/status")
    assert cuda.status_code == 200
    assert cuda.json()["device"]


def test_asking_for_the_windows_permission_is_not_an_error_elsewhere():
    """The permission button must not fail on a machine that is not Windows."""
    posted = client.post("/api/gpu/preference", json={})

    assert posted.status_code == 200
    assert "supported" in posted.json()


def test_a_gigabyte_of_cuda_is_refused_where_it_would_do_nothing():
    """§4.57: downloading CUDA to a machine that cannot use it is not a favour."""
    if gpu.nvidia_smi().get("present"):
        pytest.skip("this machine has an NVIDIA card, so the install is legitimate")

    refused = client.post("/api/ai/cuda/install")

    assert refused.status_code == 409
    assert "NVIDIA" in refused.json()["detail"]


# ------------------------------------------------------------- the real proof


@requires_ffmpeg
def test_a_render_completes_on_whatever_this_machine_is(media, tmp_path):
    """The whole point: the export works with or without a card.

    This is the assertion that would have caught a hard-coded `-hwaccel cuda` or
    an NVENC-only encoder list, and it runs on the machine it is run on rather
    than pretending to know what that machine has.
    """
    timeline = compose.Timeline.from_dict({
        "width": 640, "height": 360, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video", "muted": False}],
        "clips": [
            {"id": "c1", "trackId": "v1", "start": 0, "duration": 2,
             "offset": 0, "src": str(media["clip_a"])},
        ],
    })
    output = compose.render(timeline, tmp_path / "no-card.mp4")

    assert output.exists() and output.stat().st_size > 5_000
    info = compose.probe_media(str(output))
    assert info["has_video"] and abs(info["duration"] - 2.0) < 0.4


@requires_ffmpeg
def test_the_render_command_never_carries_a_flag_this_machine_refuses(media, tmp_path):
    """Read the actual command, not the intention behind it."""
    timeline = compose.Timeline.from_dict({
        "width": 640, "height": 360, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video", "muted": False}],
        "clips": [
            {"id": "c1", "trackId": "v1", "start": 0, "duration": 2,
             "offset": 0, "src": str(media["clip_a"])},
        ],
    })
    command = " ".join(compose.build_command(timeline, tmp_path / "x.mp4"))

    if not gpu.can_encode():
        assert "nvenc" not in command and "_qsv" not in command and "_amf" not in command, (
            "the command asks a card that refused the probe"
        )
    assert "-hwaccel" not in command or gpu.can_decode()
