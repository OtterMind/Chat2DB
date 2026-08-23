"""Style analysis: does the analyser measure what we built into the clip?

Every fixture here is generated to a known recipe — shots of an exact length, a
click track at an exact tempo, camera moves with an exact scale or pan — so each
assertion has a right answer instead of a plausible one. Motion classification in
particular was wrong three times before these tests existed:

* a push-in read as a pan (whole-frame correlation cannot separate them),
* quadrant divergence read self-similar content as noise,
* log-polar read *any* movement as a zoom until translation was cancelled first,
  and then reported the zoom backwards.
"""
from __future__ import annotations

import subprocess

import pytest

from core.engine import compose, style
from tests.conftest import requires_ffmpeg


def _run(args: list[str]) -> None:
    subprocess.run([compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


@pytest.fixture(scope="module")
def still(tmp_path_factory):
    """A rich, non-repeating picture: colour bars are periodic and fool correlation."""
    target = tmp_path_factory.mktemp("style") / "still.png"
    _run(["-f", "lavfi", "-i", "mandelbrot=size=900x900", "-frames:v", "1", str(target)])
    return target


@pytest.fixture(scope="module")
def moves(still, tmp_path_factory):
    """One clip per camera move, each built from the same still."""
    base = tmp_path_factory.mktemp("moves")
    clips = {}
    recipes = {
        "push": "scale=w='trunc(320*(1+0.25*t)/2)*2':h=-2:eval=frame,crop=320:320",
        "pull": "scale=w='trunc(320*(1.8-0.25*t)/2)*2':h=-2:eval=frame,crop=320:320",
        "pan": "crop=320:320:x='min(iw-ow\\,60*t)':y=200",
        "static": "crop=320:320:x=200:y=200",
    }
    for name, chain in recipes.items():
        target = base / f"{name}.mp4"
        _run(["-loop", "1", "-i", str(still), "-t", "3", "-vf", chain, "-r", "25",
              "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(target)])
        clips[name] = target
    return clips


@pytest.fixture(scope="module")
def reference(tmp_path_factory):
    """Eight shots of exactly 1.5 s over a 120 BPM click — a known template."""
    base = tmp_path_factory.mktemp("reference")
    patterns = ["testsrc", "smptebars", "rgbtestsrc", "testsrc2",
                "smptehdbars", "yuvtestsrc", "testsrc", "smptebars"]
    parts = []
    for index, pattern in enumerate(patterns):
        part = base / f"shot{index}.mp4"
        _run(["-f", "lavfi", "-i", f"{pattern}=size=360x640:rate=25:duration=1.5",
              "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(part)])
        parts.append(part)
    listing = base / "list.txt"
    listing.write_text("".join(f"file '{p}'\n" for p in parts), encoding="utf-8")
    silent = base / "silent.mp4"
    _run(["-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(silent)])
    target = base / "reference.mp4"
    _run(["-i", str(silent), "-f", "lavfi", "-i",
          "aevalsrc='0.9*sin(2*PI*880*t)*exp(-30*mod(t\\,0.5))':d=12:s=44100",
          "-c:v", "copy", "-c:a", "aac", "-shortest", str(target)])
    return target


# ------------------------------------------------------------------- motion


@requires_ffmpeg
@pytest.mark.parametrize("move", ["push", "pull", "pan", "static"])
def test_camera_motion_is_recognised(moves, move):
    kind, _ = style._classify_motion(str(moves[move]), 0.2, 2.5)
    assert kind == move, f"a {move} was read as {kind}"


@requires_ffmpeg
def test_a_strip_of_frames_comes_back_in_one_call(moves):
    frames = style.sample_strip(str(moves["static"]), 0.0, 2.0, 5)
    assert len(frames) == 5
    assert all(f.shape == (style.FRAME, style.FRAME) for f in frames)


# ----------------------------------------------------------------- template


@requires_ffmpeg
def test_the_template_matches_the_recipe(reference):
    template = style.analyse(str(reference), name="unit")

    assert abs(template.duration - 12.0) < 0.2
    assert template.aspect == "9:16"

    # Shots were built at 1.5 s; the median is the honest statistic here, since a
    # detector can merge two neighbouring patterns.
    assert 1.3 <= template.median_shot <= 1.7
    assert 5 <= len(template.shots) <= 9

    assert abs(template.bpm - 120.0) < 3.0
    assert len(template.beats) > 15

    assert template.transitions["type"] == "cut"      # concatenated, no dissolves
    assert template.look["saturation"] > 1.0          # test patterns are saturated
    assert template.unknown                            # it says what it cannot know


@requires_ffmpeg
def test_the_template_survives_a_round_trip(reference, tmp_path, monkeypatch):
    monkeypatch.setattr(style, "templates_dir", lambda: tmp_path)
    template = style.analyse(str(reference), name="round-trip")
    style.save_template(template)

    listing = style.list_templates()
    assert [t["name"] for t in listing] == ["round-trip"]
    assert listing[0]["shots"] == len(template.shots)

    loaded = style.load_template("round-trip")
    assert loaded["bpm"] == template.bpm

    style.delete_template("round-trip")
    assert style.list_templates() == []


# ------------------------------------------------------------------ planning


@requires_ffmpeg
def test_the_plan_follows_the_template(reference, moves):
    """The user's footage, laid out in the shape of the reference."""
    template = style.analyse(str(reference), name="plan")
    built = style.build_timeline(template, str(moves["pan"]), name="Styled")

    clips = built["timeline"]["clips"]
    assert clips, "the planner produced nothing"
    assert len(clips) == len(template.shots)

    # The clips tile the timeline with no gaps and no overlaps.
    for previous, current in zip(clips, clips[1:]):
        assert abs(current["start"] - (previous["start"] + previous["duration"])) < 0.01

    # Every shot of the template is represented, clamped to what the source has.
    for clip, shot in zip(clips, template.shots):
        assert clip["duration"] <= shot.duration + 0.01
        assert clip["src"] == str(moves["pan"])

    # The look travels with the plan.
    grade = clips[0]["props"]["adjust"]
    assert grade["saturation"] == template.look["saturation"]

    assert built["aspect"] == template.aspect
    assert built["summary"]["shots"] == len(clips)


@requires_ffmpeg
def test_camera_moves_become_keyframes(reference, moves, monkeypatch):
    """A template shot that pushes in must produce an animated clip, not a still one."""
    template = style.analyse(str(reference), name="kf")
    for shot in template.shots:
        shot.motion = "push"

    built = style.build_timeline(template, str(moves["static"]))
    first = built["timeline"]["clips"][0]
    keyframes = first["props"].get("keyframes")
    assert keyframes and len(keyframes) == 2
    assert keyframes[0]["scale"] < keyframes[1]["scale"]


@requires_ffmpeg
def test_the_plan_is_rendered_by_the_normal_compositor(reference, moves, tmp_path):
    """Whatever the planner emits has to be a timeline the engine already renders."""
    template = style.analyse(str(reference), name="render")
    built = style.build_timeline(template, str(moves["pan"]))

    timeline = compose.Timeline.from_dict({**built["timeline"], "width": 180, "height": 320, "fps": 12})
    output = compose.render(timeline, tmp_path / "styled.mp4")
    assert output.exists()
    info = compose.probe_media(str(output))
    assert (info["width"], info["height"]) == (180, 320)
    assert info["duration"] > 1.0


# ------------------------------------------------------- fully automatic mode


@requires_ffmpeg
def test_captions_and_music_are_placed_automatically(reference, moves, tmp_path):
    """The second door does everything itself: no prompt, no settings."""
    template = style.analyse(str(reference), name="auto")

    music = tmp_path / "bed.wav"
    _run(["-f", "lavfi", "-i", "sine=frequency=220:duration=12", str(music)])

    cues = [
        {"start": 0.2, "end": 1.4, "text": "first line", "words": []},
        {"start": 1.6, "end": 2.9, "text": "second line", "words": []},
    ]
    built = style.build_timeline(
        template, str(moves["pan"]), name="Auto", music=str(music), captions=cues
    )

    clips = built["timeline"]["clips"]
    text = [c for c in clips if c["trackId"] == "t1"]
    audio = [c for c in clips if c["trackId"] == "a1"]

    assert len(text) == 2 and text[0]["text"] == "first line"
    assert text[0]["props"]["position"] == template.captions["position"]

    assert len(audio) == 1
    assert audio[0]["src"] == str(music)
    # The template says the music sits under the voice, so the bed must duck.
    assert audio[0]["props"]["duck"] is True

    assert "music bed with ducking" in built["summary"]["applied"]
    assert built["summary"]["captions"] == 2
    assert built["summary"]["skipped"] == []


@requires_ffmpeg
def test_what_could_not_be_done_is_reported(reference, moves):
    """No speech model and no music: the edit still happens, the gaps are named."""
    template = style.analyse(str(reference), name="honest")
    built = style.build_timeline(template, str(moves["pan"]))

    skipped = " ".join(built["summary"]["skipped"])
    assert "captions" in skipped
    assert "music" in skipped
    # …and the parts that *were* done are listed too, not assumed.
    assert "cut to the template rhythm" in built["summary"]["applied"]


@requires_ffmpeg
def test_the_automatic_result_still_renders(reference, moves, tmp_path):
    template = style.analyse(str(reference), name="auto-render")
    music = tmp_path / "bed.wav"
    _run(["-f", "lavfi", "-i", "sine=frequency=220:duration=12", str(music)])
    built = style.build_timeline(
        template, str(moves["static"]), music=str(music),
        captions=[{"start": 0.5, "end": 1.5, "text": "hello", "words": []}],
    )
    timeline = compose.Timeline.from_dict({**built["timeline"], "width": 180, "height": 320, "fps": 12})
    output = compose.render(timeline, tmp_path / "auto.mp4")
    assert output.exists()
    info = compose.probe_media(str(output))
    assert info["has_video"] and info["has_audio"]
