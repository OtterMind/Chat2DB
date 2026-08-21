"""The render engine must produce a real file with the right geometry and length."""
from __future__ import annotations

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
