"""Editing proxies must be small, fast to seek — and never reach the export."""
from __future__ import annotations

from pathlib import Path

from core.engine import compose, proxy
from tests.conftest import requires_ffmpeg


def _make_big(tmp_path: Path) -> Path:
    """A 2560×1440 clip: above the proxy threshold, cheap to encode."""
    target = tmp_path / "big.mp4"
    compose_bin = compose.ffmpeg_binary()
    import subprocess

    subprocess.run(
        [
            compose_bin, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=2560x1440:rate=25:duration=2",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(target),
        ],
        check=True,
    )
    return target


def test_only_large_footage_gets_a_proxy():
    assert proxy.needs_proxy({"has_video": True, "width": 3840, "height": 2160})
    assert proxy.needs_proxy({"has_video": True, "width": 1080, "height": 1920})   # portrait 1080p
    assert not proxy.needs_proxy({"has_video": True, "width": 1280, "height": 720})
    assert not proxy.needs_proxy({"has_video": False, "width": 0, "height": 0})    # audio only


def test_the_name_changes_when_the_file_changes(tmp_path):
    source = tmp_path / "a.mp4"
    source.write_bytes(b"one")
    first = proxy.proxy_path(source)
    source.write_bytes(b"two different bytes")
    assert proxy.proxy_path(source) != first          # edited file, new proxy


@requires_ffmpeg
def test_proxy_is_small_720p_and_seekable(tmp_path):
    source = _make_big(tmp_path)
    state = proxy.build_now(str(source))

    assert state.status == "ready" and state.proxy
    built = Path(state.proxy)
    assert built.exists()

    info = compose.probe_media(str(built))
    assert info["height"] == proxy.PROXY_HEIGHT
    assert abs(info["duration"] - 2.0) < 0.3
    assert built.stat().st_size < source.stat().st_size

    # A keyframe at least twice a second is what makes scrubbing feel instant.
    command = proxy.build_command(source, built)
    assert "-g" in command and command[command.index("-g") + 1] == "15"

    # Asking again must reuse the file instead of encoding it twice.
    again = proxy.build_now(str(source))
    assert again.proxy == state.proxy


@requires_ffmpeg
def test_the_export_never_reads_a_proxy(media, tmp_path):
    """A proxy on a clip must not change a single argument of the render."""
    timeline = {
        "width": 640, "height": 360, "fps": 25,
        "tracks": [{"id": "v1", "kind": "video", "muted": False}],
        "clips": [{
            "id": "c1", "trackId": "v1", "start": 0, "duration": 1, "offset": 0,
            "src": str(media["clip_a"]),
            "proxy": "/somewhere/else/proxy.mp4",
        }],
    }
    command = " ".join(compose.build_command(compose.Timeline.from_dict(timeline), tmp_path / "out.mp4"))
    assert str(media["clip_a"]) in command
    assert "proxy.mp4" not in command
