"""Persian text cleaning for captions and meaning — built-in, Hazm when present.

Persian ASR output is full of things that render badly in libass and score badly
in meaning: Arabic variants of Persian letters (ي/ي, ك/ک), Latin digits mixed into
a Persian sentence, doubled spaces, missing half-spaces (نیم‌فاصله) in prefixes like
«می» and «‌های», and stray diacritics. This cleans them deterministically so a
caption reads like a typesetter set it, and so discourse scoring sees canonical
tokens.

The built-in rules cover the common cases with zero dependencies. If **Hazm** (MIT)
has been fetched, its `Normalizer` runs first for the harder cases (lemmatization,
ezafe spacing); the built-in pass always runs after, so behaviour is stable with or
without the engine.
"""
from __future__ import annotations

import importlib.util
import re

_AR_TO_FA = str.maketrans({"ي": "ی", "ك": "ک", "ي": "ی", "ۀ": "هٔ", "‌": "‌"})
_DIGITS = str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")
_DIACRITICS = re.compile(r"[\u064B-\u0652]")


def _hazm_normalizer():
    if importlib.util.find_spec("hazm") is None:
        return None
    try:
        from hazm import Normalizer  # noqa: PLC0415

        return Normalizer()
    except Exception:  # noqa: BLE001 — a broken optional engine must not break captions
        return None


def normalize(text: str) -> str:
    """Canonical Persian, safe for libass and for token scoring."""
    if not text:
        return ""
    hazm = _hazm_normalizer()
    if hazm is not None:
        try:
            text = hazm.normalize(text)
        except Exception:  # noqa: BLE001
            pass

    text = text.translate(_AR_TO_FA)
    text = _DIACRITICS.sub("", text)
    # Latin digits inside a Persian run become Persian digits.
    if _has_persian(text):
        text = text.translate(_DIGITS)
    # Half-space for the common prefixes/suffixs.
    text = re.sub(r"\b(می|نمی) +", r"\1‌", text)
    text = re.sub(r" (های|ام|ات|ی|هایم|هایت) ", r"‌\1 ", text)
    text = re.sub(r"[ ]+", " ", text).strip()
    return text


def _has_persian(text: str) -> bool:
    return any("؀" <= ch <= "ۿ" for ch in text)
