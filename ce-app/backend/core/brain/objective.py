"""The judge: how good is a candidate edit, as a number.

An edit is chosen by racing planners against each other (`core.brain.race`), and
a race needs a referee that cannot be argued with. This module is that referee.

The rule it exists to enforce: **the language model never measures, and the
measurements never argue.** A planner may propose any set of moments; what makes
one plan better than another is counted here, from data that was measured —
shot boundaries, beat times, speech ranges, word timings — never from an
opinion about the footage.

Every term is a fraction between 0 and 1, weighted as in
`docs/CuttingEdge/BRAIN_DESIGN.md` §3:

| term | weight | what it counts |
|---|---|---|
| duration fit | 3 | how close the total length is to the target |
| speech integrity | 3 | cuts that do not land inside a spoken word |
| on-beat cuts | 2 | how close the plan's on-beat ratio is to the reference's |
| silence avoided | 2 | share of the result that is not silence |
| highlight strength | 2 | the measured strength of the moments chosen |
| variety | 1 | penalty for using the same piece of footage twice |
| shot-length match | 1 | how closely shot lengths follow the target rhythm |

A term that cannot be measured on this material — no beats in a silent clip, no
speech in a landscape shot — is **dropped and the remaining weights are
renormalised**, rather than being scored as zero or as one. Inventing a number
for something we did not measure is the failure mode this whole design exists to
avoid.
"""
from __future__ import annotations

from dataclasses import dataclass, field

#: How close a cut has to be to a beat to count as "on the beat" (seconds).
BEAT_TOLERANCE = 0.12

WEIGHTS: dict[str, float] = {
    "duration_fit": 3.0,
    "speech_integrity": 3.0,
    "on_beat": 2.0,
    "silence_avoided": 2.0,
    "highlight_strength": 2.0,
    # Weight 3, not 1. A plan that used the same half second twenty times still
    # scored 0.91 under the original weights, because every other term was
    # happy — and the user's word for the result was "amateur". Repetition is
    # not a rounding error in an edit; it is the whole impression.
    "variety": 3.0,
    "shot_length_match": 1.0,
}


@dataclass
class Pick:
    """One piece of the user's footage, chosen for the edit."""

    start: float
    end: float
    score: float = 0.0

    @property
    def duration(self) -> float:
        return max(0.0, self.end - self.start)

    def as_dict(self) -> dict:
        return {"start": round(self.start, 3), "end": round(self.end, 3), "score": round(self.score, 4)}


@dataclass
class Context:
    """Everything the judge is allowed to know — all of it measured."""

    #: Length of the user's source material.
    duration: float = 0.0
    #: Shot lengths the template asks for, in order.
    target_shots: list[float] = field(default_factory=list)
    #: Beat times in the source (empty when there is no music to speak of).
    beats: list[float] = field(default_factory=list)
    #: The reference's own on-beat ratio: matching *it* is the goal, not 100 %.
    reference_cuts_on_beat: float | None = None
    #: Spoken ranges in the source, from silence detection.
    speech: list[tuple[float, float]] = field(default_factory=list)
    #: Word timings from Whisper, when a transcript exists: [{start, end, word}]
    words: list[dict] = field(default_factory=list)
    #: The measured strength of the strongest moment, for normalisation.
    best_highlight: float = 0.0

    @property
    def target_duration(self) -> float:
        return float(sum(self.target_shots))


@dataclass
class Score:
    """A number, and the arithmetic that produced it."""

    total: float
    terms: dict[str, float]
    weights: dict[str, float]
    skipped: list[str]

    def as_dict(self) -> dict:
        return {
            "total": round(self.total, 4),
            "terms": {k: round(v, 4) for k, v in self.terms.items()},
            "weights": self.weights,
            "skipped": self.skipped,
        }


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def _overlap(a: tuple[float, float], b: tuple[float, float]) -> float:
    return max(0.0, min(a[1], b[1]) - max(a[0], b[0]))


# ------------------------------------------------------------------ the terms


def duration_fit(picks: list[Pick], context: Context) -> float | None:
    target = context.target_duration
    if target <= 0:
        return None
    total = sum(p.duration for p in picks)
    return _clamp01(1.0 - abs(total - target) / target)


