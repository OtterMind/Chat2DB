"""Tier 1 — transcript-first editing and one-click jump cuts.

The Descript contract: the transcript is the timeline. Deleting a word (or a run
of words) must delete the matching slice of video, and removing fillers / dead
silence is one click with a preview and an undo. Everything here is pure maths on
data the app already measures — word timings from Whisper, silence from the VAD —
so a cut is always a range with a start and an end, never a guess.

The frontend owns the timeline; this module only answers "which ranges would this
edit remove?", as a sorted, merged, non-overlapping list the editor can ripple-
delete in one undoable step.
"""
from __future__ import annotations

from core.engine import fillers as fillers_engine

#: How much breathing room to leave either side of a removed word, in seconds.
#: A cut that lands exactly on a word boundary clips the consonant; a hair of
#: margin keeps the edit from sounding chewed.
WORD_MARGIN = 0.08
#: Silence is trimmed to this much tail on each side, so a jump cut does not
#: butt the next word against an audible click of silence.
SILENCE_MARGIN = 0.12


def _merge(ranges: list[tuple[float, float]]) -> list[tuple[float, float]]:
    """Sorted, de-overlapped copy of a range list."""
    if not ranges:
        return []
    ordered = sorted((max(0.0, float(a)), max(float(a), float(b))) for a, b in ranges)
    out: list[tuple[float, float]] = [ordered[0]]
    for start, end in ordered[1:]:
        if start <= out[-1][1] + 1e-6:
            out[-1] = (out[-1][0], max(out[-1][1], end))
        else:
            out.append((start, end))
    return [(round(a, 3), round(b, 3)) for a, b in out if b > a]


def ranges_from_words(words: list[dict], spans: list[list[int]]) -> list[tuple[float, float]]:
    """Cut ranges for a list of inclusive word-index spans [[i0, i1], …].

    A span that falls outside the word list is ignored rather than raising — the
    UI can send stale indices after a re-transcribe and the worst case is "that
    selection no longer exists", not a crash.
    """
    cuts: list[tuple[float, float]] = []
    for span in spans or []:
        if not span:
            continue
        i0, i1 = int(span[0]), int(span[-1])
        if i0 >= len(words) or i1 < i0:
            continue
        i1 = min(i1, len(words) - 1)
        start = float(words[i0].get("start", 0.0)) - WORD_MARGIN
        end = float(words[i1].get("end", words[i1].get("start", 0.0))) + WORD_MARGIN
        cuts.append((start, end))
    return _merge(cuts)


def filler_ranges(words: list[dict], lang: str | None = None) -> list[tuple[float, float]]:
    """The time ranges occupied by whole-token filler words, per detected language.

    Reuses the conservative filler detector (a filler is removed only as a whole
    token, never inside a real word), so the ranges it returns are exactly the
    words `clean_text` would strip — the preview and the caption always agree.
    """
    cuts = [
        (float(w.get("start", 0.0)) - WORD_MARGIN,
         float(w.get("end", w.get("start", 0.0))) + WORD_MARGIN)
        for w in words or []
        if fillers_engine.clean_text(str(w.get("word") or w.get("text") or "")) == ""
        and str(w.get("word") or w.get("text") or "").strip() != ""
    ]
    return _merge(cuts)


def silence_cuts(silences: list[dict], minimum: float = 0.4,
                 keep: float = SILENCE_MARGIN) -> list[tuple[float, float]]:
    """The portion of each silent gap beyond `keep` on each side, when the gap is
    longer than `minimum`. Short pauses stay — a human pause is rhythm, not waste.
    """
    cuts: list[tuple[float, float]] = []
    for s in silences or []:
        start = float(s.get("start", 0.0))
        end = float(s.get("end", start))
        if end - start < minimum:
            continue
        cuts.append((start + keep, end - keep))
    return _merge(cuts)


def jumpcut(words: list[dict], silences: list[dict], *,
            remove_fillers: bool = True, remove_silence: bool = True,
            minimum_silence: float = 0.4) -> dict:
    """The combined jump-cut: fillers + dead silence, as keep/cut range lists.

    Returned as both `cuts` and `keep` so the UI can draw the preview lanes and
    the editor can apply either representation; they are exact complements.
    """
    cuts: list[tuple[float, float]] = []
    if remove_fillers:
        cuts += filler_ranges(words)
    if remove_silence:
        cuts += silence_cuts(silences, minimum=minimum_silence)
    cuts = _merge(cuts)

    duration = 0.0
    for w in words or []:
        duration = max(duration, float(w.get("end", 0.0)))
    for s in silences or []:
        duration = max(duration, float(s.get("end", 0.0)))

    keep: list[tuple[float, float]] = []
    cursor = 0.0
    for start, end in cuts:
        if start > cursor:
            keep.append((round(cursor, 3), round(start, 3)))
        cursor = max(cursor, end)
    if cursor < duration:
        keep.append((round(cursor, 3), round(duration, 3)))

    removed = round(sum(b - a for a, b in cuts), 3)
    return {
        "cuts": cuts,
        "keep": keep,
        "duration": round(duration, 3),
        "removed": removed,
        "kept": round(duration - removed, 3),
    }
