"""The render engine must produce a real file with the right geometry and length."""
from __future__ import annotations

import pytest

from core.engine import compose
from tests.conftest import requires_ffmpeg


def _timeline(media) -> dict:
    return {
        "width": 1080, "height": 1920, "fps": 30,
        "tracks": [
            {"id": "v1", "kind": "video", "muted": False},
            {"id": "a1", "kind": "audio", "muted": False},
        ],
        "clips": [
            {"id": "c1", "trackId": "v1", "start": 0, "duration": 3, "offset": 1, "src": str(media["clip_a"])},
            {"id": "c2", "trackId": "v1", "start": 3.5, "duration": 2, "offset": 0, "src": str(media["clip_b"])},
            {"id": "c3", "trackId": "a1", "start": 0, "duration": 5.5, "offset": 0, "src": str(media["tone"])},
        ],
    }


def test_timeline_duration_is_the_last_clip_end(media):
    timeline = compose.Timeline.from_dict(_timeline(media))
    assert timeline.duration == 5.5


@requires_ffmpeg
def test_render_produces_playable_output(media, tmp_path):
    timeline = compose.Timeline.from_dict(_timeline(media))
    seen: list[float] = []
    output = compose.render(timeline, tmp_path / "out.mp4", on_progress=lambda p, s: seen.append(p))

    assert output.exists() and output.stat().st_size > 10_000
    info = compose.probe_media(str(output))
    assert (info["width"], info["height"]) == (1080, 1920)
    assert abs(info["duration"] - 5.5) < 0.4
    assert info["has_audio"] and info["has_video"]
    assert seen and seen[-1] == 100.0


@requires_ffmpeg
def test_video_without_audio_does_not_break_the_graph(media, tmp_path):
    """Regression: an audio branch for a silent source aborted the whole render."""
    timeline = compose.Timeline.from_dict({
        "width": 720, "height": 1280, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video", "muted": False}],
        "clips": [{"id": "c1", "trackId": "v1", "start": 0, "duration": 2, "offset": 0, "src": str(media["clip_a"])}],
    })
    output = compose.render(timeline, tmp_path / "silent.mp4")
    assert output.exists()
    assert compose.probe_media(str(output))["has_video"]


def test_empty_timeline_is_rejected():
    timeline = compose.Timeline.from_dict({"tracks": [], "clips": []})
    try:
        compose.build_command(timeline, __import__("pathlib").Path("/tmp/none.mp4"))
    except ValueError:
        return
    raise AssertionError("an empty timeline should not build a command")


def _clip(cid: str, src, start: float, duration: float, **props) -> dict:
    return {
        "id": cid, "trackId": "v1", "start": start, "duration": duration,
        "offset": 0, "src": str(src), "props": props or {},
    }


@requires_ffmpeg
def test_transition_overlaps_the_two_clips(media, tmp_path):
    """A 0.5s transition must shorten the result by exactly that much."""
    timeline = compose.Timeline.from_dict({
        "width": 480, "height": 854, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video"}],
        "clips": [
            _clip("a", media["clip_a"], 0, 3),
            _clip("b", media["clip_b"], 2.5, 3),
        ],
        "transitions": [
            {"id": "t1", "trackId": "v1", "fromClipId": "a", "toClipId": "b",
             "type": "wipeleft", "duration": 0.5}
        ],
    })
    command = compose.build_command(timeline, tmp_path / "x.mp4")
    graph = command[command.index("-filter_complex") + 1]
    assert "xfade=transition=wipeleft" in graph

    output = compose.render(timeline, tmp_path / "transition.mp4")
    assert abs(compose.probe_media(str(output))["duration"] - 5.5) < 0.4


def test_speed_defines_how_much_source_a_clip_consumes(media):
    timeline = compose.Timeline.from_dict({
        "tracks": [{"id": "v1", "kind": "video"}],
        "clips": [_clip("a", media["clip_a"], 0, 2, speed=2)],
    })
    clip = timeline.clips[0]
    assert clip.duration == 2          # two seconds on the timeline
    assert clip.source_window == 4     # consuming four seconds of source


