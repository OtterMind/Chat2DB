"""The brain: does the judge measure, and can the race make things worse?

Every fixture here has a right answer built into it, because the whole point of
an objective score is that it is not a matter of taste:

* a plan that matches the target exactly must score higher than one that does not,
* a cut through the middle of a word must cost points,
* a term that cannot be measured must be *dropped*, not invented,
* and the race must never return something worse than the offline rule plan —
  that is the property that makes putting a language model in the loop safe.
"""
from __future__ import annotations

from core.brain import planners, race
from core.brain.objective import Context, Pick, WEIGHTS, score_plan


def _context(**kwargs) -> Context:
    base = dict(
        duration=60.0,
        target_shots=[2.0, 2.0, 2.0],
        beats=[i * 0.5 for i in range(120)],
        reference_cuts_on_beat=1.0,
        speech=[(0.0, 20.0), (24.0, 40.0)],
        # Words that end before every cut in the "perfect" plan below, so a
        # clean edit really is clean.
        words=[
            {"start": 0.1, "end": 1.8, "word": "one"},
            {"start": 4.1, "end": 5.8, "word": "two"},
            {"start": 8.1, "end": 9.8, "word": "three"},
        ],
        best_highlight=1.0,
    )
    base.update(kwargs)
    return Context(**base)


# ----------------------------------------------------------------- the judge


def test_a_perfect_plan_scores_one():
    """Exact lengths, on the beat, inside speech, strongest moments, no reuse."""
    context = _context()
    picks = [Pick(0.0, 2.0, 1.0), Pick(4.0, 6.0, 1.0), Pick(8.0, 10.0, 1.0)]
    score = score_plan(picks, context)

    assert score.total > 0.99, score.as_dict()
    # The 0.9.30 senses (story shape, platform pace, feature vectors) were not
    # handed to this context, so they are skipped — dropped, not faked. Every
    # sense that WAS measured scores one.
    assert set(score.skipped) == {"narrative_arc", "platform_pacing", "visual_variety"}


def test_a_plan_of_the_wrong_length_loses_the_duration_term():
    context = _context(words=[])
    right = score_plan([Pick(0, 2, 1.0), Pick(4, 6, 1.0), Pick(8, 10, 1.0)], context)
    long = score_plan([Pick(0, 6, 1.0), Pick(6, 12, 1.0), Pick(12, 18, 1.0)], context)

    assert long.terms["duration_fit"] < right.terms["duration_fit"]
    assert long.total < right.total


def test_cutting_through_a_word_costs_points():
    words = [{"start": 1.0, "end": 3.0, "word": "hello"}]
    context = _context(words=words)

    clean = score_plan([Pick(3.0, 5.0, 1.0), Pick(6.0, 8.0, 1.0)], context)
    through = score_plan([Pick(2.0, 4.0, 1.0), Pick(6.0, 8.0, 1.0)], context)

    assert through.terms["speech_integrity"] < clean.terms["speech_integrity"]


def test_reusing_the_same_footage_costs_variety():
    context = _context(words=[])
    fresh = score_plan([Pick(0, 2, 1.0), Pick(4, 6, 1.0)], context)
    repeated = score_plan([Pick(0, 2, 1.0), Pick(0, 2, 1.0)], context)

    assert repeated.terms["variety"] < fresh.terms["variety"] == 1.0


def test_speech_integrity_needs_a_transcript_not_a_guess():
    """Without word timings the term is dropped — a speech range is too coarse."""
    with_words = score_plan([Pick(0, 2, 1.0), Pick(4, 6, 1.0)], _context())
    without = score_plan([Pick(0, 2, 1.0), Pick(4, 6, 1.0)], _context(words=[]))

    assert "speech_integrity" in with_words.terms
    assert "speech_integrity" in without.skipped


def test_silence_is_avoided_only_when_speech_was_measured():
    spoken = score_plan([Pick(0, 2, 1.0)], _context(words=[]))
    assert "silence_avoided" in spoken.terms

    silent_source = _context(words=[], speech=[], beats=[])
    quiet = score_plan([Pick(0, 2, 1.0)], silent_source)
    # No speech and no beats: those terms are dropped, not guessed at.
    assert "silence_avoided" in quiet.skipped
    assert "speech_integrity" in quiet.skipped
    assert "on_beat" in quiet.skipped
    assert 0.0 <= quiet.total <= 1.0


