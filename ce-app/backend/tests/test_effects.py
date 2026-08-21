"""Every effect in the tool rail must reach the exported pixels.

The user reported that opacity, transform, freeze, rotate, filters, animations,
adjustments and transitions "do nothing". These tests render tiny timelines and
measure the frames, because a filter that is silently dropped still produces a
perfectly valid file — that is exactly how the bug hid.
"""
from __future__ import annotations

import json
import subprocess

import pytest

from core.engine import compose
from tests.conftest import requires_ffmpeg


def _stats(path, at: float = 0.5) -> dict[str, float]:
    """Average luma and saturation of one frame, via ffmpeg's signalstats."""
    out = subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-nostats", "-y",
            "-ss", f"{at}", "-i", str(path), "-vframes", "1",
            "-vf", "signalstats,metadata=print:file=-",
            "-f", "null", "-",
        ],
        capture_output=True, text=True, check=True,
    )
    values: dict[str, float] = {}
    for line in (out.stdout + out.stderr).splitlines():
        if "lavfi.signalstats." in line:
            key, _, value = line.partition("=")
            name = key.strip().split("lavfi.signalstats.")[-1]
            try:
                values[name] = float(value)
            except ValueError:
                pass
    return values


def _timeline(media, props: dict | None = None, **overrides) -> dict:
    clip = {
        "id": "c1", "trackId": "v1", "start": 0, "duration": 2, "offset": 0,
        "src": str(media["clip_b"]),          # colour bars: a strong colour signal
    }
    if props:
        clip["props"] = props
    data = {
        "width": 320, "height": 240, "fps": 15,
        "tracks": [{"id": "v1", "kind": "video", "muted": False}],
        "clips": [clip],
    }
    data.update(overrides)
    return data


def _render(data: dict, tmp_path, name: str):
    return compose.render(compose.Timeline.from_dict(data), tmp_path / name)


@requires_ffmpeg
def test_black_and_white_look_removes_colour(media, tmp_path):
    plain = _stats(_render(_timeline(media), tmp_path, "plain.mp4"))
    bw = _stats(_render(_timeline(media, {"filter": "bw"}), tmp_path, "bw.mp4"))
    # Colour bars are heavily saturated; the look must flatten U and V to neutral.
    assert plain["SATAVG"] > 20
    assert bw["SATAVG"] < plain["SATAVG"] / 3


@requires_ffmpeg
def test_opacity_darkens_against_the_canvas(media, tmp_path):
    solid = _stats(_render(_timeline(media), tmp_path, "solid.mp4"))
    faded = _stats(_render(_timeline(media, {"opacity": 0.35}), tmp_path, "faded.mp4"))
    # The canvas underneath is black, so a half-transparent clip must be darker.
    assert faded["YAVG"] < solid["YAVG"] * 0.75


@requires_ffmpeg
def test_adjustments_change_brightness_and_saturation(media, tmp_path):
    plain = _stats(_render(_timeline(media), tmp_path, "adj_plain.mp4"))
    bright = _stats(
        _render(
            _timeline(media, {"adjust": {"brightness": 0.3, "contrast": 1.0, "saturation": 0.2}}),
            tmp_path, "adj_bright.mp4",
        )
    )
    assert bright["YAVG"] > plain["YAVG"] + 10
    assert bright["SATAVG"] < plain["SATAVG"]


@requires_ffmpeg
def test_transform_scale_leaves_canvas_around_the_picture(media, tmp_path):
    plain = _stats(_render(_timeline(media), tmp_path, "tr_plain.mp4"))
    small = _stats(
        _render(
            _timeline(media, {"transform": {"x": 0, "y": 0, "scale": 0.4, "rotate": 0}}),
            tmp_path, "tr_small.mp4",
        )
    )
    # Shrinking the picture pads the rest of the frame with black.
    assert small["YAVG"] < plain["YAVG"] * 0.6


@requires_ffmpeg
def test_rotation_changes_the_frame(media, tmp_path):
    plain = _stats(_render(_timeline(media), tmp_path, "rot_plain.mp4"))
    turned = _stats(
        _render(
            _timeline(media, {"transform": {"x": 0, "y": 0, "scale": 1, "rotate": 90}}),
            tmp_path, "rot_90.mp4",
        )
    )
    assert abs(turned["YAVG"] - plain["YAVG"]) > 2


@requires_ffmpeg
def test_crop_keeps_only_the_requested_region(media, tmp_path):
    plain = _stats(_render(_timeline(media), tmp_path, "crop_plain.mp4"))
    cropped = _stats(
        _render(
            _timeline(media, {"crop": {"left": 0.0, "top": 0.0, "right": 0.6, "bottom": 0.0}}),
            tmp_path, "crop_left.mp4",
        )
    )
    # Colour bars are not uniform, so cropping to the left 40% must move the average.
    assert abs(cropped["YAVG"] - plain["YAVG"]) > 3


