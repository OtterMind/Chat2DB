"""Tier 1/2 — the emotional arc and the hook score, as numbers a UI can draw.

Advisors asked for two things the engine already almost had:
  * an **emotional arc** — the whole footage as a curve the user can click to jump
    to a peak (advisor 1's "Emotional Arc Visualizer");
  * a **hook / virality score** shown in the UI, not hidden inside the objective
    (advisor 1's #4, advisor 2's "Hook Lab").

Both are built only from signals this codebase already measures — the reaction
cues of `emotion.py`, the energy envelope of `audio.py`, the motion curve of
`analyze.py` — so the curve and the badge are descriptions of measurements, and
the UI says exactly which measurements produced them. No new dependency, no model
with an unreadable licence, and nothing that blocks the pipeline when a signal is
absent (a missing signal is simply not part of the sum).
"""
from __future__ import annotations

import numpy as np

from core.engine import analyze, audio as audio_engine, emotion


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def emotional_arc(path: str, fps: float = 2.0) -> dict:
    """The footage as a 0..1 curve of "how much is happening", per 1/fps seconds.

    Composition (each term only joins when it could be measured):
      0.5·reaction (crowd/laughter joy) + 0.3·energy + 0.2·motion.
    Returns the series plus which terms were actually present, so the chart can
    label itself honestly.
    """
    terms: list[str] = []
    series: dict[int, float] = {}
    count = 0

    try:
        cues = emotion.audio_cues(path, fps=fps)
        count = cues.frames
        for i in range(count):
            series[i] = 0.5 * cues.joy[i]
        terms.append("reaction")
    except Exception:  # noqa: BLE001 — no audio: the arc is motion+energy only
        cues = None

    try:
        peaks = audio_engine.peaks(path, points=max(8, int(count or 60)))
        env = [float(v) for v in (peaks.get("peaks") or [])]
        duration = float(peaks.get("duration") or 0.0)
        if env and duration > 0 and count:
            for i in range(count):
                idx = min(len(env) - 1, int(len(env) * (i / fps) / duration))
                series[i] = series.get(i, 0.0) + 0.3 * _clamp01(env[idx])
            terms.append("energy")
    except Exception:  # noqa: BLE001
        pass

    try:
        curve = analyze.motion_curve(path, fps=fps, width=96)
        if curve and count:
            vals = np.array(curve, dtype=float)
            low, high = float(vals.min()), float(vals.max())
            spread = max(1e-9, high - low)
            for i in range(count):
                idx = min(len(curve) - 1, int(len(curve) * i / count))
                series[i] = series.get(i, 0.0) + 0.2 * _clamp01((curve[idx] - low) / spread)
            terms.append("motion")
    except Exception:  # noqa: BLE001
        pass

    if not series:
        return {"fps": fps, "points": [], "terms": [], "duration": 0.0}

    weight = {"reaction": 0.5, "energy": 0.3, "motion": 0.2}
    total = sum(weight[t] for t in terms) or 1.0
    points = [{"t": round(i / fps, 2), "score": round(_clamp01(series[i] / total), 3)}
              for i in sorted(series)]
    duration = points[-1]["t"] + 1.0 / fps if points else 0.0
    return {"fps": fps, "points": points, "terms": terms, "duration": round(duration, 2)}


#: Hook bands, in the advisors' own words — a score the user can read at a glance.
HOOK_BANDS = [
    (80, "🔥 Viral", "#EF4444"),
    (60, "⚡ Strong", "#FFB800"),
    (40, "👍 Good", "#10F0A0"),
    (0, "😐 Weak", "#888888"),
]


def hook_score(path: str, start: float = 0.0, end: float = 3.0) -> dict:
    """How hard the first seconds grab, 0–100, with the reasons that built it.

    A Short lives or dies in 0–3 s, and the things that make a hook are measurable
    here: a loud open (energy), movement (motion), people reacting (crowd) and a
    voice getting straight to it (speech). Each contributes a share of the 100 and
    is listed in `reasons`, so the badge is an explanation, not a horoscope.
    """
    reasons: list[str] = []
    score = 0.0

    try:
        cues = emotion.audio_cues(path)
        a, b = emotion.window_value, None
        energy = emotion.window_value(cues, start, end, "energy")
        crowd = emotion.window_value(cues, start, end, "crowd")
        speech = emotion.window_value(cues, start, end, "speech")
        score += 35 * energy
        if energy > 0.4:
            reasons.append(f"opening energy {energy:.2f}")
        score += 30 * crowd
        if crowd > 0.3:
            reasons.append(f"the room reacts early (crowd {crowd:.2f})")
        score += 15 * speech
        if speech > 0.5:
            reasons.append("a voice starts immediately")
    except Exception:  # noqa: BLE001 — no audio: motion carries the hook alone
        pass

    try:
        curve = analyze.motion_curve(path, fps=4.0, width=96)
        if curve:
            vals = np.array(curve, dtype=float)
            low, high = float(vals.min()), float(vals.max())
            first = float(np.mean(vals[: max(1, int((end - start) * 4))]))
            motion = _clamp01((first - low) / max(1e-9, high - low))
            score += 20 * motion
            if motion > 0.5:
                reasons.append(f"the picture moves from frame one ({motion:.2f})")
    except Exception:  # noqa: BLE001
        pass

    score = int(round(max(0.0, min(1.0, score)) * 100))
    label, color = next((l, c) for threshold, l, c in HOOK_BANDS if score >= threshold)
    return {
        "score": score,
        "label": label,
        "color": color,
        "window": {"start": start, "end": end},
        "reasons": reasons or ["no hook signal measured in this window"],
    }
