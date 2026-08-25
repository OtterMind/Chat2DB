"""Strip the words a speaker uses to buy time, in English and Persian.

A talk — and a lot of vlog-style sports commentary — is full of "um", "you know",
«یعنی», «مثلاً». They carry no information, they make captions longer than the
shot, and they are exactly what a short-form edit should cut. This module removes
them from transcript cues, word timings and all, so the karaoke highlight never
lights a word nobody should read.

It is deliberately conservative: a filler is removed only as a whole token, never
inside a word ("like" must not eat "likely"), and a cue that becomes empty is
dropped rather than left as a blank flash.
"""
from __future__ import annotations

import re

#: Whole-token fillers. Kept short and unambiguous on purpose; a filler list that
#: also removes real words is worse than none.
FILLERS = {
    "en": {"um", "uh", "umm", "uhh", "er", "erm", "like,", "like", "you know",
           "i mean", "kinda", "sorta", "actually,"},
    "fa": {"یعنی", "مثلا", "مثلاً", "در واقع", "خب", "ام", "اوم", "اه", "چی",
           "یعنی،", "خب،"},
}

_ALL = sorted(FILLERS["en"] | FILLERS["fa"], key=len, reverse=True)
_PATTERN = re.compile(
    r"(?<![\w\u0600-\u06FF])(" + "|".join(re.escape(f.rstrip(",،")) for f in _ALL) + r")(?![\w\u0600-\u06FF])",
    re.IGNORECASE,
)


def clean_text(text: str) -> str:
    """The text with fillers removed and whitespace tidied."""
    cleaned = _PATTERN.sub(" ", text)
    cleaned = re.sub(r"\s+", " ", cleaned)
    cleaned = re.sub(r"\s+([,،.!؟?])", r"\1", cleaned)  # no " ," left behind
    cleaned = cleaned.strip().lstrip(",،. ").rstrip(",،. ")
    return cleaned


def clean_cues(cues: list[dict]) -> list[dict]:
    """Cues with filler words removed from text and word timings.

    A word timing whose word is a filler is dropped; a cue left with no words is
    dropped entirely so the timeline never shows a blank caption.
    """
    out = []
    for cue in cues:
        words = [
            w for w in (cue.get("words") or [])
            if clean_text(str(w.get("word", ""))) != ""
        ]
        text = clean_text(str(cue.get("text", "")))
        if not text and not words:
            continue
        new = dict(cue)
        new["text"] = text or " ".join(str(w.get("word", "")) for w in words)
        if cue.get("words") is not None:
            new["words"] = words
        out.append(new)
    return out
