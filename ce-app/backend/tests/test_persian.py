"""Persian cleaning must make ASR output safe for libass and for scoring."""
from __future__ import annotations

from core.engine import persian


def test_arabic_variants_become_persian():
    assert persian.normalize("يك كارت") == "یک کارت"


def test_diacritics_are_stripped():
    assert persian.normalize("سَلام") == "سلام"


def test_half_space_for_prefixes():
    assert persian.normalize("می روم") == "می‌روم"


def test_persian_digits_when_the_run_is_persian():
    assert persian.normalize("سلام 123") == "سلام ۱۲۳"


def test_double_spaces_collapse_and_empty_stays_empty():
    assert persian.normalize("a   b") == "a b"
    assert persian.normalize("") == ""


def test_subtitles_normalize_their_text():
    from core.engine import subtitles

    cues = subtitles.cues_from_clips([
        {"text": "مي روم 123", "start": 0, "duration": 2, "props": {}},
    ])
    assert cues[0].text == "می‌روم ۱۲۳"
