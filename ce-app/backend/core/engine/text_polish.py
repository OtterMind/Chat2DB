"""Per-language caption polishing — the spelling layer Veed-quality needs.

Whisper's raw text is speech, not typeset text: missing case, stray spaces
around punctuation, Arabic letter shapes in Persian, filler tokens. The
deterministic layers here run offline on every cue; Persian delegates to
`persian.normalize` (Hazm when fetched), English gets true-casing and
punctuation spacing, Arabic gets shape normalisation, and a shared layer
collapses whitespace and repeats. Nothing here invents words — it only cleans
what the recogniser heard, so timings stay honest.
"""
from __future__ import annotations

import re

from core.engine import persian

_WS = re.compile(r"\s+")
_PUNCT_SPACE = re.compile(r"\s+([.,!?;:،۔؟؛:])")
_OPEN_PUNCT = re.compile(r"([(\[])\s+")
_REPEAT = re.compile(r"\b(\w+)( \1)+\b")

_EN_STARTERS = re.compile(r"\b(i|im|i'm|ive|i've|id|i'd|ill|i'll|dont|don't|cant|can't|wont|won't|its|it's|thats|that's|lets|let's)\b", re.I)
_EN_MAP = {
    "i": "I", "im": "I'm", "i'm": "I'm", "ive": "I've", "i've": "I've",
    "id": "I'd", "i'd": "I'd", "ill": "I'll", "i'll": "I'll",
    "dont": "don't", "cant": "can't", "wont": "won't", "its": "it's",
    "thats": "that's", "lets": "let's",
}

_AR_SHAPES = str.maketrans({"ي": "ی", "ك": "ک", "ۀ": "هٔ", "ـ": ""})
_AR_DIAG = re.compile(r"[ً-ْ]")


def _truecase_en(text: str) -> str:
    out = _EN_STARTERS.sub(lambda m: _EN_MAP.get(m.group(1).lower(), m.group(1)), text)
    # sentence starts: after .!? or at the beginning
    def cap(m: str) -> str:
        return m[:2] + m[2].upper() if len(m) > 2 else m
    out = re.sub(r"(^|[.!?]\s+)([a-z])", lambda m: m.group(1) + m.group(2).upper(), out)
    return out


def polish(text: str, lang: str) -> str:
    """Clean one caption line for the given language tag (fa/en/ar/…)."""
    if not text:
        return ""
    lang = (lang or "").lower()
    if lang.startswith(("fa", "per")):
        return persian.normalize(text)
    out = text
    if lang.startswith(("en",)):
        out = _truecase_en(out)
    if lang.startswith(("ar",)):
        out = out.translate(_AR_SHAPES)
        out = _AR_DIAG.sub("", out)
    # shared layer — whitespace first, so the repeat detector sees single spaces
    out = _WS.sub(" ", out)
    out = _PUNCT_SPACE.sub(r"\1", out)
    out = _OPEN_PUNCT.sub(r"\1", out)
    out = _REPEAT.sub(r"\1", out)
    out = _WS.sub(" ", out).strip()
    return out


def polish_words(words: list[dict], lang: str) -> list[dict]:
    """The same cleaning, word by word, so karaoke text matches the line."""
    return [{**w, "text": polish(w.get("text", ""), lang) or w.get("text", "")}
            for w in words]