def test_dropped_terms_renormalise_the_weights():
    context = _context(words=[], speech=[], beats=[])
    score = score_plan([Pick(0, 2, 1.0), Pick(4, 6, 1.0), Pick(8, 10, 1.0)], context)

    assert set(score.weights) == set(score.terms)
    assert sum(score.weights.values()) < sum(WEIGHTS.values())
    assert score.total <= 1.0


def test_a_plan_off_the_beat_scores_below_one_when_the_reference_was_on_it():
    context = _context(words=[], beats=[i * 0.5 for i in range(120)], reference_cuts_on_beat=1.0)
    on = score_plan([Pick(0, 2, 1.0), Pick(4, 6, 1.0), Pick(8, 10, 1.0)], context)
    off = score_plan([Pick(0, 2.3, 1.0), Pick(4, 6.3, 1.0), Pick(8, 10.3, 1.0)], context)

    assert on.terms["on_beat"] == 1.0
    assert off.terms["on_beat"] < 1.0


# ------------------------------------------------------------------ the race


def _highlights() -> list[Pick]:
    return [Pick(0.0, 4.0, 1.0), Pick(6.0, 10.0, 0.6), Pick(30.0, 34.0, 0.2)]


def test_the_rule_plan_is_always_in_the_race(monkeypatch):
    monkeypatch.setattr(planners, "ollama_plan", lambda *a, **k: None)
    # The 0.9.30 planners join the race when their senses are alive (covered by
    # test_brain_upgrade); here the question is the older invariant — with no
    # model and nothing else to think with, the rule plan stands alone.
    for name in ("narrative_plan", "retention_plan", "variety_plan", "hook_plan", "emotion_plan"):
        monkeypatch.setattr(planners, name, lambda *a, **k: None)
    result = race.race(_highlights(), _context(words=[]), use_llm=True)

    assert result.winner == "rules"
    assert [row["name"] for row in result.scoreboard] == ["rules"]
    assert result.picks, "the offline plan produced nothing"


def test_a_bad_model_answer_can_never_win(monkeypatch):
    """The failure this design exists to prevent: a worse edit because of AI."""
    context = _context(words=[])
    nonsense = planners.Candidate(
        name="ollama:pretend",
        picks=[Pick(30.0, 39.0, 0.2), Pick(30.0, 39.0, 0.2), Pick(30.0, 39.0, 0.2)],
        seconds=1.0,
    )
    monkeypatch.setattr(planners, "ollama_plan", lambda *a, **k: nonsense)
    for name in ("narrative_plan", "retention_plan", "variety_plan", "hook_plan", "emotion_plan"):
        monkeypatch.setattr(planners, name, lambda *a, **k: None)

    offline = race.race(_highlights(), context, use_llm=False)
    with_model = race.race(_highlights(), context, use_llm=True)

    assert with_model.winner == "rules"
    assert with_model.picks == offline.picks
    assert len(with_model.scoreboard) == 2, "the loser is still reported"
    assert with_model.scoreboard[1]["score"] < with_model.scoreboard[0]["score"]


def test_a_better_model_answer_wins_and_says_so(monkeypatch):
    context = _context(words=[])
    # Same strengths as the rules would choose, but placed on the beat and
    # inside speech — measurably better, so it should win.
    better = planners.Candidate(
        name="ollama:good",
        picks=[Pick(0.0, 2.0, 1.0), Pick(6.0, 8.0, 1.0), Pick(12.0, 14.0, 1.0)],
        seconds=2.0,
        note="hook first",
    )
    monkeypatch.setattr(planners, "ollama_plan", lambda *a, **k: better)

    result = race.race(_highlights(), context, use_llm=True)

    assert result.winner == "ollama:good"
    assert "used ollama:good" in result.line
    assert "rules" in result.line


