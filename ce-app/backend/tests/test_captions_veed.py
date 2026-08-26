"""Veed-grade captions: polish, SRT round-trip, guarded LLM, quality ladder."""
from __future__ import annotations

import pytest

from core.engine import captions_llm, subtitles, text_polish, transcribe


# ---------------------------------------------------------------- polish


def test_english_truecase_and_punctuation():
    out = text_polish.polish("im going to the market , and i wont be late  .", "en")

    assert out.startswith("I'm going")
    assert "won't" in out
    assert " ," not in out and " ." not in out


def test_persian_delegates_to_the_existing_normaliser():
    out = text_polish.polish("من مي روم ب بازار", "fa")

    assert "می" in out and "مي" not in out


def test_arabic_shapes_and_shared_layers():
    out = text_polish.polish("كتاب   كتاب   كتاب دهها", "ar")

    assert out.count("کتاب") == 1  # the repeated-word layer collapses
    assert "ك" not in out


def test_word_level_polish_keeps_timings():
    words = [{"start": 1.0, "end": 1.5, "text": "im", "prob": 0.9}]
    out = text_polish.polish_words(words, "en")

    assert out[0]["text"] == "I'm" and out[0]["start"] == 1.0


# ---------------------------------------------------------------- SRT


def test_srt_round_trip():
    cues = [{"start": 1.5, "end": 3.0, "text": "سلام دنیا"},
            {"start": 4.0, "end": 6.25, "text": "خط دوم"}]
    text = subtitles.build_srt(cues)

    assert "00:00:01,500 --> 00:00:03,000" in text
    back = subtitles.parse_srt(text)

    assert back == cues or (back[0]["text"] == "سلام دنیا" and back[1]["end"] == 6.25)


def test_srt_import_tolerates_garbage_blocks():
    messy = "1\n00:00:01,000 --> 00:00:02,000\none\n\ngarbage block\n\n2\n00:00:03,000 --> 00:00:04,000\ntwo\n"
    cues = subtitles.parse_srt(messy)

    assert [c["text"] for c in cues] == ["one", "two"]


# ---------------------------------------------------------------- guarded LLM


def test_refine_accepts_only_close_corrections(monkeypatch):
    monkeypatch.setattr("core.brain.planners.ollama_available", lambda model=None, timeout=2.0: "m")
    monkeypatch.setattr(captions_llm, "_ask", lambda model, prompt, timeout=120.0: [
        "من رفتم به بازار",            # close enough → accepted
        "the weather is a purple dinosaur",  # far from original → rejected
    ])
    cues = [{"text": "من رفتم ب بازار"}, {"text": "hello world"}]

    out = captions_llm.refine_cues(cues)

    assert out["cues"][0]["text"] == "من رفتم به بازار"
    assert out["cues"][1]["text"] == "hello world"
    assert out["changed"] == 1


def test_refine_without_a_model_changes_nothing(monkeypatch):
    monkeypatch.setattr("core.brain.planners.ollama_available", lambda model=None, timeout=2.0: None)
    cues = [{"text": "x"}]

    out = captions_llm.refine_cues(cues)

    assert out["provider"] is None and out["cues"] == cues


def test_refine_rejects_count_mismatch(monkeypatch):
    monkeypatch.setattr("core.brain.planners.ollama_available", lambda model=None, timeout=2.0: "m")
    monkeypatch.setattr(captions_llm, "_ask", lambda model, prompt, timeout=120.0: ["only one"])

    out = captions_llm.refine_cues([{"text": "a"}, {"text": "b"}])

    assert out["changed"] == 0


# ---------------------------------------------------------------- quality ladder


def test_asking_for_an_absent_rung_raises_instead_of_silent_downgrade():
    if transcribe.model_present("large-v3"):
        pytest.skip("large-v3 present on this machine")
    with pytest.raises(transcribe.ModelNotDownloaded):
        transcribe.transcribe_to_cues("/nonexistent.wav", quality="best")


def test_the_ladder_maps_to_the_known_models():
    assert transcribe.QUALITY_MODELS == {"fast": "base", "balanced": "medium",
                                         "best": "large-v3"}
