"""The race: every planner answers, arithmetic decides.

    rule plan 0.71 · ollama:qwen2.5 0.83 → used ollama:qwen2.5

That line is the point of this module. It is written into the result, shown in
the app, and it is the honest answer to "did the AI actually help?" — sometimes
it is "no", and then the offline plan is used and says so.

Two guarantees this file exists to keep:

1. **The rule plan is always a candidate.** A language model can only win by
   scoring higher; it can never make the output worse than the offline result.
2. **A tie goes to the rules.** Determinism is worth more than novelty when the
   numbers say the two plans are equally good.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from core.brain import planners
from core.brain.objective import Context, Pick, Score, score_plan


@dataclass
class Result:
    winner: str
    picks: list[Pick]
    scoreboard: list[dict] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "winner": self.winner,
            "picks": [p.as_dict() for p in self.picks],
            "scoreboard": self.scoreboard,
            "line": self.line,
        }

    def as_dict_without_picks(self) -> dict:
        """For a result summary: who won and what everyone scored."""
        return {"winner": self.winner, "scoreboard": self.scoreboard, "line": self.line}

    @property
    def line(self) -> str:
        parts = [f"{row['name']} {row['score']:.2f}" for row in self.scoreboard]
        return " · ".join(parts) + (f" → used {self.winner}" if parts else "")


def _ensemble(scoreboard: list[dict], context: Context) -> planners.Candidate | None:
    """Collaboration, not just competition: a Borda-style vote across planners.

    A pick that several planners independently chose gathers their scores; the
    ensemble is the plan the brains agree on. It competes on the same scoreboard,
    so agreement only wins when it actually scores — consensus is a candidate,
    never a veto.
    """
    rows = [r for r in scoreboard if r.get("picks") and r["score"] > 0]
    if len(rows) < 2:
        return None
    votes: dict[tuple[float, float], list] = {}
    for row in rows:
        for pick in row["picks"]:
            key = (round(pick["start"], 2), round(pick["end"], 2))
            votes.setdefault(key, [0.0, pick, set()])
            votes[key][0] += row["score"]
            votes[key][2].add(row["name"])
    # Genuine agreement only: a pick counts when at least two *different* planners
    # chose it. One planner's solo idea — including a bad model's — is not consensus.
    ordered = [v[1] for v in sorted(votes.values(), key=lambda item: -item[0]) if len(v[2]) >= 2]
    picks = planners._ordered([Pick(p["start"], p["end"], p.get("score", 0.0)) for p in ordered])
    if not picks:
        return None
    return planners.Candidate(name="ensemble", picks=picks,
                              note="the picks the planners agree on")


def race(
    highlights: list[Pick],
    context: Context,
    transcript: list[dict] | None = None,
    use_llm: bool = True,
    model: str | None = None,
    timeout: float = 120.0,
) -> Result:
    """Run the planners, score them all, return the winner and the scoreboard."""
    rules = planners.rule_plan(highlights, context)
    candidates = [rules]

    # Same moments, cut on the music. It is a candidate rather than a rewrite
    # because snapping trades length for rhythm and only the score can weigh that.
    on_the_beat = planners.beat_plan(rules.picks, context)
    if on_the_beat is not None:
        candidates.append(on_the_beat)

    # The 0.9.30 strategies. Each one skips itself when the signal it thinks
    # with is absent — a planner without its sense is not a candidate.
    for extra in (
        planners.narrative_plan(highlights, context),
        planners.retention_plan(highlights, context),
        planners.variety_plan(highlights, context),
        planners.hook_plan(highlights, context),
        planners.emotion_plan(highlights, context),
    ):
        if extra is not None:
            candidates.append(extra)

    if use_llm:
        proposed = planners.ollama_plan(highlights, context, transcript, model=model, timeout=timeout)
        if proposed is not None:
            candidates.append(proposed)

    scoreboard: list[dict] = []
    best_candidate = candidates[0]
    best_score: Score | None = None

    for candidate in candidates:
        score = score_plan(candidate.picks, context) if candidate.picks else None
        scoreboard.append(
            {
                "name": candidate.name,
                "score": round(score.total, 4) if score else 0.0,
                "seconds": round(candidate.seconds, 2),
                "shots": len(candidate.picks),
                "note": candidate.note,
                "terms": score.terms if score else {},
                "skipped": score.skipped if score else ["no plan"],
                "picks": [p.as_dict() for p in candidate.picks],
            }
        )
        if score is None:
            continue
        # Strictly greater: a tie keeps the deterministic plan.
        if best_score is None or score.total > best_score.total:
            best_candidate, best_score = candidate, score

    # The brains collaborating: the ensemble of picks they agree on joins the race
    # as one more candidate, scored like the rest.
    ensemble = _ensemble(scoreboard, context)
    if ensemble is not None:
        escore = score_plan(ensemble.picks, context) if ensemble.picks else None
        if escore is not None:
            scoreboard.append({
                "name": "ensemble", "score": round(escore.total, 4), "seconds": 0.0,
                "shots": len(ensemble.picks), "note": ensemble.note,
                "terms": escore.terms, "skipped": escore.skipped,
                "picks": [p.as_dict() for p in ensemble.picks],
            })
            if best_score is None or escore.total > best_score.total:
                best_candidate, best_score = ensemble, escore

    # The living part: the critic looks at the winning cut once more — at most
    # two revisions, replacements drawn from unused measured highlights, and the
    # pure rule plan's score as a floor it can never fall below.
    from core.brain import critic  # noqa: PLC0415

    rules_score = next((row["score"] for row in scoreboard if row["name"] == "rules"), None)
    revised, iterations, final = critic.revise(
        best_candidate.picks, highlights, context, rules_score)
    if iterations and final is not None and best_score is not None and final > best_score.total:
        name = f"{best_candidate.name}+critic"
        scoreboard.append({"name": name, "score": round(final, 4), "seconds": 0.0,
                           "shots": len(revised), "note": f"{iterations} weak pick(s) revised",
                           "terms": {}, "skipped": []})
        best_candidate = planners.Candidate(name=name, picks=revised, seconds=0.0,
                                            note=f"critic revised {iterations} pick(s)")

    return Result(winner=best_candidate.name, picks=best_candidate.picks, scoreboard=scoreboard)
