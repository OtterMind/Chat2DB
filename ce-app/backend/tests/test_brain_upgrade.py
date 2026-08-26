"""The 0.9.30 brain upgrade: bus, arc, planners, critic, prior — all measured."""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app
from core.brain import memory, meaning, objective, planners
from core.brain import critic as critic_mod
from core.brain.objective import Context, Pick
from core.engine import analyze, clip_embed, vision
from core.engine import engines as registry

client = TestClient(app)


# ---------------------------------------------------------------- FeatureBus


def test_the_bus_carries_what_it_measured_and_names_what_it_could_not(media):
    from core.engine import features

    bus = features.extract_all_sensors(str(media["gaps"]))

    assert bus.duration > 0
    assert bus.speech, "a tone-with-gaps file has speech"
    assert 0.0 < bus.speech_ratio <= 1.0
    assert isinstance(bus.unknown, list)


def test_complement_of_silences_is_speech():
    from core.engine.features import _complement

    out = _complement([(1.0, 2.0), (4.0, 5.0)], 6.0)
    assert out == [(0.0, 1.0), (2.0, 4.0), (5.0, 6.0)]


# ---------------------------------------------------------------- motion


def test_motion_curve_spans_zero_to_one_and_spikes_at_cuts(media):
    curve = analyze.motion_curve(str(media["shots"]))

    assert curve and max(curve) == 1.0 and min(curve) >= 0.0


def test_motion_keep_ranges_finds_the_burst():
    curve = [0.0] * 8 + [0.9, 0.95, 0.9, 0.85] + [0.0] * 8   # 20 steps of 0.2 s
    ranges = analyze.motion_keep_ranges(curve, 4.0, threshold=0.35, minimum=0.4)

    assert len(ranges) == 1
    assert abs(ranges[0].start - 1.6) < 0.01      # burst begins at step 8
    assert abs(ranges[0].end - 2.6) < 0.01        # ...and closes after step 11


# ---------------------------------------------------------------- meaning 2.0


def test_narrative_arc_finds_hook_and_payoff():
    cues = [
        {"start": 0.0, "end": 2.0, "text": "امروز می‌خوام نشونت بدم مهمترین نکته را"},
        {"start": 2.0, "end": 4.0, "text": "چرا این کار سخت است؟ چون زمان می‌برد"},
        {"start": 4.0, "end": 6.0, "text": "در نتیجه خلاصه اینکه تمرین لازم است"},
    ]
    arc = meaning.narrative_arc(cues)

    assert arc["hook"] == 0.0
    assert arc["payoff"] == 4.0
    assert arc["qna"] >= 1
    assert arc["arc"] >= 0.9


def test_narrative_arc_is_honest_on_empty():
    assert meaning.narrative_arc([])["hook"] is None


# ---------------------------------------------------------------- planners


def _highlights():
    return [Pick(0, 1, 0.9), Pick(2, 3, 0.5), Pick(4, 5, 0.7), Pick(6, 7, 0.3),
            Pick(8, 9, 0.8)]


def _ctx(**over):
    base = dict(target_shots=[1.0, 1.0, 1.0, 1.0])
    base.update(over)
    return Context(**base)


def test_new_planners_only_emit_measured_times():
    ctx = _ctx(narrative={"hook": 0.5, "payoff": 8.5, "arc": 1.0}, platform="tiktok")
    measured = {(p.start, p.end) for p in _highlights()}

    for plan in (planners.narrative_plan(_highlights(), ctx),
                 planners.retention_plan(_highlights(), ctx),
                 planners.variety_plan(_highlights(), ctx)):
        assert plan is not None
        for pick in plan.picks:
            assert (pick.start, pick.end) in measured


def test_planners_skip_themselves_without_their_signal():
    plain = _ctx()
    assert planners.narrative_plan(_highlights(), plain) is None
    assert planners.retention_plan(_highlights(), plain) is None


def test_narrative_plan_keeps_hook_and_payoff():
    ctx = _ctx(narrative={"hook": 0.5, "payoff": 8.5, "arc": 1.0})
    plan = planners.narrative_plan(_highlights(), ctx)

    starts = [p.start for p in plan.picks]
    assert 0.0 in starts and 8.0 in starts


def test_the_race_lists_the_new_planners_on_the_scoreboard():
    from core.brain import race

    ctx = _ctx(narrative={"hook": 0.5, "payoff": 8.5, "arc": 1.0}, platform="tiktok")
    result = race.race(_highlights(), ctx, use_llm=False)

    names = {row["name"] for row in result.scoreboard}
    assert {"rules", "narrative", "retention", "variety"} <= names


# ---------------------------------------------------------------- critic


