"""Chapters from the transcript — Veed-style structure, offline.

A long video reads better (and exports better to YouTube) when it is split
where the *speaker* changes subject. We approximate that without a model:
a silence gap longer than a breath is a boundary, and discourse markers
(«اما», «خب», «بعد», "so", "next") inside the first line of a block name the
chapter. No marker → «بخش N». Timings come straight from the cues, so a
chapter never starts mid-word.
"""
from __future__ import annotations

import re

_GAP = 1.1  # seconds of silence that read as a subject change

_MARKERS = (
    ("اما", "اما"), ("خب", "خب"), ("بعد", "بعد"), ("حالا", "حالا"),
    ("نکته", "نکته"), ("نتیجه", "نتیجه"), ("اول", "اول"), ("دوم", "دوم"),
    ("so ", "So"), ("next", "Next"), ("but ", "But"), ("finally", "Finally"),
    ("now ", "Now"),
)


def _title_of(text: str, index: int) -> str:
    low = text.lower()
    for needle, pretty in _MARKERS:
        if needle in low:
            clean = re.sub(r"\s+", " ", text).strip()
            return clean[:42]
    return f"بخش {index}" if not re.search(r"[a-zA-Z]{3,}", text) else f"Part {index}"


def suggest_chapters(cues: list[dict], duration: float = 0.0) -> list[dict]:
    """[{start, end, title}] — boundaries only at cue edges, never mid-word."""
    if not cues:
        return []
    ordered = sorted(cues, key=lambda c: c.get("start", 0.0))
    chapters: list[dict] = []
    current_start = ordered[0]["start"]
    first_text = ordered[0].get("text", "")
    number = 1
    for prev, cue in zip(ordered, ordered[1:]):
        gap = cue["start"] - prev["end"]
        if gap >= _GAP:
            chapters.append({"start": round(current_start, 3),
                             "end": round(prev["end"], 3),
                             "title": _title_of(first_text, number)})
            number += 1
            current_start = cue["start"]
            first_text = cue.get("text", "")
    last_end = max(c.get("end", 0.0) for c in ordered)
    if duration:
        last_end = max(last_end, min(duration, duration))
    chapters.append({"start": round(current_start, 3), "end": round(last_end, 3),
                     "title": _title_of(first_text, number)})
    # a single unbroken talk is one chapter — honest, not padded
    return chapters
