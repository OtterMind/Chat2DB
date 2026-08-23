"""Planners: the candidates that race.

Two of them today, both producing the same thing — an ordered list of
`Pick`s, one per shot the template asks for:

* `rule_plan` is deterministic, offline, and always in the race. It is the floor
  the language model has to beat, which is what makes the race safe: a bad or
  slow model can never make the result worse than the offline answer.
* `ollama_plan` asks a local model to *choose and order* moments from the
  measured list. It is handed text only — the transcript, the measured strength
  of each candidate moment, the target rhythm — because a model that cannot see
  the picture must not be asked about the picture.

The model returns indices into the measured list, never timings of its own. That
is the whole safety property: it cannot invent a moment that does not exist, and
the worst it can do is pick a poor order, which the judge then scores lower than
the rule plan.
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, field

from core.brain.objective import Context, Pick

OLLAMA_URL = "http://127.0.0.1:11434"


@dataclass
class Candidate:
    """One planner's answer, with what it cost to get it."""

    name: str
    picks: list[Pick] = field(default_factory=list)
    seconds: float = 0.0
    note: str = ""

    def as_dict(self) -> dict:
        return {
            "name": self.name,
            "picks": [p.as_dict() for p in self.picks],
            "seconds": round(self.seconds, 2),
            "note": self.note,
        }


# ------------------------------------------------------------------ the rules


def rule_plan(highlights: list[Pick], context: Context) -> Candidate:
    """Strongest moments first, trimmed to the template's shot lengths.

    This is what Style Match did before the brain existed, expressed as a
    candidate so it can be scored against the others instead of being assumed
    to be the answer.
    """
    started = time.time()
    if not highlights:
        return Candidate(name="rules", picks=[], seconds=0.0, note="no material")

    ordered = sorted(highlights, key=lambda p: p.score, reverse=True)
    shots = context.target_shots or [p.duration for p in ordered]
    picks: list[Pick] = []
    for index, wanted in enumerate(shots):
        source = ordered[index % len(ordered)]
        length = min(wanted, source.duration) if wanted > 0 else source.duration
        if length <= 0.05:
            continue
        picks.append(Pick(start=source.start, end=source.start + length, score=source.score))
    return Candidate(name="rules", picks=picks, seconds=time.time() - started)


# ----------------------------------------------------------------- the model


PROMPT = """You are choosing the moments for a short video edit.

You cannot see the video. You are given moments that were already MEASURED from
it, each with an index, a length in seconds, and a strength score. Some have a
transcript of what is said in them.

Choose {count} of them, in the order they should appear, so that the edit tells
something: the strongest hook first, no repetition unless there is nothing else,
and prefer moments whose words carry meaning over moments that are merely loud.

Reply with JSON only: {{"picks": [index, index, ...], "why": "one short sentence"}}
Use only indices from the list. Do not invent timings.

Moments:
{moments}
"""


def _moment_lines(highlights: list[Pick], transcript: list[dict] | None) -> str:
    lines = []
    for index, pick in enumerate(highlights):
        said = ""
        if transcript:
            words = [
                str(cue.get("text", "")).strip()
                for cue in transcript
                if float(cue.get("start", 0.0)) < pick.end and float(cue.get("end", 0.0)) > pick.start
            ]
            spoken = " ".join(w for w in words if w)[:160]
            if spoken:
                said = f' says: "{spoken}"'
        lines.append(
            f"{index}: {pick.duration:.1f}s at {pick.start:.1f}s, strength {pick.score:.2f}{said}"
        )
    return "\n".join(lines)


def ollama_available(model: str | None = None, timeout: float = 2.0) -> str | None:
    """The model we would use, or None. Never installs, never downloads."""
    try:
        import requests

        response = requests.get(f"{OLLAMA_URL}/api/tags", timeout=timeout)
        names = [m.get("name", "") for m in response.json().get("models", [])]
    except Exception:  # noqa: BLE001 — not running is a normal state
        return None
    if not names:
        return None
    if model and model in names:
        return model
    return names[0]


def ollama_plan(
    highlights: list[Pick],
    context: Context,
    transcript: list[dict] | None = None,
    model: str | None = None,
    timeout: float = 120.0,
) -> Candidate | None:
    """Ask a local model to choose. Returns None when there is no model to ask."""
    chosen_model = ollama_available(model)
    if not chosen_model or not highlights:
        return None

    started = time.time()
    count = len(context.target_shots) or min(6, len(highlights))
    prompt = PROMPT.format(count=count, moments=_moment_lines(highlights, transcript))

    try:
        import requests

        response = requests.post(
            f"{OLLAMA_URL}/api/generate",
            json={"model": chosen_model, "prompt": prompt, "stream": False, "format": "json"},
            timeout=timeout,
        )
        raw = response.json().get("response", "")
        data = json.loads(raw)
    except Exception as error:  # noqa: BLE001 — the rule plan is still in the race
        return Candidate(name=f"ollama:{chosen_model}", picks=[], seconds=time.time() - started,
                         note=f"no usable answer ({type(error).__name__})")

    picks = _picks_from_indices(data.get("picks"), highlights, context)
    note = str(data.get("why", ""))[:120]
    return Candidate(name=f"ollama:{chosen_model}", picks=picks, seconds=time.time() - started, note=note)


def _picks_from_indices(raw: object, highlights: list[Pick], context: Context) -> list[Pick]:
    """Turn whatever the model said into picks, or into nothing.

    Every index is checked against the measured list and every length comes from
    the template, not from the model. This is the clamp that makes an LLM answer
    safe to score rather than dangerous to apply.
    """
    if not isinstance(raw, list):
        return []
    shots = context.target_shots or []
    picks: list[Pick] = []
    for position, value in enumerate(raw):
        try:
            index = int(value)
        except (TypeError, ValueError):
            continue
        if not 0 <= index < len(highlights):
            continue
        source = highlights[index]
        wanted = shots[position] if position < len(shots) else source.duration
        length = min(wanted, source.duration) if wanted > 0 else source.duration
        if length <= 0.05:
            continue
        picks.append(Pick(start=source.start, end=source.start + length, score=source.score))
        if shots and len(picks) >= len(shots):
            break
    return picks
