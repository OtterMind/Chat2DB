"""Which moment is worth keeping — read from what was said, not how loud it was.

Until now the strongest moment was the longest stretch of speech or the busiest
picture. That finds the loud parts; it does not find the part where the point is
made. This module reads the transcript and scores each moment on the things that
mark meaning in a sentence, in English and Persian:

* **discourse markers** — "but", "the most important", "because", "so",
  «اما», «مهم‌ترین», «چون», «در نتیجه». These are where an argument turns.
* **questions** — a question sets up an answer, which is a hook.
* **numbers and names** — concrete claims survive being cut out of context.
* **completeness** — a fragment that starts mid-sentence reads as an accident.
* **density** — words per second, so a long pause is not mistaken for content.

It is not understanding. It is a measurable proxy for it, and it is only *part*
of the score: `core.brain.objective` still weighs duration, rhythm and silence,
and the local model in `core.brain.planners` still gets its say. A model that is
not installed changes nothing here — this runs offline, on text we already have.
"""
from __future__ import annotations

import re

from core.engine import persian

MARKERS: dict[str, tuple[str, ...]] = {
    "en": (
        "but ", "however", "the most important", "most important", "the point is",
        "because", "so that", "therefore", "the result", "in the end", "finally",
        "the problem", "the secret", "here is", "remember", "never", "always",
        "first", "second", "third", "the best", "the worst", "actually",
    ),
    "fa": (
        "اما", "ولی", "مهم‌ترین", "مهمترین", "نکته", "چون", "بنابراین", "در نتیجه",
        "نتیجه", "مشکل", "راز", "یادت باشه", "هیچ‌وقت", "همیشه", "اول", "دوم",
        "بهترین", "بدترین", "در واقع", "پس", "یعنی",
    ),
}

#: Sentences that end properly are worth more than fragments.
ENDINGS = (".", "!", "?", "؟", "!", "…")
QUESTIONS = ("?", "؟", "چرا", "چطور", "چگونه", "چیست", "how ", "why ", "what ")


def score_text(text: str) -> float:
    text = persian.normalize(text or '')
    """A 0..1 score for one caption's worth of speech."""
    words = [w for w in re.split(r"\s+", text.strip()) if w]
    if not words:
        return 0.0

    lowered = text.lower()
    points = 0.0

    hits = sum(1 for marker in MARKERS["en"] if marker in lowered)
    hits += sum(1 for marker in MARKERS["fa"] if marker in text)
    points += min(0.35, hits * 0.12)

    if any(q in lowered or q in text for q in QUESTIONS):
        points += 0.15

    if re.search(r"\d", text) or re.search(r"[۰-۹]", text):
        points += 0.1

    if text.strip().endswith(ENDINGS):
        points += 0.1

    # Enough said to be a thought, not so much that it is the whole clip.
    length = len(words)
    if 4 <= length <= 40:
        points += 0.2 * min(1.0, length / 14)

    return max(0.0, min(1.0, points))


def score_window(cues: list[dict], start: float, end: float) -> float:
    """How much meaning sits between two times, per second of speech.

    Density matters: three sharp sentences in four seconds beat the same three
    spread over twenty with the microphone open in between.
    """
    if end <= start:
        return 0.0
    inside = [
        cue for cue in cues
        if float(cue.get("start", 0.0)) < end and float(cue.get("end", 0.0)) > start
    ]
    if not inside:
        return 0.0

    total = 0.0
    spoken = 0.0
    for cue in inside:
        cue_start = max(start, float(cue.get("start", 0.0)))
        cue_end = min(end, float(cue.get("end", 0.0)))
        overlap = max(0.0, cue_end - cue_start)
        if overlap <= 0:
            continue
        total += score_text(str(cue.get("text", ""))) * overlap
        spoken += overlap

    if spoken <= 0:
        return 0.0
    coverage = min(1.0, spoken / (end - start))
    return max(0.0, min(1.0, (total / spoken) * (0.6 + 0.4 * coverage)))


def blend(measured: float, meaning: float, weight: float = 0.5) -> float:
    """Signal and sense, together.

    Neither alone is right: loudness finds energy without content, and text
    finds content the camera may have missed entirely. `weight` is how much of
    the answer comes from the words.
    """
    return max(0.0, min(1.0, (1.0 - weight) * measured + weight * meaning))


# ------------------------------------------------------------------ arc 2.0
#
# The advisors' "narrative arc" as markers, not embeddings: a story the
# transcript tells has a hook early, a payoff late, and question→answer pairs
# in between. Embeddings (sentence-transformers, on-demand) may sharpen this
# later; the marker version is the offline floor and it is what the objective's
# new `narrative_arc` term reads when no engine is fetched.

PAYOFF = ("نتیجه", "خلاصه", "در آخر", "حرف آخر", "پایان", "therefore", "finally",
          "in the end", "to sum up", "so in the end")
HOOK_OPEN = ("امروز", "ببین", "ببینید", "می‌دونستی", "today", "watch this",
             "hey", "did you know", "here is")


def narrative_arc(cues: list[dict]) -> dict:
    """Hook → development → payoff, with the timestamps, measured from text.

    `cues` are `{start, end, text}`. Returns None-fields rather than guesses
    when the transcript is empty — the objective drops the term in that case.
    """
    if not cues:
        return {"hook": None, "payoff": None, "qna": 0, "claim_density": 0.0,
                "arc": 0.0}

    hook: float | None = None
    payoff: float | None = None
    qna = 0
    claims = 0
    for index, cue in enumerate(cues):
        text = cue.get("text", "") or ""
        lowered = text.lower()
        if hook is None and (any(h in lowered or h in text for h in HOOK_OPEN)
                             or score_text(text) >= 0.5):
            hook = float(cue.get("start", 0.0))
        if any(p in lowered or p in text for p in PAYOFF):
            payoff = float(cue.get("start", 0.0))
        if any(q in text for q in ("?", "؟")) and index + 1 < len(cues):
            qna += 1
        claims += len(re.findall(r"\d+", text))
    density = round(min(1.0, claims / max(1, len(cues))), 3)

    arc = (0.5 if hook is not None else 0.0) + (0.5 if payoff is not None else 0.0)
    arc = min(1.0, arc + (0.1 if qna else 0.0))
    return {"hook": hook, "payoff": payoff, "qna": qna, "claim_density": density,
            "arc": round(arc, 3)}
