"""Tier 3 — Style DNA: the editable fingerprint of a reference edit.

Style Match already measures a reference into a `.cetemplate`; this module turns
that template into a compact, human-readable *DNA* — pacing histogram, motion mix,
colour mood, rhythm and talk — so a creator can see *what* was transferred and an
agent can store/replay it as a recipe. Everything here is derived from numbers the
analyser already measured (`style.Template`); nothing is re-guessed.

It is deliberately a projection, not a new analysis: given the same template it is
deterministic, which is exactly what "DNA" should mean.
"""
from __future__ import annotations

#: Pacing buckets, in seconds — the shape of an edit's rhythm at a glance.
BUCKETS = [(0.0, 1.0, "<1s"), (1.0, 2.0, "1–2s"), (2.0, 4.0, "2–4s"), (4.0, 1e9, "4s+")]


def pacing_histogram(shots: list[dict]) -> list[dict]:
    """How the reference distributes its shot lengths across the pacing buckets."""
    hist = [{ "label": label, "count": 0 } for _, _, label in BUCKETS]
    for shot in shots or []:
        duration = float(shot.get("duration", 0.0))
        for (lo, hi, _label), bucket in zip(BUCKETS, hist):
            if lo <= duration < hi:
                bucket["count"] += 1
                break
    total = sum(b["count"] for b in hist) or 1
    for bucket in hist:
        bucket["share"] = round(bucket["count"] / total, 2)
    return hist


def _mood(look: dict) -> str:
    """A one-word read of the colour look, from the measured adjustments."""
    if not look:
        return "neutral"
    temp = float(look.get("temperature", 0) or 0)
    sat = float(look.get("saturation", 1) or 1)
    if sat < 0.6:
        return "muted"
    if temp > 0.15:
        return "warm"
    if temp < -0.15:
        return "cool"
    if sat > 1.25:
        return "vivid"
    return "neutral"


def style_dna(template: dict) -> dict:
    """The compact fingerprint of a measured template."""
    shots = template.get("shots") or []
    durations = [float(s.get("duration", 0.0)) for s in shots]
    motion_mix = template.get("motion_mix") or {}
    dominant_motion = max(motion_mix, key=lambda k: motion_mix[k], default="static")

    dna = {
        "pacing": pacing_histogram(shots),
        "avg_shot": round(float(template.get("mean_shot", 0.0) or 0.0), 2),
        "cut_rate": round(60.0 / float(template.get("mean_shot", 1.0) or 1.0), 1)
        if float(template.get("mean_shot", 0.0) or 0.0) > 0 else 0.0,
        "motion": dominant_motion,
        "motion_mix": motion_mix,
        "mood": _mood(template.get("look") or {}),
        "bpm": round(float(template.get("bpm", 0.0) or 0.0), 1),
        "cuts_on_beat": round(float(template.get("cuts_on_beat", 0.0) or 0.0), 2),
        "talk": round(float(template.get("speech_ratio", 0.0) or 0.0), 2),
    }
    pace = max(dna["pacing"], key=lambda b: b["count"])["label"] if shots else "n/a"
    dna["line"] = (
        f"{pace} pacing · {dna['motion']} · {dna['mood']} · "
        f"{dna['bpm']:.0f} BPM · {dna['talk']:.0%} talk"
    )
    return dna