def test_a_tie_keeps_the_deterministic_plan(monkeypatch):
    context = _context(words=[])
    rules = planners.rule_plan(_highlights(), context)
    twin = planners.Candidate(name="ollama:twin", picks=list(rules.picks), seconds=9.0)
    monkeypatch.setattr(planners, "ollama_plan", lambda *a, **k: twin)

    assert race.race(_highlights(), context, use_llm=True).winner == "rules"


def test_the_model_can_only_choose_from_measured_moments():
    """Indices are checked; timings never come from the model."""
    context = _context(words=[])
    highlights = _highlights()

    picks = planners._picks_from_indices([1, 99, "x", -3, 0], highlights, context)

    assert [p.start for p in picks] == [6.0, 0.0]
    assert all(p.duration <= 4.0 for p in picks)
    assert len(picks) <= len(context.target_shots)


def test_an_unreachable_model_is_not_an_error(monkeypatch):
    monkeypatch.setattr(planners, "ollama_available", lambda *a, **k: None)
    assert planners.ollama_plan(_highlights(), _context(), None) is None


# ------------------------------------------------- the brain inside the app


def test_style_match_reports_the_race_in_its_summary(monkeypatch, tmp_path):
    """The scoreboard reaches the user, not just the log."""
    import subprocess

    from core.engine import compose, style

    source = tmp_path / "src.mp4"
    subprocess.run(
        [compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
         "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25:duration=6",
         "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(source)],
        check=True,
    )

    template = {
        "name": "t", "aspect": "16:9", "duration": 4.0,
        "shots": [{"start": 0, "duration": 2.0, "motion": "static", "energy": 0.1},
                  {"start": 2, "duration": 2.0, "motion": "push", "energy": 0.2}],
        "look": {}, "transitions": {"type": "cut"}, "captions": {}, "audio": {},
    }

    built = style.build_timeline(template, str(source), "Test", brain=False)
    brain = built["summary"]["brain"]

    assert brain["winner"] == "rules"
    assert brain["scoreboard"] and brain["scoreboard"][0]["name"] == "rules"
    assert "used rules" in brain["line"]
    assert len([c for c in built["timeline"]["clips"] if c["trackId"] == "v1"]) == 2


def test_a_better_plan_changes_the_edit_that_is_built(monkeypatch, tmp_path):
    """If the race is won by another planner, the timeline must follow it."""
    import subprocess

    from core.brain import planners as brain_planners
    from core.brain.objective import Pick
    from core.engine import compose, style

    source = tmp_path / "src2.mp4"
    subprocess.run(
        [compose.ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
         "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25:duration=8",
         "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(source)],
        check=True,
    )

    template = {
        "name": "t", "aspect": "16:9", "duration": 4.0,
        "shots": [{"start": 0, "duration": 2.0, "motion": "static", "energy": 0.1},
                  {"start": 2, "duration": 2.0, "motion": "static", "energy": 0.1}],
        "look": {}, "transitions": {"type": "cut"}, "captions": {}, "audio": {},
    }

    # Patch the race itself: this test is about the *wiring* — that
    # build_timeline lays out whatever the race chose — not about which planner
    # wins. (It used to patch a fake Ollama answer and rely on it out-scoring
    # the rule plan; once the rule planner learned to spread its picks, the fake
    # lost on merit and the test failed for the right reason in the wrong test.)
    from core.brain import race as brain_race

    def fixed_race(*_args, **_kwargs):
        return brain_race.Result(
            winner="ollama:test",
            picks=[Pick(4.0, 6.0, 1.0), Pick(6.0, 8.0, 1.0)],
            scoreboard=[{"name": "rules", "score": 0.5}, {"name": "ollama:test", "score": 0.9}],
        )

    monkeypatch.setattr(style.brain_race, "race", fixed_race)

    built = style.build_timeline(template, str(source), "Test", brain=True)
    clips = [c for c in built["timeline"]["clips"] if c["trackId"] == "v1"]

    assert built["summary"]["brain"]["winner"] == "ollama:test"
    assert [c["offset"] for c in clips] == [4.0, 6.0], "the winning plan was not used"
