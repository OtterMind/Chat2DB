"""Better brains: new planners + the ensemble collaboration, and multi-reference blend."""
from __future__ import annotations

from core.brain import planners, race
from core.brain.objective import Context, Pick
from core.engine import style


def _picks():
    return [Pick(0, 2, 0.9), Pick(3, 5, 0.7), Pick(6, 8, 0.5), Pick(9, 11, 0.3)]


def _ctx(**kw):
    return Context(duration=12, target_shots=[2, 2, 2], **kw)


def test_hook_plan_opens_with_the_strongest():
    plan = planners.hook_plan(_picks(), _ctx())
    assert plan is not None and plan.name == "hook-first"
    assert plan.picks[0].score == 0.9


def test_emotion_plan_skips_without_reaction_and_joins_with_it():
    assert planners.emotion_plan(_picks(), _ctx(emotion=0.0)) is None
    got = planners.emotion_plan(_picks(), _ctx(emotion=0.4))
    assert got is not None and got.name == "emotion"


def test_ensemble_is_a_candidate_in_the_race():
    result = race.race(_picks(), _ctx(emotion=0.3))
    names = [row["name"] for row in result.scoreboard]
    assert "ensemble" in names, "the collaborating brains must appear on the scoreboard"
    assert "hook-first" in names
    assert result.winner  # someone won


def test_blend_templates_averages_and_merges_shots():
    a = {"bpm": 100, "speech_ratio": 0.4, "shots": [{"duration": 1}, {"duration": 2}],
         "motion_mix": {"static": 1.0}, "look": {"saturation": 1.0}, "cuts_on_beat": 0.5}
    b = {"bpm": 140, "speech_ratio": 0.8, "shots": [{"duration": 3}],
         "motion_mix": {"push": 1.0}, "look": {"saturation": 1.4}, "cuts_on_beat": 0.9}
    blended = style.blend_templates([a, b])
    assert blended["bpm"] == 120.0
    assert len(blended["shots"]) == 3
    assert blended["look"]["saturation"] == 1.2
    assert blended["name"] == "blend×2"


def test_blend_endpoint_requires_two():
    from fastapi.testclient import TestClient
    from app.main import app
    client = TestClient(app)
    assert client.post("/api/style/blend", json={"templates": [{}]}).status_code == 400
    ok = client.post("/api/style/blend", json={"templates": [{"bpm": 100}, {"bpm": 140}]})
    assert ok.status_code == 200 and ok.json()["bpm"] == 120.0
