"""Package C: chapters from the transcript, guarded hook titles, recipes."""
from __future__ import annotations

from core.engine import captions_llm, chapters, style


def test_chapters_split_on_long_gaps_not_mid_word():
    cues = [
        {"start": 0.0, "end": 2.0, "text": "خب امروز می‌خواهیم سرویس را ببینیم"},
        {"start": 2.2, "end": 4.0, "text": "اول با مقدمات شروع می‌کنیم"},
        {"start": 6.0, "end": 8.0, "text": "اما حالا نتیجه را ببینید"},
    ]
    out = chapters.suggest_chapters(cues, duration=8.0)

    assert len(out) == 2
    assert out[0]["end"] == 4.0 and out[1]["start"] == 6.0
    assert "خب" in out[0]["title"] and "اما" in out[1]["title"]


def test_one_unbroken_talk_is_one_chapter():
    cues = [{"start": 0.0, "end": 2.0, "text": "a"}, {"start": 2.2, "end": 4.0, "text": "b"}]
    assert len(chapters.suggest_chapters(cues)) == 1


def test_hook_title_fallback_without_a_model(monkeypatch):
    monkeypatch.setattr("core.brain.planners.ollama_available", lambda model=None, timeout=2.0: None)
    out = captions_llm.hook_title([{"text": "امروز پرش آخر را ببینید"}])

    assert out["provider"] is None
    assert out["title"] == "امروز پرش آخر را ببینید"


def test_recipes_carry_intent_and_a_template():
    out = style.recipes()

    assert len(out) >= 3
    assert all(r["intent"] and r["template"]["shots"] for r in out)


def test_hook_title_rejects_overlong_answers(monkeypatch):
    import json

    monkeypatch.setattr("core.brain.planners.ollama_available", lambda model=None, timeout=2.0: "m")

    class Fake:
        @staticmethod
        def post(*a, **k):
            class R:
                @staticmethod
                def json():
                    return {"response": json.dumps({"title": "word " * 30})}
            return R()

    import sys
    import types

    stub = types.ModuleType("requests")
    stub.post = Fake.post
    monkeypatch.setitem(sys.modules, "requests", stub)
    out = captions_llm.hook_title([{"text": "امروز پرش آخر را ببینید"}])

    assert out["title"] == "امروز پرش آخر را ببینید"  # fallback stands
