"""Text rendering: ASS generation and the Persian path in particular."""
from __future__ import annotations

from core.engine import subtitles as subs
from core.engine.transcribe import group_words


def test_colour_conversion_is_ass_bgr():
    assert subs._ass_colour("#FF0000") == "&H000000FF"
    assert subs._ass_colour("#00FF00") == "&H0000FF00"


def test_persian_text_selects_an_arabic_capable_font():
    document = subs.build_ass([subs.TextCue(0, 2, "سلام دنیا")], 1080, 1920)
    assert subs.DEFAULT_FONT_RTL in document
    latin = subs.build_ass([subs.TextCue(0, 2, "Hello")], 1080, 1920)
    assert subs.DEFAULT_FONT_LATIN in latin


def test_karaoke_timings_are_emitted_per_word():
    cue = subs.TextCue(
        0, 2, "one two",
        words=[subs.Word(0, 0.5, "one"), subs.Word(0.5, 1.2, "two")],
        animate=True,
    )
    document = subs.build_ass([cue], 720, 1280)
    assert "{\\kf50}one" in document
    assert "{\\kf70}two" in document


def test_braces_and_newlines_are_escaped():
    document = subs.build_ass([subs.TextCue(0, 1, "a {b}\nc")], 720, 1280)
    assert "\\{b\\}" in document and "\\N" in document


def test_timestamps_use_ass_format():
    assert subs._timestamp(3661.5) == "1:01:01.50"


def test_cues_are_built_from_edit_model_clips():
    cues = subs.cues_from_clips([
        {"start": 1, "duration": 2, "text": "hi", "props": {"fontSize": 60, "position": "top"}},
        {"start": 5, "duration": 1, "text": "", "props": {}},  # empty text is dropped
    ])
    assert len(cues) == 1
    assert cues[0].start == 1 and cues[0].end == 3
    assert cues[0].font_size == 60 and cues[0].position == "top"


def test_words_are_grouped_into_readable_lines():
    words = [{"start": i * 0.4, "end": i * 0.4 + 0.35, "text": f"word{i}"} for i in range(12)]
    cues = group_words(words, max_chars=20)
    assert len(cues) > 1
    assert all(len(cue["text"]) <= 26 for cue in cues)


def test_a_pause_breaks_the_caption_line():
    words = [
        {"start": 0.0, "end": 0.3, "text": "before"},
        {"start": 2.0, "end": 2.4, "text": "after"},  # 1.7s gap
    ]
    cues = group_words(words, max_chars=100)
    assert len(cues) == 2
