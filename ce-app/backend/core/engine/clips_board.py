"""Tier 2 — the batch clips board and the sports/gym marker lane.

Advisor 2's "one file → a board of ranked Shorts, each with a one-line reason" and
the Sports/Gym Brain, built on the signals the scorer already trusts. Nothing here
invents a ranking: cards come from `_highlights` (the measured candidate moments),
each card's badge from `arc_hook.hook_score`, and its one-line reason from the same
signal values that produced the rank — so "why this clip?" is always answerable.
"""
from __future__ import annotations

import numpy as np

from core.engine import analyze, emotion, style
from core.engine.arc_hook import hook_score

#: Persona → how the board prefers to cut. A sport edit wants short, punchy,
#: action-led windows; a vlog wants the voice; a gym wants the rep rhythm.
PERSONAS = {
    "sport": {"prefer_speech": False, "minimum": 1.0},
    "vlog": {"prefer_speech": True, "minimum": 2.0},
    "gym": {"prefer_speech": False, "minimum": 1.5},
}


def propose(path: str, n: int = 8, persona: str = "sport") -> dict:
    """A board of ranked clip cards, each with score, hook, reason and a thumb."""
    cfg = PERSONAS.get(persona, PERSONAS["sport"])
    try:
        highlights = style._highlights(path, wanted=n, minimum=cfg["minimum"],
                                       prefer_speech=cfg["prefer_speech"])
    except Exception:  # noqa: BLE001 — a file with no measurable moment yields an empty board
        highlights = []

    cards = []
    for index, candidate in enumerate(highlights):
        start = float(candidate.get("start", 0.0))
        end = float(candidate.get("end", start))
        mid = (start + end) / 2
        hook = hook_score(path, start, min(end, start + 3.0))
        signals = candidate.get("signals", {})
        reason = _one_line_reason(signals, persona)
        cards.append({
            "id": f"c{index}",
            "start": round(start, 2),
            "end": round(end, 2),
            "score": candidate.get("score", 0.0),
            "hook": hook["score"],
            "hookLabel": hook["label"],
            "reason": reason,
            "thumb": round(mid, 2),
        })
    cards.sort(key=lambda c: (c["score"] + c["hook"] / 100), reverse=True)
    return {"persona": persona, "cards": cards, "count": len(cards)}


def _one_line_reason(signals: dict, persona: str) -> str:
    """The single human line a card shows — built from what was measured."""
    bits = []
    if signals.get("action", 0) > 0.4:
        bits.append("sharp action peak")
    if signals.get("crowd", 0) > 0.3 or signals.get("emotion", 0) > 0.3:
        bits.append("the crowd reacts")
    if signals.get("speech", 0) > 0.5:
        bits.append("someone talks")
    if signals.get("motion", 0) > 0.5:
        bits.append("strong movement")
    if not bits:
        bits.append("calm moment" if persona == "vlog" else "usable B-roll")
    return " · ".join(bits[:2])


def _local_peaks(values: np.ndarray, min_distance: int = 2) -> list[int]:
    """Indices of local maxima at least `min_distance` apart, strongest first."""
    peaks = []
    for i in range(1, len(values) - 1):
        if values[i] >= values[i - 1] and values[i] > values[i + 1] and values[i] > 0:
            if not peaks or i - peaks[-1] >= min_distance:
                peaks.append(i)
    return peaks


def sports_markers(path: str, fps: float = 4.0) -> dict:
    """Snap-able markers for sport/gym footage, typed by the signal that made them.

    * `spike`  — a whoosh/impact plus a motion peak (a volleyball contact, a drop).
    * `rep`    — a periodic motion peak (the top of a squat/push-up cycle).
    * `crowd`  — a crowd-reaction peak (the celebration after the point).
    Every marker carries the confidence of its own signal so the lane can dim the
    unsure ones instead of pretending they are all equal.
    """
    markers: list[dict] = []

    cues = None
    try:
        cues = emotion.audio_cues(path, fps=fps)
    except Exception:  # noqa: BLE001 — silent footage still gets motion reps
        cues = None

    curve = None
    try:
        curve = np.array(analyze.motion_curve(path, fps=fps, width=96), dtype=float)
    except Exception:  # noqa: BLE001
        curve = None

    if curve is not None and curve.size:
        low, high = float(curve.min()), float(curve.max())
        spread = max(1e-9, high - low)
        norm = (curve - low) / spread
        for i in _local_peaks(norm):
            kind = "rep"
            conf = float(norm[i])
            if cues is not None and i < cues.frames and cues.whoosh[i] > 0.35:
                kind, conf = "spike", max(conf, cues.whoosh[i])
            if conf > 0.4:
                markers.append({"t": round(i / fps, 2), "type": kind,
                                "conf": round(min(1.0, conf), 2)})

    if cues is not None:
        crowd = np.array(cues.crowd)
        for i in _local_peaks(crowd):
            if crowd[i] > 0.4:
                markers.append({"t": round(i / fps, 2), "type": "crowd",
                                "conf": round(min(1.0, float(crowd[i])), 2)})

    markers.sort(key=lambda m: m["t"])
    # de-duplicate markers closer than half a second, keeping the stronger one
    out: list[dict] = []
    for m in markers:
        if out and abs(out[-1]["t"] - m["t"]) < 0.5:
            if m["conf"] > out[-1]["conf"]:
                out[-1] = m
        else:
            out.append(m)
    return {"fps": fps, "markers": out, "count": len(out)}
