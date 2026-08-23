"""The offline planner must understand real requests in both languages."""
from __future__ import annotations

from core.assistant import planner

TIMELINE = {
    "tracks": [{"id": "v1", "kind": "video"}],
    "clips": [
        {"id": "c1", "trackId": "v1", "start": 0, "duration": 5, "label": "intro"},
        {"id": "c2", "trackId": "v1", "start": 5, "duration": 4, "label": "body"},
    ],
    "transitions": [],
}


def ops_for(prompt: str) -> list[str]:
    return [op["op"] for op in planner.rule_based_plan(prompt, TIMELINE).ops]


def test_english_intents():
    assert "removeSilence" in ops_for("please remove the silence")
    assert "splitScenes" in ops_for("split it at every scene change")
    assert "addTransitionsEverywhere" in ops_for("add fade transitions between all clips")
    assert "reverse" in ops_for("play this clip in reverse")


def test_persian_intents():
    assert "removeSilence" in ops_for("سکوت‌ها را حذف کن")
    assert "splitScenes" in ops_for("در محل تغییر نما برش بزن")
    assert "setSpeed" in ops_for("سرعت را ۲ برابر کن")
    assert "mute" in ops_for("این کلیپ را بی‌صدا کن")


def test_values_are_parsed_and_clamped():
    plan = planner.rule_based_plan("make it 3x faster", TIMELINE)
    speed = next(op for op in plan.ops if op["op"] == "setSpeed")
    assert speed["speed"] == 3

    plan = planner.rule_based_plan("speed 99x", TIMELINE)
    speed = next(op for op in plan.ops if op["op"] == "setSpeed")
    assert speed["speed"] == 4  # clamped to the documented range

    plan = planner.rule_based_plan("set volume to 40%", TIMELINE)
    assert next(op for op in plan.ops if op["op"] == "setVolume")["volume"] == 0.4


def test_export_format_is_recognised():
    plan = planner.rule_based_plan("export it for shorts", TIMELINE)
    export = next(op for op in plan.ops if op["op"] == "setExport")
    assert (export["width"], export["height"]) == (1080, 1920)


def test_unknown_request_is_honest():
    plan = planner.make_plan("compose a symphony", TIMELINE, prefer_llm=False)
    assert plan.ops == []
    assert "could not" in plan.explanation.lower()


def test_only_whitelisted_operations_survive_parsing():
    ops, _ = planner._parse_ops('{"ops":[{"op":"deleteEverything"},{"op":"mute","muted":true}],"explanation":"x"}')
    assert [op["op"] for op in ops] == ["mute"]


def test_colour_and_audio_intents():
    assert "setFilter" in ops_for("give it a cinematic look")
    assert "setFilter" in ops_for("فیلتر سیاه و سفید بزن")
    assert "setAdjust" in ops_for("make it brighter")
    assert "setAnimation" in ops_for("zoom in at the start")
    assert "denoise" in ops_for("remove the background noise")
    assert "enhanceVoice" in ops_for("صدا رو تمیز کن با بهبود صدا")


def test_new_operations_are_in_the_whitelist():
    for name in ("setFilter", "setAdjust", "setAnimation", "denoise", "enhanceVoice"):
        assert name in planner.OPERATIONS


def test_caption_and_text_intents():
    assert "generateCaptions" in ops_for("add captions to this")
    assert "generateCaptions" in ops_for("زیرنویس بساز")
    plan = planner.rule_based_plan("add captions word by word at the top", TIMELINE)
    names = [op["op"] for op in plan.ops]
    assert names.count("styleCaptions") >= 1
    assert any(op.get("animateWords") for op in plan.ops if op["op"] == "styleCaptions")


def test_text_content_is_extracted():
    plan = planner.rule_based_plan('add a title that says Hello World', TIMELINE)
    text_op = next(op for op in plan.ops if op["op"] == "addText")
    assert "hello world" in text_op["text"].lower()


def test_a_missing_http_client_falls_back_instead_of_failing(monkeypatch):
    """Regression: `import requests` inside the LLM path 500'd the endpoint.

    The sandbox had no `requests` and every prompt came back as an Internal
    Server Error instead of quietly using the offline rules — the same shape as
    the bug that once broke the AI self-test.
    """
    import builtins

    from core.assistant import planner

    real_import = builtins.__import__

    def without_requests(name, *args, **kwargs):
        if name == "requests":
            raise ModuleNotFoundError("No module named 'requests'")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", without_requests)
    monkeypatch.setattr(planner, "_provider_config", lambda: ("ollama", "", "llama3"))

    plan = planner.make_plan("add fade transitions between all clips", {"tracks": [], "clips": []})

    assert plan.source == "rules"
    assert plan.ops, "the offline planner produced nothing"
