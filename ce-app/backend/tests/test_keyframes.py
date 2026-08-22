"""Keyframes must animate the export exactly the way the monitor shows them.

Every check here measures the rendered file — picture brightness as the clip
grows, audio level as the volume ramps, and the shape of the generated FFmpeg
expression — because a filter that is silently dropped still produces a valid
video, which is precisely how a fake keyframe would hide.
"""
from __future__ import annotations

import subprocess

from core.engine import compose
from core.engine.compose import keyframe_expression
from tests.conftest import requires_ffmpeg


def _y_average(path, at: float) -> float:
    out = subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-nostats", "-y",
            "-ss", f"{at}", "-i", str(path), "-vframes", "1",
            "-vf", "signalstats,metadata=print:file=-", "-f", "null", "-",
        ],
        capture_output=True, text=True, check=True,
    )
    for line in (out.stdout + out.stderr).splitlines():
        if "YAVG" in line:
            return float(line.split("=")[-1])
    raise AssertionError("no YAVG in signalstats output")


def _frame_difference(first, second, at: float, at_second: float | None = None) -> float:
    """Mean luma difference between two frames — 0 means identical."""
    out = subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-nostats", "-y",
            "-ss", f"{at}", "-i", str(first),
            "-ss", f"{at if at_second is None else at_second}", "-i", str(second),
            "-filter_complex",
            "[0:v]format=gray[a];[1:v]format=gray[b];[a][b]blend=all_mode=difference,"
            "signalstats,metadata=print:file=-",
            "-frames:v", "1", "-f", "null", "-",
        ],
        capture_output=True, text=True, check=True,
    )
    for line in (out.stdout + out.stderr).splitlines():
        if "YAVG" in line:
            return float(line.split("=")[-1])
    raise AssertionError("no YAVG in the difference output")


def _mean_db(path, start: float, end: float) -> float:
    out = subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-nostats", "-y",
            "-ss", f"{start}", "-t", f"{end - start}", "-i", str(path),
            "-af", "volumedetect", "-f", "null", "-",
        ],
        capture_output=True, text=True, check=True,
    )
    for line in out.stderr.splitlines():
        if "mean_volume" in line:
            return float(line.split(":")[-1].replace("dB", "").strip())
    raise AssertionError("no mean_volume in volumedetect output")


# ----------------------------------------------------------------- the maths


def test_a_static_clip_produces_no_expression():
    assert keyframe_expression([], "scale", 1.0) is None
    assert keyframe_expression([{"t": 0, "x": 0.2}], "scale", 1.0) is None


def test_one_key_is_a_constant():
    assert keyframe_expression([{"t": 1.0, "scale": 0.5}], "scale", 1.0) == "0.500000"


def test_the_expression_holds_then_ramps_then_holds():
    expression = keyframe_expression([{"t": 0.0, "x": 0.0}, {"t": 2.0, "x": 1.0}], "x", 0.0)
    assert expression is not None
    # Piecewise: a comparison per segment, and every comma escaped — an
    # unescaped one ends the filter and truncates the whole graph.
    assert expression.count("if(") == 2
    assert "," not in expression.replace("\\,", "")


def test_the_expression_evaluates_the_way_the_editor_samples(tmp_path):
    """Ground truth: ask FFmpeg itself what the expression is worth over time."""
    expression = keyframe_expression(
        [{"t": 0.0, "volume": 0.0}, {"t": 2.0, "volume": 1.0}], "volume", 1.0
    )
    # A silent-source volume ramp, measured in two halves.
    output = tmp_path / "ramp.wav"
    subprocess.run(
        [
            compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-af", f"volume=volume='{expression}':eval=frame", str(output),
        ],
        check=True,
    )
    first = _mean_db(output, 0.0, 0.9)
    second = _mean_db(output, 1.1, 2.0)
    assert second > first + 5          # the ramp really rises


# --------------------------------------------------------------- the renders


def _timeline(media, keyframes: list[dict], audio_keyframes: list[dict] | None = None) -> dict:
    clips = [{
        "id": "c1", "trackId": "v1", "start": 0, "duration": 3, "offset": 0,
        "src": str(media["clip_b"]),
        "props": {"keyframes": keyframes},
    }]
    tracks = [{"id": "v1", "kind": "video"}]
    if audio_keyframes is not None:
        tracks.append({"id": "a1", "kind": "audio"})
        clips.append({
            "id": "c2", "trackId": "a1", "start": 0, "duration": 3, "offset": 0,
            "src": str(media["tone"]), "props": {"keyframes": audio_keyframes},
        })
    return {"width": 320, "height": 240, "fps": 15, "tracks": tracks, "clips": clips}


@requires_ffmpeg
def test_scale_and_position_keyframes_move_the_picture(media, tmp_path):
    timeline = compose.Timeline.from_dict(
        _timeline(media, [
            {"t": 0.0, "scale": 0.35, "x": -0.25},
            {"t": 3.0, "scale": 1.00, "x": 0.25},
        ])
    )
    output = compose.render(timeline, tmp_path / "grow.mp4")

    early = _y_average(output, 0.2)
    late = _y_average(output, 2.8)
    # A small picture on a black canvas is dark; a full one is bright.
    assert late > early * 2

    command = " ".join(compose.build_command(timeline, tmp_path / "x.mp4"))
    assert "eval=frame" in command and "overlay=x=" in command


@requires_ffmpeg
def test_rotation_keyframes_turn_the_picture(media, tmp_path):
    still = compose.render(
        compose.Timeline.from_dict(_timeline(media, [])), tmp_path / "still.mp4"
    )
    turning = compose.render(
        compose.Timeline.from_dict(
            _timeline(media, [{"t": 0.0, "rotate": 0.0}, {"t": 3.0, "rotate": 90.0}])
        ),
        tmp_path / "turn.mp4",
    )
    # The source is a still test pattern, so any change over time is the
    # rotation and nothing else: the untouched clip stays identical to itself,
    # the keyframed one does not.
    assert _frame_difference(still, still, 0.1, 2.8) < 2
    assert _frame_difference(turning, turning, 0.1, 2.8) > 15


@requires_ffmpeg
def test_volume_keyframes_ramp_the_sound(media, tmp_path):
    timeline = compose.Timeline.from_dict(
        _timeline(media, [], audio_keyframes=[{"t": 0.0, "volume": 0.05}, {"t": 3.0, "volume": 1.0}])
    )
    output = compose.render(timeline, tmp_path / "ramp.mp4")
    assert _mean_db(output, 0.0, 1.0) + 6 < _mean_db(output, 2.0, 3.0)


@requires_ffmpeg
def test_a_clip_without_keyframes_keeps_the_simple_chain(media, tmp_path):
    """The fast path must stay fast: no canvas, no per-frame evaluation."""
    command = " ".join(
        compose.build_command(compose.Timeline.from_dict(_timeline(media, [])), tmp_path / "plain.mp4")
    )
    assert "eval=frame" not in command
    assert "color=c=black@0" not in command
