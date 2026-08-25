"""The word-level forced-alignment bridge: a refinement that never breaks captions."""
from __future__ import annotations

import sys

from core.engine import whisperx_align


def test_without_the_engine_alignment_hands_the_timings_back():
    # The sandbox has no whisperX; alignment must degrade, not raise.
    if whisperx_align.available():
        return  # on a machine that fetched it, the no-engine path is not the case

    words = [{"start": 0.0, "end": 0.5, "text": "سلام"},
             {"start": 0.5, "end": 1.0, "text": "دنیا"}]
    out = whisperx_align.align("does-not-matter.wav", words, language="fa")

    assert out["status"] == "no-engine"
    assert out["aligner"] is None
    assert out["words"] == words           # unchanged, karaoke keeps working
    assert out["words"] is not words       # a copy, not the caller's list


def test_whisperx_word_rows_convert_to_our_word_shape():
    class Row:
        def __init__(self, start, end, word):
            self.start, self.end, self.word = start, end, word

    class Aligned:
        word_segments = [Row(0.10, 0.42, " سلام "), Row(0.42, 0.90, "دنیا")]

    out = whisperx_align._to_words(Aligned())

    assert out == [{"start": 0.1, "end": 0.42, "text": "سلام"},
                   {"start": 0.42, "end": 0.9, "text": "دنیا"}]


def test_rows_missing_a_time_or_text_are_dropped_not_crashed_on():
    class Row:
        def __init__(self, start, end, word):
            self.start, self.end, self.word = start, end, word

    class Aligned:
        word_segments = [Row(None, 0.5, "x"), Row(0.5, None, "y"),
                         Row(0.5, 0.9, ""), Row(0.9, 1.2, "ok")]

    out = whisperx_align._to_words(Aligned())

    assert out == [{"start": 0.9, "end": 1.2, "text": "ok"}]


def test_an_engine_that_fails_midway_still_returns_the_original_words(monkeypatch):
    words = [{"start": 0.0, "end": 0.5, "text": "سلام"}]
    monkeypatch.setattr(whisperx_align, "available", lambda: True)

    class Boom:
        def __getattr__(self, _name):
            raise RuntimeError("aligner exploded")

    monkeypatch.setitem(sys.modules, "whisperx", Boom())

    out = whisperx_align.align("x.wav", words, language="fa")

    assert out["words"] == words
    assert out["status"].startswith("error:")


def test_a_successful_alignment_reports_the_persian_aligner(monkeypatch):
    class Row:
        def __init__(self, start, end, word):
            self.start, self.end, self.word = start, end, word

    class Aligned:
        word_segments = [Row(0.02, 0.48, "سلام")]

    class FakeWhisperX:
        @staticmethod
        def load_audio(_path):
            return object()

        @staticmethod
        def load_align_model(language_code, device):
            assert language_code == "fa"
            return object()

        @staticmethod
        def align(segments, _model, _meta, _audio, device, return_char_alignments):
            assert return_char_alignments is False
            return Aligned(), None

    monkeypatch.setattr(whisperx_align, "available", lambda: True)
    monkeypatch.setitem(sys.modules, "whisperx", FakeWhisperX())

    out = whisperx_align.align("x.wav", [{"start": 0.0, "end": 0.5, "text": "سلام"}],
                               language="fa")

    assert out["status"] == "aligned"
    assert out["aligner"] == whisperx_align.PERSIAN_ALIGNER
    assert out["words"] == [{"start": 0.02, "end": 0.48, "text": "سلام"}]


def test_the_bridge_fetches_only_the_light_package_not_torch():
    # torch is the heavy part; the engines registry marks it `heavy` and the
    # fetch list here must stay to the bridge itself.
    assert whisperx_align.PACKAGES == ["whisperx"]
    assert "torch" not in whisperx_align.PACKAGES


def test_whisperx_is_still_a_registered_on_demand_engine():
    from core.engine import engines

    names = {e["id"] for e in engines.status()["engines"]}
    assert "whisperx" in names


def test_the_align_status_endpoint_reports_honestly():
    from fastapi.testclient import TestClient

    from app.main import app

    body = TestClient(app).get("/api/captions/align-status").json()

    assert body["available"] is whisperx_align.available()
    assert body["aligner"] == whisperx_align.PERSIAN_ALIGNER
    assert "note" in body
