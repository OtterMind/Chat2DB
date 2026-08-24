"""What the video is *for* — and the two failures that followed from not knowing.

Both were reported by the user in the same sentence: "it shortens the first
video" and "the highlight detection of the second video is very weak". They were
the same bug, and both were measurable before a line was changed:

* On 120 s of footage against a 12 s reference the rebuild touched **17.3 s —
  14.4 % of the material** — and produced the *same* offsets it produced for a
  30 s file, which is what "it shortens my video" looked like from the chair in
  front of the screen.
* The 26 candidate moments it chose between scored 0.998 to 1.0: a spread of
  **0.002**. The sort was stable, so "best" silently meant "earliest".

These tests are written as the user experiences them, not as unit tests of the
helper: the file is built to a recipe, the rebuild is run, and the assertions are
about the edit that comes out.
"""
from __future__ import annotations

import subprocess

import pytest

from core.brain.objective import Context, Pick, score_plan
from core.engine import compose, style
from core.engine.intent import NEUTRAL, Intent
from tests.conftest import requires_ffmpeg


def _run(args: list[str]) -> None:
    subprocess.run([compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


@pytest.fixture(scope="module")
def long_take(tmp_path_factory):
    """Two minutes of continuous talking — long enough that the beginning is a trap."""
    target = tmp_path_factory.mktemp("intent") / "take.mp4"
    _run([
        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25:duration=120",
        "-f", "lavfi", "-i", "sine=frequency=300:duration=120",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-shortest", str(target),
    ])
    return target


def _template(shots: int = 12, length: float = 1.0, **extra) -> dict:
    document = {
        "name": "t", "aspect": "9:16", "duration": shots * length,
        "shots": [
            {"start": i * length, "duration": length, "motion": "static", "energy": 0.1}
            for i in range(shots)
        ],
        "look": {}, "transitions": {"type": "cut", "count": shots - 1, "soft": 0, "duration": 0.4},
        "captions": {}, "audio": {}, "cuts_on_beat": 0.5, "bpm": 120.0,
        "median_shot": length, "mean_shot": length, "speech_ratio": 0.6,
    }
    document.update(extra)
    return document


def _video(document: dict) -> list[dict]:
    return [c for c in document["timeline"]["clips"] if c["trackId"] == "v1"]


# ------------------------------------------------------------------ coverage


@requires_ffmpeg
def test_the_whole_file_is_a_candidate(long_take):
    """The regression: only the first 14 % of the material was ever considered."""
    candidates = style._highlights(str(long_take), wanted=32, minimum=0.8, window=1.0)

    assert candidates, "no candidates at all"
    latest = max(c["end"] for c in candidates)
    assert latest > 100.0, (
        f"the last candidate ends at {latest:.1f}s of a 120s file — "
        "the rebuild can only ever use the beginning of the footage"
    )


@requires_ffmpeg
def test_candidates_are_ranked_by_something(long_take):
    """A spread of 0.002 is not a ranking; it is a constant wearing a sort."""
    candidates = style._highlights(str(long_take), wanted=24, minimum=0.8, window=1.0)
    scores = [c["score"] for c in candidates]

    assert max(scores) - min(scores) > 0.05, (
        f"the candidates span {min(scores):.4f}..{max(scores):.4f} — nothing separates them"
    )


@requires_ffmpeg
def test_the_edit_reaches_the_end_of_the_material(long_take):
    """What the user asked for, as a number: use the footage I gave you."""
    built = style.build_timeline(
        _template(), str(long_take), "Test", brain=False, intent={"seconds": 60},
    )

    assert built["summary"]["sourceSpanUsed"] > 80.0, (
        f"the edit drew from {built['summary']['sourceSpanUsed']}% of the file"
    )


# -------------------------------------------------------------------- length


@requires_ffmpeg
@pytest.mark.parametrize("asked", [8.0, 30.0, 60.0])
def test_a_requested_length_is_honoured(long_take, asked):
    """The rebuild used to be exactly as long as the reference, always."""
    built = style.build_timeline(
        _template(), str(long_take), "Test", brain=False, intent={"seconds": asked},
    )
    clips = _video(built)

    assert abs(built["summary"]["duration"] - asked) <= 0.5, (
        f"asked for {asked:g}s, got {built['summary']['duration']}s"
    )
    # And it is reached by running the rhythm again, not by crushing the shots
    # into a flash: nothing on the timeline is a single frame.
    assert min(c["duration"] for c in clips) >= 0.2
    assert f"length set to {asked:g} s" in built["summary"]["applied"]


@requires_ffmpeg
def test_no_answers_means_the_reference_length(long_take):
    """Neutral by default: an unanswered question must change nothing."""
    built = style.build_timeline(_template(), str(long_take), "Test", brain=False)

    assert abs(built["summary"]["duration"] - 12.0) < 0.05
    assert built["summary"]["intentSaid"] == []


# --------------------------------------------------------------- the opening


@requires_ffmpeg
def test_the_opening_shot_is_never_shortened(long_take):
    """A hook measurement may hold an opening; it may not chop it.

    The old form assigned `hook.firstCut` outright inside a 6 s window, so a
    template whose first shot was measured at 4 s but whose first *cut* was at
    0.6 s opened on a fraction of a second — and a 7 s held intro was ignored
    for being over the ceiling.
    """
    template = _template(shots=6, length=4.0)
    template["hook"] = {"firstCut": 0.6, "firstWord": 0.0}
    built = style.build_timeline(template, str(long_take), "Test", brain=False)

    assert _video(built)[0]["duration"] == pytest.approx(4.0, abs=0.05)

    template["hook"] = {"firstCut": 7.0, "firstWord": 0.0}
    held = style.build_timeline(template, str(long_take), "Test", brain=False)
    assert _video(held)[0]["duration"] == pytest.approx(7.0, abs=0.05)


# -------------------------------------------------------------- the answers


def test_answers_rebalance_the_judge_but_cannot_delete_a_measurement():
    """A weight is an opinion about measurements; it is not a measurement."""
    picks = [Pick(0.0, 1.0, 1.0), Pick(2.0, 3.0, 0.5)]
    plain = Context(duration=10.0, target_shots=[1.0, 1.0])
    weighted = Context(duration=10.0, target_shots=[1.0, 1.0], weights={"duration_fit": 4.0})

    assert score_plan(picks, weighted).weights["duration_fit"] == pytest.approx(
        score_plan(picks, plain).weights["duration_fit"] * 4.0
    )

    # An answer of zero must not switch a term off, and a typo must not explode it.
    extreme = Context(duration=10.0, target_shots=[1.0, 1.0], weights={"duration_fit": 0.0})
    assert score_plan(picks, extreme).weights["duration_fit"] > 0.0


def test_answers_are_parsed_tolerantly_and_rejected_when_they_are_nonsense():
    intent = Intent.from_dict({
        "kind": "Talking_Head",           # case, from a form
        "goal": "teach",
        "keep": "قیمت, ضمانت نامه",        # a Persian comma is a comma
        "avoid": "تبلیغ",
        "seconds": "45",                  # a text field
        "focus": "a thing that is not on the list",
    })

    assert intent.kind == "talking_head"
    assert intent.keep == ["قیمت", "ضمانت نامه"]
    assert intent.seconds == 45.0
    assert intent.focus == ""            # unknown is unanswered, not an error
    assert intent.signal_weights()["speech"] > intent.signal_weights()["motion"]
    assert Intent.from_dict(None).empty
    assert Intent.from_dict(None).signal_weights() == NEUTRAL
    # An hour is a wish; a week is a typo.
    assert Intent.from_dict({"seconds": 99999}).seconds == 0.0


def test_keywords_are_read_in_both_languages():
    intent = Intent.from_dict({"keep": "ضمانت", "avoid": "advert"})

    assert intent.keyword_score("and the ضمانت is two years long") > 0
    assert intent.keyword_score("this is an ADVERT for something") < 0
    assert intent.keyword_score("nothing relevant here") == 0.0


@requires_ffmpeg
def test_a_phrase_the_user_asked_to_keep_outranks_the_same_moment_without_it(long_take):
    """The user's own definition of a highlight must move the ranking.

    The shortlist is chosen by the cheap signals alone, so both runs measure the
    same candidates and the only difference between them is the answer — which is
    what makes this a fair comparison rather than two different sets of moments.
    """
    captions = [
        {"start": 0.0, "end": 10.0, "text": "and the ضمانت is two years long",
         "words": [{"start": 0.0, "end": 0.4, "word": "and"}]},
    ]
    plain = style._highlights(str(long_take), wanted=40, minimum=0.8, window=1.0, captions=captions)
    asked = style._highlights(str(long_take), wanted=40, minimum=0.8, window=1.0,
                              captions=captions, intent=Intent.from_dict({"keep": "ضمانت"}))
    by_start = {c["start"]: c for c in plain}

    lifted = [c for c in asked if c.get("keywords", 0.0) > 0]
    assert lifted, "the phrase the user asked to keep lifted nothing"
    for moment in lifted:
        assert moment["score"] > by_start[moment["start"]]["score"], (
            f"the moment saying «{captions[0]['text']}» did not rank higher for being asked for"
        )

