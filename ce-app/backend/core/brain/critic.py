"""The critic: look at the winning cut once more, fix only what is weak.

The advisors' "living brain" loop (Analyzer → Planner → Critic → Refiner, and
LangGraph's unbounded agent version) is adopted here as a **bounded** native
loop, because the project's invariants say the race, not an agent, decides:

* it revises at most `MAX_ITERATIONS` times (2), so it is a critic, not a loop;
* it replaces ONLY picks whose measured strength sits in the bottom quantile,
  and only with *unused measured highlights* — it never invents a time;
* a revision is kept only if the judge scores it strictly higher, AND never
  when the result would fall below the pure rule plan's score — the floor that
  makes the whole race safe.

The scoreboard line reports it ("rules 0.71 · narrative 0.80 ·
narrative+critic 0.84 → used …"), because a brain that revises silently is a
brain nobody can audit.
"""
from __future__ import annotations

from core.brain.objective import Context, Pick, score_plan

MAX_ITERATIONS = 2
#: A pick this far below the field is "weak" and may be revised.
BOTTOM_QUANTILE = 0.25


def revise(picks: list[Pick], highlights: list[Pick], context: Context,
           rules_score: float | None = None) -> tuple[list[Pick], int, float | None]:
    """Return (revised picks, iterations used, final score).

    Deterministic and measured: weakness is the pick's own measured `score`
    relative to the field, and the replacement is the strongest unused
    highlight that does not overlap the plan.
    """
    if not picks or not highlights:
        return picks, 0, None

    current = list(picks)
    score = score_plan(current, context)
    if score is None:
        return current, 0, None
    floor = rules_score
    if floor is None:
        from core.brain import planners  # noqa: PLC0415

        rule = planners.rule_plan(highlights, context)
        floor_score = score_plan(rule.picks, context) if rule.picks else None
        floor = floor_score.total if floor_score else None

    iterations = 0
    for _ in range(MAX_ITERATIONS):
        scores = sorted(p.score for p in current)
        if not scores:
            break
        cutoff = scores[max(0, int(len(scores) * BOTTOM_QUANTILE) - (1 if len(scores) > 1 else 0))]
        weak = [p for p in current if p.score <= cutoff]
        if not weak:
            break
        used = [(p.start, p.end) for p in current]
        spare = [h for h in highlights
                 if not any(h.start < e - 0.05 and h.end > s + 0.05 for s, e in used)]
        if not spare:
            break
        worst = min(weak, key=lambda p: p.score)
        best = max(spare, key=lambda h: h.score)
        if best.score <= worst.score:
            break  # nothing better is free — revising would be churn
        trial = [p for p in current if p is not worst] + [best]
        trial_score = score_plan(trial, context)
        if trial_score is None:
            break
        if floor is not None and trial_score.total < floor:
            break  # the floor holds: never below the pure rule plan
        if trial_score.total > score.total:
            current, score, iterations = trial, trial_score, iterations + 1
        else:
            break
    return current, iterations, score.total
