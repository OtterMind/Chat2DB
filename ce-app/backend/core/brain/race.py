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


def race(
    highlights: list[Pick],
    context: Context,
    transcript: list[dict] | None = None,
    use_llm: bool = True,
    model: str | None = None,
    timeout: float = 120.0,
) -> Result:
    """Run the planners, score them all, return the winner and the scoreboard."""
    candidates = [planners.rule_plan(highlights, context)]

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
            }
        )
        if score is None:
            continue
        # Strictly greater: a tie keeps the deterministic plan.
        if best_score is None or score.total > best_score.total:
            best_candidate, best_score = candidate, score

    return Result(winner=best_candidate.name, picks=best_candidate.picks, scoreboard=scoreboard)