def speech_integrity(picks: list[Pick], context: Context) -> float | None:
    """Cuts that do not fall inside a spoken **word**.

    Only word timings can answer this. A speech *range* is far too coarse — a
    twenty-second range of continuous talking would mark every possible cut as
    "inside speech", which is not a measurement of anything. The first version
    of this function did exactly that and scored a flawless plan 0.82; without a
    transcript the term is dropped instead.
    """
    if not context.words or not picks:
        return None
    spans = [
        (float(w.get("start", 0.0)), float(w.get("end", 0.0)))
        for w in context.words
        if float(w.get("end", 0.0)) > float(w.get("start", 0.0))
    ]
    if not spans:
        return None

    cuts = [p.start for p in picks] + [p.end for p in picks]
    # A boundary that coincides with the edge of a word is clean; one that lands
    # in its middle is the mistake this term exists to punish.
    inside = 0
    for cut in cuts:
        for start, end in spans:
            if start + 0.02 < cut < end - 0.02:
                inside += 1
                break
    return _clamp01(1.0 - inside / len(cuts))


def on_beat(picks: list[Pick], context: Context) -> float | None:
    """How close the plan's on-beat ratio is to the reference's own.

    Matching the reference is the goal. A template whose cuts ignore the music
    should not be rebuilt as a metronome.
    """
    if not context.beats or len(picks) < 2:
        return None
    cuts: list[float] = []
    cursor = 0.0
    for pick in picks[:-1]:
        cursor += pick.duration
        cuts.append(cursor)
    if not cuts:
        return None
    hits = sum(1 for cut in cuts if min(abs(cut - b) for b in context.beats) <= BEAT_TOLERANCE)
    ratio = hits / len(cuts)
    target = context.reference_cuts_on_beat
    if target is None:
        return _clamp01(ratio)
    return _clamp01(1.0 - abs(ratio - target))


def silence_avoided(picks: list[Pick], context: Context) -> float | None:
    if not context.speech or not picks:
        return None
    total = sum(p.duration for p in picks)
    if total <= 0:
        return None
    spoken = sum(
        sum(_overlap((p.start, p.end), span) for span in context.speech) for p in picks
    )
    return _clamp01(spoken / total)


def highlight_strength(picks: list[Pick], context: Context) -> float | None:
    if not picks:
        return None
    best = context.best_highlight or max((p.score for p in picks), default=0.0)
    if best <= 0:
        return None
    return _clamp01(sum(p.score for p in picks) / (best * len(picks)))


def variety(picks: list[Pick], context: Context) -> float | None:
    """Reusing the same seconds of footage twice is the cheapest way to fill time."""
    if len(picks) < 2:
        return None
    total = sum(p.duration for p in picks)
    if total <= 0:
        return None
    repeated = 0.0
    for index, pick in enumerate(picks):
        for other in picks[index + 1 :]:
            repeated += _overlap((pick.start, pick.end), (other.start, other.end))
    return _clamp01(1.0 - repeated / total)


def shot_length_match(picks: list[Pick], context: Context) -> float | None:
    if not context.target_shots or not picks:
        return None
    pairs = list(zip(picks, context.target_shots))
    if not pairs:
        return None
    error = 0.0
    for pick, wanted in pairs:
        if wanted <= 0:
            continue
        error += min(1.0, abs(pick.duration - wanted) / wanted)
    return _clamp01(1.0 - error / len(pairs))


TERMS = {
    "duration_fit": duration_fit,
    "speech_integrity": speech_integrity,
    "on_beat": on_beat,
    "silence_avoided": silence_avoided,
    "highlight_strength": highlight_strength,
    "variety": variety,
    "shot_length_match": shot_length_match,
}


def score_plan(picks: list[Pick], context: Context) -> Score:
    """Score one candidate. Terms that cannot be measured are dropped, not faked."""
    terms: dict[str, float] = {}
    skipped: list[str] = []
    for name, function in TERMS.items():
        value = function(picks, context)
        if value is None:
            skipped.append(name)
            continue
        terms[name] = float(value)

    if not terms:
        # Nothing measurable at all — say so with a zero and an empty breakdown
        # rather than pretending the plan is perfect.
        return Score(total=0.0, terms={}, weights={}, skipped=skipped)

    weights = {name: WEIGHTS[name] for name in terms}
    total_weight = sum(weights.values())
    total = sum(terms[name] * weights[name] for name in terms) / total_weight
    return Score(total=total, terms=terms, weights=weights, skipped=skipped)
