"""ASS round-trip: word timings survive the trip through `\\kf` tags."""
from __future__ import annotations

from core.engine import assfile

HAND = """[Script Info]
Title: hand-made

[V4+ Styles]
Format: Name, Fontname
Style: Default,Vazirmatn

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\kf50}سلام {\\kf50}دنیا
Dialogue: 0,0:00:04.25,0:00:06.00,Default,,0,0,0,,متن ساده بدون کارائوکه
"""


def test_timestamps_round_trip():
    assert assfile.parse_timestamp("0:01:23.45") == 83.45
    assert assfile.format_timestamp(83.45) == "0:01:23.45"
    assert assfile.format_timestamp(0) == "0:00:00.00"


def test_kf_tags_become_timed_words():
    plain, words = assfile._words_from_text("{\\kf50}سلام {\\kf50}دنیا", 1.0)

    assert plain == "سلام دنیا"
    assert words == [{"start": 1.0, "end": 1.5, "text": "سلام"},
                     {"start": 1.5, "end": 2.0, "text": "دنیا"}]


def test_a_hand_made_ass_imports_with_and_without_karaoke(tmp_path):
    path = tmp_path / "hand.ass"
    path.write_text(HAND, encoding="utf-8")

    body = assfile.import_cues(str(path))

    assert body["cues"][0]["words"] == [
        {"start": 1.0, "end": 1.5, "text": "سلام"},
        {"start": 1.5, "end": 2.0, "text": "دنیا"}]
    assert body["cues"][1]["words"] == []
    assert body["cues"][1]["text"] == "متن ساده بدون کارائوکه"


def test_export_then_import_is_a_round_trip(tmp_path):
    cues = [{"start": 2.0, "end": 4.0, "text": "سلام دنیا",
             "words": [{"start": 2.0, "end": 3.0, "text": "سلام"},
                       {"start": 3.0, "end": 4.0, "text": "دنیا"}]}]
    out = assfile.export(cues, str(tmp_path / "out.ass"))

    assert out["writer"] in ("builtin", "python-ass")
    back = assfile.import_cues(out["path"])

    assert back["cues"][0]["start"] == 2.0
    assert back["cues"][0]["end"] == 4.0
    assert [w["text"] for w in back["cues"][0]["words"]] == ["سلام", "دنیا"]
    # centisecond rounding is the only allowed loss
    assert abs(back["cues"][0]["words"][0]["end"] - 3.0) < 0.011


def test_missing_file_is_an_error_not_a_crash(tmp_path):
    try:
        assfile.import_cues(str(tmp_path / "nope.ass"))
        raise AssertionError("should have raised")
    except FileNotFoundError:
        pass