@requires_ffmpeg
def test_fade_animation_starts_dark(media, tmp_path):
    output = _render(
        _timeline(media, {"animIn": "fade", "animDuration": 1.0}), tmp_path, "anim_fade.mp4"
    )
    early = _stats(output, at=0.1)
    late = _stats(output, at=1.6)
    assert early["YAVG"] < late["YAVG"] * 0.6


@requires_ffmpeg
def test_freeze_holds_a_single_frame(media, tmp_path):
    """A freeze is speed ≈ 0; every frame of the output must be identical."""
    frozen = _render(
        _timeline(media, {"speed": 0.0001}), tmp_path, "freeze.mp4"
    )
    first = _stats(frozen, at=0.2)
    last = _stats(frozen, at=1.6)
    assert abs(first["YAVG"] - last["YAVG"]) < 0.5
    assert abs(first["SATAVG"] - last["SATAVG"]) < 0.5


@requires_ffmpeg
def test_transition_blends_the_two_clips(media, tmp_path):
    """Halfway through an xfade the frame must be neither clip on its own."""
    data = {
        "width": 320, "height": 240, "fps": 15,
        "tracks": [{"id": "v1", "kind": "video", "muted": False}],
        "clips": [
            {"id": "c1", "trackId": "v1", "start": 0, "duration": 2, "offset": 0, "src": str(media["clip_a"])},
            {"id": "c2", "trackId": "v1", "start": 1.5, "duration": 2, "offset": 0, "src": str(media["clip_b"])},
        ],
        "transitions": [
            {"id": "t1", "trackId": "v1", "fromClipId": "c1", "toClipId": "c2", "type": "fade", "duration": 0.5}
        ],
    }
    output = _render(data, tmp_path, "xfade.mp4")
    command = " ".join(compose.build_command(compose.Timeline.from_dict(data), tmp_path / "x.mp4"))
    assert "xfade" in command and "transition=fade" in command

    before = _stats(output, at=0.5)["YAVG"]
    middle = _stats(output, at=1.75)["YAVG"]
    after = _stats(output, at=2.8)["YAVG"]
    # The blend has to sit between the two sources, not equal either of them.
    low, high = sorted((before, after))
    assert low - 1 <= middle <= high + 1
    assert abs(middle - before) > 0.5 and abs(middle - after) > 0.5


@requires_ffmpeg
def test_speed_shortens_the_clip(media, tmp_path):
    output = _render(_timeline(media, {"speed": 2.0}), tmp_path, "speed.mp4")
    info = compose.probe_media(str(output))
    assert abs(info["duration"] - 2.0) < 0.35   # the clip still occupies its slot
    command = " ".join(compose.build_command(compose.Timeline.from_dict(_timeline(media, {"speed": 2.0})), tmp_path / "s.mp4"))
    assert "setpts=PTS/2" in command


@requires_ffmpeg
def test_thumbnail_endpoint_returns_a_frame_and_caches_it(media):
    """The timeline film strip depends on this: a real JPEG, then a cache hit."""
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as client:
        source = str(media["clip_b"])
        first = client.get("/api/media/thumb", params={"path": source, "t": 1.0, "h": 64})
        assert first.status_code == 200
        assert first.headers["content-type"] == "image/jpeg"
        assert len(first.content) > 500                     # a real image, not an empty file

        second = client.get("/api/media/thumb", params={"path": source, "t": 1.0, "h": 64})
        assert second.status_code == 200
        assert second.content == first.content              # served from the cache

        assert client.get("/api/media/thumb", params={"path": "/no/such/file.mp4"}).status_code == 404


@requires_ffmpeg
def test_mute_silences_a_lane_but_keeps_the_picture(media, tmp_path):
    """Regression: muting the video lane used to blank the monitor entirely."""
    data = {
        "width": 320, "height": 240, "fps": 15,
        "tracks": [{"id": "v1", "kind": "video", "muted": True}],
        "clips": [{
            "id": "c1", "trackId": "v1", "start": 0, "duration": 1, "offset": 0,
            "src": str(media["clip_b"]),
        }],
    }
    output = _render(data, tmp_path, "muted_lane.mp4")
    info = compose.probe_media(str(output))
    assert info["has_video"]                      # the picture survives a mute
    assert _stats(output, at=0.3)["YAVG"] > 20    # …and it is not a black frame


@requires_ffmpeg
def test_hiding_a_lane_removes_the_picture(media, tmp_path):
    data = {
        "width": 320, "height": 240, "fps": 15,
        "tracks": [{"id": "v1", "kind": "video", "hidden": True}],
        "clips": [{
            "id": "c1", "trackId": "v1", "start": 0, "duration": 1, "offset": 0,
            "src": str(media["clip_b"]),
        }],
    }
    output = _render(data, tmp_path, "hidden_lane.mp4")
    # Nothing but the canvas is left, so the frame is black.
    assert _stats(output, at=0.3)["YAVG"] < 20
