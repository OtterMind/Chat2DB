"""The brain interrogates itself: answers key off measured numbers only."""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app
from core.brain import intake

client = TestClient(app)


def _template(**over):
    base = {
        "bpm": 120.0, "median_shot": 2.0, "speech_ratio": 0.6, "aspect": "9:16",
        "hook": {"firstCut": 1.0},
        "shots": [{"duration": 2.0, "transition": "dissolve"},
                  {"duration": 2.0, "transition": ""},
                  {"duration": 2.0, "transition": ""}],
    }
    base.update(over)
    return base


def test_reference_answers_carry_the_number_behind_them():
    qa = intake.answer_reference(_template())

    by_id = {q["id"]: q for q in qa}
    assert by_id["energy"]["a"]["en"] == "Punchy, fast cuts"      # 120 BPM, 2 s shots
    assert by_id["platform"]["a"]["en"] == "Instagram Reels"      # 9:16
    assert by_id["captions"]["a"]["en"] == "Persian"              # 60 % speech
    assert "120" in by_id["energy"]["value"]                      # the number is shown
    assert all(q["why"]["fa"] and q["why"]["en"] for q in qa)


def test_a_calm_landscape_reference_answers_differently():
    qa = intake.answer_reference(_template(bpm=70.0, median_shot=5.0,
                                           speech_ratio=0.1, aspect="16:9"))
    by_id = {q["id"]: q for q in qa}

    assert by_id["energy"]["a"]["en"] == "Calm, longer shots"
    assert by_id["platform"]["a"]["en"] == "YouTube (long)"
    assert by_id["captions"]["a"]["en"] == "No captions"


def test_footage_kind_follows_the_measured_signals():
    sport = intake.answer_footage(_template(), {"speech_ratio": 0.1, "action": 0.8,
                                                "presence": 0.7, "duration": 60})
    talk = intake.answer_footage(_template(), {"speech_ratio": 0.7, "action": 0.1,
                                               "presence": 0.2, "duration": 60})

    kind_s = next(q for q in sport if q["id"] == "kind")
    kind_t = next(q for q in talk if q["id"] == "kind")
    assert kind_s["a"]["en"] == "Sport / action"
    assert kind_t["a"]["en"] == "Talking to camera"

    noise = next(q for q in sport if q["id"] == "fnoise")
    assert "unmeasured" in noise["why"]["en"].lower() or "don't know" in noise["why"]["en"]


def test_the_menu_offers_genuinely_different_starts():
    options = intake.edit_options(_template(), {"speech_ratio": 0.6, "action": 0.2,
                                                "vertical": True})

    assert len(options) >= 4
    assert len({o["id"] for o in options}) == len(options)
    intents = [tuple(sorted(o["intent"].items())) for o in options]
    assert len(set(intents)) == len(intents), "each option must be a different edit"
    punchy = next(o for o in options if o["id"] == "punchy")
    assert punchy["intent"]["seconds"] == 30


def test_the_brain_endpoint_answers_for_both_videos():
    body = client.post("/api/style/brain", json={"template": _template()}).json()

    assert body["reference_qa"] and body["options"]
    assert body["footage_qa"] == []

    body2 = client.post("/api/style/brain",
                        json={"template": _template(), "footage": ""}).json()
    assert body2["footage_qa"] == []