@requires_ffmpeg
def test_clip_effects_render(media, tmp_path):
    timeline = compose.Timeline.from_dict({
        "width": 480, "height": 480, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video"}],
        "clips": [
            _clip("a", media["clip_a"], 0, 1, speed=2, opacity=0.5,
                  crop={"left": 0.1, "right": 0.1, "top": 0, "bottom": 0},
                  transform={"x": 0.05, "y": 0, "scale": 0.8, "rotate": 0}),
            _clip("b", media["clip_a"], 1.2, 1, reversed=True),
        ],
    })
    output = compose.render(timeline, tmp_path / "effects.mp4")
    info = compose.probe_media(str(output))
    assert (info["width"], info["height"]) == (480, 480)
    assert info["has_video"]


@requires_ffmpeg
def test_muted_clip_contributes_no_audio(media, tmp_path):
    timeline = compose.Timeline.from_dict({
        "width": 320, "height": 320, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video"}, {"id": "a1", "kind": "audio"}],
        "clips": [
            _clip("v", media["clip_a"], 0, 1),
            {"id": "m", "trackId": "a1", "start": 0, "duration": 1, "offset": 0,
             "src": str(media["tone"]), "props": {"muted": True}},
        ],
    })
    command = compose.build_command(timeline, tmp_path / "muted.mp4")
    assert "-an" in command


@requires_ffmpeg
@pytest.mark.parametrize("look", ["warm", "cool", "cinematic", "vivid", "bw", "sepia", "vintage", "matte", "night"])
def test_every_colour_look_renders(media, tmp_path, look):
    timeline = compose.Timeline.from_dict({
        "width": 320, "height": 320, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video"}],
        "clips": [_clip("a", media["clip_a"], 0, 1, filter=look)],
    })
    output = compose.render(timeline, tmp_path / f"{look}.mp4")
    assert compose.probe_media(str(output))["has_video"]


@requires_ffmpeg
@pytest.mark.parametrize("anim", ["fade", "zoomIn", "zoomOut"])
def test_animations_render(media, tmp_path, anim):
    """Regression: unescaped commas in a time expression broke the whole graph."""
    timeline = compose.Timeline.from_dict({
        "width": 320, "height": 568, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video"}],
        "clips": [_clip("a", media["clip_a"], 0, 2, animIn=anim, animOut=anim, animDuration=0.4)],
    })
    output = compose.render(timeline, tmp_path / f"{anim}.mp4")
    assert abs(compose.probe_media(str(output))["duration"] - 2) < 0.3


@requires_ffmpeg
def test_colour_adjustment_renders(media, tmp_path):
    timeline = compose.Timeline.from_dict({
        "width": 320, "height": 320, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video"}],
        "clips": [_clip("a", media["clip_a"], 0, 1, adjust={
            "brightness": 0.1, "contrast": 1.3, "saturation": 1.4,
            "temperature": 0.4, "sharpen": 0.6, "vignette": 0.5,
        })],
    })
    assert compose.render(timeline, tmp_path / "graded.mp4").exists()


@requires_ffmpeg
def test_audio_cleanup_renders(media, tmp_path):
    timeline = compose.Timeline.from_dict({
        "width": 320, "height": 320, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video"}, {"id": "a1", "kind": "audio"}],
        "clips": [
            _clip("v", media["clip_a"], 0, 2),
            {"id": "a", "trackId": "a1", "start": 0, "duration": 2, "offset": 0,
             "src": str(media["tone"]), "props": {"denoise": 0.7, "enhanceVoice": True}},
        ],
    })
    output = compose.render(timeline, tmp_path / "audio.mp4")
    assert compose.probe_media(str(output))["has_audio"]
