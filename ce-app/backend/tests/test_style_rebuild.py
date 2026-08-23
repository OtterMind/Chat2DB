"""The rebuild, not the analysis: does the edit it produces look edited?

This file exists because of a report from the user's own machine — "Style Match
worked like an amateur, as if no AI were involved at all". It was right, and the
cause was not the AI: on a minute of continuous talking the highlight finder
returned **one** range, and the planner then took every one of the template's
twenty shots from the same starting second. Twenty clips, one unique offset: the
same half second, twenty times.

Nothing in the suite noticed, because every test asserted *counts* — twenty
clips, gapless, graded — and none asserted that the twenty clips were different
from each other. These do.
"""
from __future__ import annotations

import subprocess

import pytest

from core.brain.objective import Context, Pick, score_plan
from core.brain.planners import rule_plan, snap_to_beats
from core.engine import compose, style
from tests.conftest import requires_ffmpeg


def _run(args: list[str]) -> None:
    subprocess.run([compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


@pytest.fixture(scope="module")
def one_long_take(tmp_path_factory):
    """Sixty seconds of continuous talking — the case that broke it."""
    target = tmp_path_factory.mktemp("rebuild") / "take.mp4"
    _run([
        "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=60",
        "-f", "lavfi", "-i", "sine=frequency=300:duration=60",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-shortest", str(target),
    ])
    return target


def _template(shots: int = 20, length: float = 0.6, **extra) -> dict:
    document = {
        "name": "t", "aspect": "9:16", "duration": shots * length,
        "shots": [
            {"start": i * length, "duration": length, "motion": "static", "energy": 0.1}
            for i in range(shots)
        ],
        "look": {}, "transitions": {"type": "cut", "count": shots - 1, "soft": 0, "duration": 0.4},
        "captions": {}, "audio": {}, "cuts_on_beat": 0.5, "bpm": 120.0,
    }
    document.update(extra)
    return document


@requires_ffmpeg
def test_every_shot_comes_from_a_different_moment(one_long_take):
    """The regression, stated as the user experienced it."""
    built = style.build_timeline(_template(), str(one_long_take), "Test", brain=False)
    clips = [c for c in built["timeline"]["clips"] if c["trackId"] == "v1"]

    assert len(clips) == 20
    offsets = [c["offset"] for c in clips]
    assert len(set(offsets)) == len(offsets), f"the same footage is used twice: {offsets}"

    # And they should be spread across the take, not crowded into its first seconds.
    assert max(offsets) - min(offsets) > 8.0, f"the whole edit came from {max(offsets):.1f}s of footage"


@requires_ffmpeg
def test_the_highlight_finder_returns_many_small_candidates(one_long_take):
    picks = style._highlights(str(one_long_take), wanted=40, minimum=0.5, window=0.6)

    assert len(picks) > 20, "one long take must not collapse into a handful of candidates"
    assert all(p["end"] - p["start"] <= 1.2 for p in picks), "candidates should be shot-sized"
    assert len({p["start"] for p in picks}) == len(picks)


def test_the_planner_prefers_fresh_material_over_the_best_moment_twice():
    context = Context(duration=60, target_shots=[1.0, 1.0, 1.0], best_highlight=1.0)
    highlights = [Pick(0, 2, 1.0), Pick(5, 7, 0.9), Pick(10, 12, 0.8)]

    plan = rule_plan(highlights, context)

    starts = [p.start for p in plan.picks]
    assert len(set(starts)) == 3, f"the planner reused material: {starts}"


def test_repetition_is_punished_hard_enough_to_lose():
    """A plan that repeats one moment must score below one that does not.

    It used to score 0.91 — every term was happy except variety, and variety was
    worth one point out of fourteen.
    """
    context = Context(duration=60, target_shots=[1.0] * 6, speech=[(0.0, 60.0)], best_highlight=1.0)
    repeated = [Pick(0.0, 1.0, 1.0) for _ in range(6)]
    spread = [Pick(i * 2.0, i * 2.0 + 1.0, 1.0) for i in range(6)]

    poor = score_plan(repeated, context)
    good = score_plan(spread, context)

    assert poor.terms["variety"] < 0.2
    assert good.total - poor.total > 0.15, (poor.as_dict(), good.as_dict())


def test_cutting_on_the_beat_is_a_candidate_not_a_rewrite():
    """Snapping trades length for rhythm, so the score decides — not this code."""
    from core.brain import race as brain_race

    beats = [i * 0.5 for i in range(40)]
    context = Context(duration=60, target_shots=[0.5] * 6, beats=beats,
                      reference_cuts_on_beat=1.0, speech=[(0.0, 60.0)], best_highlight=1.0)
    highlights = [Pick(i * 2.0, i * 2.0 + 1.0, 1.0) for i in range(8)]

    result = brain_race.race(highlights, context, use_llm=False)
    names = [row["name"] for row in result.scoreboard]

    assert "rules" in names
    assert "rules+beats" in names or result.winner == "rules"


def test_cuts_are_pulled_onto_the_beat_when_there_is_music():
    beats = [i * 0.5 for i in range(40)]  # 120 BPM
    context = Context(duration=60, target_shots=[0.62] * 6, beats=beats,
                      reference_cuts_on_beat=1.0, best_highlight=1.0)
    picks = [Pick(i * 2.0, i * 2.0 + 0.62, 1.0) for i in range(6)]

    snapped = snap_to_beats(picks, context)

    cursor = 0.0
    off_beat = 0
    for pick in snapped[:-1]:
        cursor += pick.duration
        if min(abs(cursor - b) for b in beats) > 0.05:
            off_beat += 1
    assert off_beat == 0, "cuts did not land on the beat"
    # Snapping costs length: a 0.62 s shot on a 0.5 s grid becomes 0.5 s. That
    # is the trade the race exists to weigh, so the bound here is honest rather
    # than tight — and `duration_fit` (weight 3) is what stops it going too far.
    shrink = 1 - sum(p.duration for p in snapped) / sum(p.duration for p in picks)
    assert 0 <= shrink < 0.25, f"the edit lost {shrink:.0%} of its length"


def test_snapping_does_nothing_without_beats():
    context = Context(duration=10, target_shots=[1.0, 1.0], beats=[])
    picks = [Pick(0, 1, 1.0), Pick(2, 3, 1.0)]
    assert snap_to_beats(picks, context) == picks


@requires_ffmpeg
def test_dissolves_appear_in_the_reference_s_proportion(one_long_take):
    """A reference that dissolves at half its junctions should not come back with none."""
    template = _template()
    template["transitions"] = {"type": "cut", "count": 19, "soft": 10, "duration": 0.4}

    built = style.build_timeline(template, str(one_long_take), "Test", brain=False)
    transitions = built["timeline"]["transitions"]

    assert transitions, "a half-dissolved reference produced no transitions at all"
    assert 4 <= len(transitions) <= 14, f"{len(transitions)} transitions for a 50 % reference"