def test_critic_replaces_a_weak_pick_and_never_falls_below_rules():
    highlights = [Pick(0, 1, 0.9), Pick(2, 3, 0.8), Pick(4, 5, 0.7), Pick(6, 7, 0.1)]
    picks = [Pick(0, 1, 0.9), Pick(6, 7, 0.1)]
    ctx = _ctx(target_shots=[1.0, 1.0])  # same length as the plan, so the swap is fair

    revised, iterations, final = critic_mod.revise(picks, highlights, ctx)

    assert iterations >= 1
    assert all(p.score >= 0.5 for p in revised)
    rules = planners.rule_plan(highlights, ctx)
    from core.brain.objective import score_plan
    assert final >= score_plan(rules.picks, ctx).total - 1e-9


def test_critic_with_no_spare_highlights_changes_nothing():
    picks = [Pick(0, 10, 0.5)]
    revised, iterations, _ = critic_mod.revise(picks, [picks[0]], _ctx())
    assert iterations == 0 and revised == picks


# ---------------------------------------------------------------- objective terms


def test_narrative_arc_term_scores_kept_stamps():
    ctx = _ctx(narrative={"hook": 0.5, "payoff": 8.5, "arc": 1.0})
    full = objective.narrative_arc([Pick(0, 1), Pick(8, 9)], ctx)
    half = objective.narrative_arc([Pick(0, 1), Pick(4, 5)], ctx)

    assert full == 1.0 and half == 0.5
    assert objective.narrative_arc([Pick(0, 1)], _ctx()) is None


def test_platform_pacing_rewards_the_platform_cut_rate():
    fast = objective.platform_pacing([Pick(0, 2), Pick(2, 4)], _ctx(platform="tiktok"))
    slow = objective.platform_pacing([Pick(0, 4), Pick(4, 8)], _ctx(platform="tiktok"))

    assert fast > slow
    assert objective.platform_pacing([Pick(0, 2)], _ctx(platform="tiktok")) is None


def test_visual_variety_needs_vectors_and_skips_without():
    assert objective.visual_variety([Pick(0, 1), Pick(2, 3)], _ctx()) is None
    with_feats = [Pick(0, 1, features=(1.0, 0.0)), Pick(2, 3, features=(0.0, 1.0))]
    assert objective.visual_variety(with_feats, _ctx()) == 1.0


def test_taste_prior_is_bounded_not_a_switch():
    ctx = _ctx(prior={"variety": 99.0})
    score = objective.score_plan([Pick(0, 1, 0.5), Pick(2, 3, 0.5)], ctx)

    assert score.weights["variety"] <= 3.0 * objective.MAX_PRIOR + 1e-9


# ---------------------------------------------------------------- memory


def test_memory_prior_learns_boundedly(monkeypatch, tmp_path):
    monkeypatch.setattr(memory, "_path", lambda: tmp_path / "taste.json")

    assert memory.prior() == {}
    memory.record("accepted", {"variety": 0.9})
    memory.record("rejected", {"variety": 0.3})
    prior = memory.prior()

    assert 1.0 < prior["variety"] <= 1.33
    memory.record("exploded", {})  # unknown outcomes are ignored
    assert memory.prior() == prior


def test_the_feedback_endpoint_returns_the_prior(monkeypatch, tmp_path):
    monkeypatch.setattr(memory, "_path", lambda: tmp_path / "taste.json")

    body = client.post("/api/brain/feedback",
                       json={"outcome": "accepted", "terms": {"variety": 0.8}}).json()
    assert "prior" in body


# ---------------------------------------------------------------- clip & vision


def test_concepts_follow_the_intent():
    assert "a ball in the air" in clip_embed.concepts_for({"kind": "sport"})
    assert len(clip_embed.concepts_for(None)) == 3


def test_semantic_score_degrades_to_none():
    if clip_embed.available():
        return
    assert clip_embed.semantic_score(object(), ["x"]) is None


def test_contact_sheet_spreads_frames_inside_the_window():
    times = vision.contact_sheet_times(2.0, 6.0)

    assert len(times) == 4
    assert all(2.0 < t < 6.0 for t in times)


def test_contact_sheet_without_a_model_is_none():
    if vision.available():
        return
    assert vision.score_contact_sheet("/nonexistent.mp4", 0.0, 1.0) is None


# ---------------------------------------------------------------- registry


def test_the_registry_moved_librosa_to_on_demand_and_rejects_essentia():
    ids = {e["id"] for e in registry.ENGINES}
    rejected = {r["name"] for r in registry.REJECTED}

    assert {"sentence-transformers", "librosa", "open-unmix", "dover"} <= ids
    assert "librosa" not in rejected
    assert "Essentia" in rejected
    assert all(e["licence"] for e in registry.ENGINES)
