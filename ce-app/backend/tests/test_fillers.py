"""Filler removal must cut time-buying words without eating real ones."""
from __future__ import annotations

from core.engine import fillers


def test_english_fillers_are_removed():
    assert fillers.clean_text("um, so like, we won the point") == "so, we won the point"


def test_persian_fillers_are_removed():
    assert fillers.clean_text("یعنی ما خب بردیم") == "ما بردیم"


def test_a_filler_is_never_eaten_inside_a_word():
    # "like" and "you know" are fillers and go; "likelihood" is a real word and
    # must survive untouched.
    assert fillers.clean_text("I like that you know the likelihood") == (
        "I that the likelihood"
    )


def test_an_emptied_cue_is_dropped_not_left_blank():
    cues = [
        {"text": "um uh", "words": [{"word": "um", "start": 0, "end": 0.2}]},
        {"text": "we won the point", "words": [{"word": "we", "start": 1, "end": 1.2}]},
    ]
    cleaned = fillers.clean_cues(cues)

    assert len(cleaned) == 1
    assert cleaned[0]["text"] == "we won the point"
