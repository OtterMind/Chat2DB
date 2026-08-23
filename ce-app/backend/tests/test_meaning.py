"""Highlights chosen from what was said, not only from how loud it was.

The fixture is a scripted transcript: one moment where the speaker makes the
point ("the most important thing…"), one where they say nothing of substance at
the same volume. A selection driven by energy alone cannot tell them apart; that
is the gap this scoring closes.
"""
from __future__ import annotations

from core.brain import meaning


def test_a_sentence_that_makes_a_point_beats_filler():
    point = meaning.score_text("But the most important thing is that we cut the render time in half.")
    filler = meaning.score_text("uh, yeah, so, um")

    assert point > 0.4
    assert filler < 0.1
    assert point > filler * 4


def test_persian_is_scored_by_its_own_markers():
    point = meaning.score_text("مهم‌ترین نکته این است که زمان رندر نصف شد.")
    filler = meaning.score_text("خب … آها")

    assert point > 0.35
    assert point > filler


def test_a_question_counts_as_a_hook():
    assert meaning.score_text("Why does this always break?") > meaning.score_text("This breaks.")


def test_numbers_survive_being_cut_out_of_context():
    assert meaning.score_text("We shipped 3 releases today.") > meaning.score_text("We shipped today.")


def test_an_empty_or_silent_window_scores_zero():
    assert meaning.score_text("") == 0.0
    assert meaning.score_window([], 0.0, 5.0) == 0.0
    assert meaning.score_window([{"start": 10, "end": 12, "text": "hello"}], 0.0, 5.0) == 0.0


def test_density_matters_not_just_presence():
    cues = [{"start": 0.0, "end": 2.0, "text": "But the most important thing is the result."}]

    tight = meaning.score_window(cues, 0.0, 2.0)
    sparse = meaning.score_window(cues, 0.0, 20.0)

    assert tight > sparse, "the same sentence spread thin should not score the same"


def test_the_scripted_moment_wins_the_selection():
    """The end-to-end claim: the moment that carries the point is chosen."""
    cues = [
        {"start": 0.0, "end": 4.0, "text": "so anyway we were driving and it was fine"},
        {"start": 4.0, "end": 8.0, "text": "But the most important thing is that the battery lasted 3 days."},
        {"start": 8.0, "end": 12.0, "text": "and then we went home"},
    ]
    windows = [(0.0, 4.0), (4.0, 8.0), (8.0, 12.0)]
    # Every window is equally "loud" — energy cannot separate them.
    scores = [
        meaning.blend(0.5, meaning.score_window(cues, start, end))
        for start, end in windows
    ]

    assert scores.index(max(scores)) == 1, scores


def test_blending_never_leaves_the_range():
    for measured in (0.0, 0.5, 1.0):
        for sense in (0.0, 0.5, 1.0):
            assert 0.0 <= meaning.blend(measured, sense) <= 1.0
