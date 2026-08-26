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
    used: list[tuple[float, float]] = []

    def overlaps(start: float, end: float) -> bool:
        return any(start < u_end - 0.05 and end > u_start + 0.05 for u_start, u_end in used)

    for index, wanted in enumerate(shots):
        source = None
        # Strongest moment that is not already on the timeline. The old version
        # was `ordered[index % len(ordered)]`, which on footage with one long
        # take put the *same half second* on the timeline twenty times over.
        for candidate in ordered:
            length = min(wanted, candidate.duration) if wanted > 0 else candidate.duration
            if length > 0.05 and not overlaps(candidate.start, candidate.start + length):
                source = candidate
                break
        if source is None:
            # Genuinely out of fresh material: reuse, in order, rather than fail.
            source = ordered[index % len(ordered)]
        length = min(wanted, source.duration) if wanted > 0 else source.duration
        if length <= 0.05:
            continue
        picks.append(Pick(start=source.start, end=source.start + length, score=source.score))
        used.append((source.start, source.start + length))

    return Candidate(name="rules", picks=picks, seconds=time.time() - started)


def beat_plan(picks: list[Pick], context: Context) -> Candidate | None:
    """The same moments, cut on the music — as a *candidate*, not a rewrite.

    Snapping is not free: a 0.62 s shot on a 0.5 s beat grid becomes 0.5 s, and
    an edit of twenty of them ends up a fifth shorter than the template asked
    for. Musically that is right; against a target length it is wrong. Rather
    than guess which matters more, both plans enter the race and the objective
    function decides — `on_beat` is worth 2, `duration_fit` is worth 3, so a
    small gain in rhythm will not buy a large loss in length.
    """
    if not context.beats or len(picks) < 2:
        return None
    started = time.time()
    snapped = snap_to_beats(picks, context)
    if snapped == picks:
        return None
    return Candidate(name="rules+beats", picks=snapped, seconds=time.time() - started,
                     note="cuts pulled onto the music")


def snap_to_beats(picks: list[Pick], context: Context) -> list[Pick]:
    """Move each cut onto a beat it can actually reach."""
    if not context.beats or len(picks) < 2:
        return picks
    beats = sorted(context.beats)
    # Half a shot: past that you are not nudging a cut, you are choosing a
    # different beat. (At 0.35 of a shot, a 0.62 s shot against a 0.5 s beat
    # could not reach the beat at all and every other cut stayed off it.)
    typical = context.target_duration / max(1, len(picks))
    tolerance = max(0.08, min(0.6, typical * 0.5))

    snapped: list[Pick] = []
    cursor = 0.0
    ideal = 0.0
    for pick in picks:
        # Snap to the beat nearest the *ideal* position, not the current one.
        # Snapping to the current position always rounded the same way and the
        # edit crept shorter with every cut — 6 shots of 0.62 s against a 0.5 s
        # beat lost 0.72 s. Measuring against the ideal keeps the drift bounded.
        ideal += pick.duration
        # Only beats this cut can actually reach: long enough to be a shot,
        # short enough not to stretch past what the source has. Choosing the
        # nearest beat *first* and clamping afterwards left cuts stranded
        # between beats, which is worse than not snapping at all.
        low, high = cursor + 0.2, cursor + min(pick.duration + tolerance, pick.duration * 2)
        reachable = [b for b in beats if low <= b <= high]
        if reachable:
            end_on_timeline = min(reachable, key=lambda b: abs(b - ideal))
        else:
            end_on_timeline = cursor + pick.duration
        length = max(0.2, end_on_timeline - cursor)
        snapped.append(Pick(start=pick.start, end=pick.start + length, score=pick.score))
        cursor += length
    return snapped


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


# ------------------------------------------------- the 0.9.30 planners
#
# Three more strategies join the race. Like the older two, each emits ONLY
# timings that exist in the measured highlight list — a planner that invents a
# moment would be a fiction wearing a measurement's clothes, and the judge
# would not catch it.


def _ordered(picks: list[Pick]) -> list[Pick]:
    return sorted(picks, key=lambda p: p.start)


def _fill(remaining: list[Pick], chosen: list[Pick], want: int) -> list[Pick]:
    out = list(chosen)
    used = [(c.start, c.end) for c in out]

    def overlaps(p: Pick) -> bool:
        return any(p.start < e - 0.05 and p.end > s + 0.05 for s, e in used)

    for pick in sorted(remaining, key=lambda p: -p.score):
        if len(out) >= want:
            break
        if not overlaps(pick):
            out.append(pick)
            used.append((pick.start, pick.end))
    return out


def narrative_plan(highlights: list[Pick], context: Context) -> Candidate | None:
    """Hook early, payoff late, development between — when the transcript has them."""
    started = time.time()
    nar = context.narrative or {}
    if not highlights or not nar.get("arc"):
        return None
    want = max(2, len(context.target_shots) or 4)
    chosen: list[Pick] = []
    for stamp in (nar.get("hook"), nar.get("payoff")):
        if stamp is None:
            continue
        near = min(highlights, key=lambda p: abs((p.start + p.end) / 2 - stamp))
        if near not in chosen:
            chosen.append(near)
    picks = _fill(highlights, chosen, want)
    if not picks:
        return None
    return Candidate(name="narrative", picks=_ordered(picks),
                     seconds=time.time() - started,
                     note="hook→payoff from the transcript's arc")


def retention_plan(highlights: list[Pick], context: Context) -> Candidate | None:
    """Front-load a peak into every bucket — the short-form retention pattern."""
    started = time.time()
    if not highlights or not context.platform:
        return None
    want = max(2, len(context.target_shots) or 4)
    span = max(p.end for p in highlights)
    if span <= 0:
        return None
    chosen: list[Pick] = []
    for index in range(want):
        lo, hi = span * index / want, span * (index + 1) / want
        bucket = [p for p in highlights if p.start < hi and p.end > lo and p not in chosen]
        if bucket:
            chosen.append(max(bucket, key=lambda p: p.score))
    picks = _fill(highlights, chosen, want)
    if not picks:
        return None
    return Candidate(name="retention", picks=_ordered(picks),
                     seconds=time.time() - started,
                     note="a peak every bucket, for the scrolling thumb")


def variety_plan(highlights: list[Pick], context: Context) -> Candidate | None:
    """Successive shots as different from each other as the material allows."""
    started = time.time()
    if len(highlights) < 2:
        return None
    want = max(2, len(context.target_shots) or 4)

    def dist(a: Pick, b: Pick) -> float:
        if a.features and b.features:
            return sum(abs(x - y) for x, y in zip(a.features, b.features)) / len(a.features)
        return abs(a.score - b.score) + abs((a.start - b.start)) / 60.0

    first = max(highlights, key=lambda p: p.score)
    chosen = [first]
    while len(chosen) < min(want, len(highlights)):
        last = chosen[-1]
        candidates = [p for p in highlights if p not in chosen]
        nxt = max(candidates, key=lambda p: dist(p, last) + 0.25 * p.score)
        chosen.append(nxt)
    return Candidate(name="variety", picks=_ordered(chosen),
                     seconds=time.time() - started,
                     note="maximally different neighbours")
