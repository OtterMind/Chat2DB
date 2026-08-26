"""The sports pass: action peaks, subject presence, and the slow-mo beat.

The owner films volleyball, gym sets and jump rope — footage whose interesting
moments are *bursts* (a spike, a rep, a jump) with no speech and often no face.
These pin the three measurements that make a sports highlight land on the burst
instead of on the loudest or earliest window, using fixtures built to a recipe so
the right answer is known in advance.
"""
from __future__ import annotations

import subprocess

import pytest

from core.engine import compose, style
from tests.conftest import requires_ffmpeg


def _run(args: list[str]) -> None:
    subprocess.run([compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


@pytest.fixture(scope="module")
def burst(tmp_path_factory):
    """Two seconds still, a one-second violent sweep, two seconds still."""
    folder = tmp_path_factory.mktemp("burst")
    target = folder / "burst.mp4"
    _run([
        "-f", "lavfi", "-i", "color=c=black:s=320x240:rate=25:duration=5",
        "-f", "lavfi", "-i", "color=c=white:s=60x120:rate=25:duration=5",
        "-filter_complex", "[0][1]overlay=eval=frame:x='if(between(t,2,3), (t-2)*260, -200)':y=60",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(target),
    ])
    return target


@requires_ffmpeg
def test_the_burst_is_the_peak_and_the_rest_is_not_present(burst):
    peak_in, presence_in = style._action_profile(str(burst), 2.0, 1.0)
    peak_out, presence_out = style._action_profile(str(burst), 3.5, 1.0)

    assert peak_in > peak_out, "the sweep window must be the action peak"
    assert presence_in > presence_out, "the moving window must read as occupied"


@requires_ffmpeg
def test_sport_intent_weights_action_and_presence():
    from core.engine.intent import Intent

    weights = Intent.from_dict({"kind": "sport"}).signal_weights()

    assert weights["action"] >= 0.9
    assert weights["presence"] >= 0.8
    calm = Intent.from_dict({"kind": "interview"}).signal_weights()
    assert calm["action"] < weights["action"]


@requires_ffmpeg
def test_slowmo_lands_on_exactly_one_clip_and_halves_its_speed(burst):
    template = {
        "name": "t", "source": "", "duration": 4.0, "aspect": "9:16",
        "shots": [{"start": i, "duration": 1.0, "motion": "static", "energy": 0.5} for i in range(4)],
        "bpm": 120.0,
    }
    built = style.build_timeline(template, str(burst), "T", brain=False, intent={"slowmo": True})
    clips = [c for c in built["timeline"]["clips"] if c["trackId"] == "v1"]

    slow = [c for c in clips if c["props"].get("speed") == 0.5]
    assert len(slow) == 1, f"slow-mo on {len(slow)} clips, expected exactly the best one"
    assert any("slow-mo" in line for line in built["summary"]["applied"])


@requires_ffmpeg
def test_no_slowmo_by_default(burst):
    template = {
        "name": "t", "source": "", "duration": 3.0, "aspect": "9:16",
        "shots": [{"start": i, "duration": 1.0, "motion": "static", "energy": 0.5} for i in range(3)],
        "bpm": 120.0,
    }
    built = style.build_timeline(template, str(burst), "T", brain=False)
    clips = [c for c in built["timeline"]["clips"] if c["trackId"] == "v1"]

    assert not [c for c in clips if c["props"].get("speed", 1.0) != 1.0]
