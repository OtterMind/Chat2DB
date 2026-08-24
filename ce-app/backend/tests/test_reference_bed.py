"""The reference's own soundtrack comes with the template.

Until 0.8.2 the template carried the *behaviour* of the music — tempo, how far
it steps aside for a voice — and never the music. That was our copyright
judgement made on the user's behalf; the owner of the project asked for the
track itself and takes that decision. So: the audio is extracted once, stored
beside the `.cetemplate` so it survives the reference being moved, and placed on
the timeline only when the user has not brought a track of their own.

What must be true, and is asserted here: the file is real audio, the rebuild
uses it, a track of the user's own still wins, and the cuts are scored against
whichever one will actually play.
"""
from __future__ import annotations

import subprocess

import pytest

from core.engine import compose, style
from tests.conftest import requires_ffmpeg


def _run(args: list[str]) -> None:
    subprocess.run([compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


@pytest.fixture()
def templates_home(tmp_path, monkeypatch):
    monkeypatch.setattr(style, "templates_dir", lambda: tmp_path)
    return tmp_path


@pytest.fixture(scope="module")
def musical_reference(tmp_path_factory):
    """Four shots of two seconds over a 120 BPM click — a template with a rhythm."""
    base = tmp_path_factory.mktemp("bed")
    parts = []
    for index, pattern in enumerate(("testsrc2", "smptebars", "rgbtestsrc", "yuvtestsrc")):
        part = base / f"s{index}.mp4"
        _run(["-f", "lavfi", "-i", f"{pattern}=size=360x640:rate=25:duration=2",
              "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(part)])
        parts.append(part)
    listing = base / "list.txt"
    listing.write_text("".join(f"file '{p}'\n" for p in parts), encoding="utf-8")
    silent = base / "silent.mp4"
    _run(["-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(silent)])

    target = base / "reference.mp4"
    _run([
        "-i", str(silent),
        "-f", "lavfi", "-i", "aevalsrc='0.8*sin(2*PI*440*t)*exp(-24*mod(t\\,0.5))':d=8:s=44100",
        "-c:v", "copy", "-c:a", "aac", "-shortest", str(target),
    ])
    return target


@pytest.fixture(scope="module")
def silent_footage(tmp_path_factory):
    target = tmp_path_factory.mktemp("own") / "own.mp4"
    _run(["-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=20",
          "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(target)])
    return target


@requires_ffmpeg
def test_saving_a_template_keeps_the_soundtrack(templates_home, musical_reference):
    template = style.analyse(str(musical_reference), "withmusic")
    style.save_template(template)

    bed = style.bed_path("withmusic")
    assert bed.exists(), "the reference's audio was not kept"
    assert bed.stat().st_size > 4000, "the bed is suspiciously small"

    info = compose.probe_media(str(bed))
    assert info.get("has_audio")
    assert abs(float(info.get("duration") or 0) - 8.0) < 0.5

    document = style.load_template("withmusic")
    assert document["audio"]["hasBed"] is True
    assert document["audio"]["bed"] == str(bed)


@requires_ffmpeg
def test_the_rebuild_plays_the_reference_s_music(templates_home, musical_reference, silent_footage):
    style.save_template(style.analyse(str(musical_reference), "withmusic"))
    document = style.load_template("withmusic")

    built = style.build_timeline(document, str(silent_footage), "Test", brain=False)
    music_clips = [c for c in built["timeline"]["clips"] if c["trackId"] == "a1"]

    assert music_clips, "the template has a soundtrack and the edit came back silent"
    assert music_clips[0]["src"] == str(style.bed_path("withmusic"))
    assert any("soundtrack" in note for note in built["summary"]["applied"])
    assert not any("music" in note for note in built["summary"]["skipped"])


@requires_ffmpeg
def test_your_own_track_still_wins(templates_home, musical_reference, silent_footage, tmp_path):
    style.save_template(style.analyse(str(musical_reference), "withmusic"))
    document = style.load_template("withmusic")

    mine = tmp_path / "mine.m4a"
    _run(["-f", "lavfi", "-i", "sine=frequency=220:duration=6", "-c:a", "aac", str(mine)])

    built = style.build_timeline(document, str(silent_footage), "Test", music=str(mine), brain=False)
    music_clips = [c for c in built["timeline"]["clips"] if c["trackId"] == "a1"]

    assert music_clips[0]["src"] == str(mine)


@requires_ffmpeg
def test_a_silent_reference_keeps_no_bed_and_says_nothing_false(templates_home, tmp_path):
    silent = tmp_path / "silent.mp4"
    _run(["-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25:duration=3",
          "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(silent)])

    style.save_template(style.analyse(str(silent), "quiet"))

    assert not style.bed_path("quiet").exists()
    document = style.load_template("quiet")
    assert not (document.get("audio") or {}).get("hasBed")


@requires_ffmpeg
def test_the_cuts_are_scored_against_the_track_that_will_play(templates_home, musical_reference, silent_footage):
    """The bed has to be chosen *before* the planners run, not after."""
    style.save_template(style.analyse(str(musical_reference), "withmusic"))
    document = style.load_template("withmusic")

    built = style.build_timeline(document, str(silent_footage), "Test", brain=False)
    scoreboard = built["summary"]["brain"]["scoreboard"]

    # With a beat grid in play, the beat-snapped plan must at least have entered
    # the race — that only happens when the bed was resolved before the brain.
    names = [row["name"] for row in scoreboard]
    assert "rules" in names
    assert any("on_beat" in (row.get("terms") or {}) for row in scoreboard), scoreboard
