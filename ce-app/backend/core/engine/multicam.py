"""Multi-cam: line the angles up, then let the room choose (advisors' B3).

Two phones on tripods at a volleyball match record the same rally and are not
started at the same instant — sometimes a second apart, sometimes ten. Nothing
downstream means anything until that gap is known, so step one is a measurement
with a known answer: the **normalised cross-correlation** of the two audio
tracks. The lag at its peak *is* the offset, and the height of the peak *is* the
confidence (1.0 = the same sound, 0.2 = a guess). An angle with no usable audio
says so instead of pretending to be aligned.

Step two is the switch. The advisors asked for audio cues — applause, whoosh,
who talks — and those are exactly what `core/engine/emotion.py` already measures
per 250 ms, so the switcher reads the same numbers the highlight scorer does:
one angle is on screen until another angle is clearly more alive, and "clearly"
is a margin plus a minimum dwell, because a cut every 250 ms is a strobe, not an
edit. Cuts can snap to the beat grid when there is one.

Nothing here is automatic about the *taste*: the plan is returned as a list of
segments for the editor to look at and accept, and every segment carries the
reason it exists.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from core.engine import audio as audio_engine
from core.engine import compose, emotion

#: 8 kHz is plenty for cross-correlation and a quarter of the decode cost.
SR = 8_000
#: How far apart two handheld recordings may be and still be found (seconds).
MAX_LAG = 15.0
#: How much of each angle is correlated. Long files cost O(n log n) for nothing.
CORRELATE_SECONDS = 90.0
#: A switch has to be *clearly* better, or the picture flickers between angles.
MARGIN = 0.08


@dataclass
class Angle:
    path: str
    duration: float
    has_audio: bool


def probe_angles(paths: list[str]) -> list[Angle]:
    """What each angle is: how long, and whether it has sound to align on."""
    out: list[Angle] = []
    for path in paths:
        info = compose.probe_media(path)
        out.append(Angle(path=path, duration=float(info.get("duration") or 0.0),
                         has_audio=bool(info.get("has_audio"))))
    return out


def _signal(path: str) -> np.ndarray | None:
    try:
        samples = audio_engine.decode_mono(path, SR)
    except Exception:  # noqa: BLE001 — a video lane with no audio is normal
        return None
    limit = int(CORRELATE_SECONDS * SR)
    samples = samples[:limit] if samples.size > limit else samples
    if samples.size < SR:  # under a second is not a signal to align on
        return None
    std = float(np.std(samples))
    if std <= 1e-6:  # silence correlates perfectly with silence, and means nothing
        return None
    return (samples - float(np.mean(samples))) / std


def _best_lag(reference: np.ndarray, other: np.ndarray) -> tuple[int, float]:
    """The lag (samples) that lines `other` onto `reference`, and the NCC there.

    Both inputs are zero-mean and unit-variance, so the correlation value *is*
    the normalised coefficient: 1.0 means identical audio, and the number can be
    shown to the user as the confidence it actually is.

    The operand order is the sign convention and was verified numerically, not
    reasoned about: `irfft(rfft(b)·conj(rfft(a)))` peaks at **+delay** when `b`
    starts later than `a` (§ the multicam tests). Swapped, it silently reports
    every offset with the wrong sign and the whole edit lands two seconds off.
    """
    size = 1 << int(np.ceil(np.log2(reference.size + other.size)))
    correlation = np.fft.irfft(np.fft.rfft(other, size) * np.conj(np.fft.rfft(reference, size)))
    correlation /= reference.size
    limit = int(MAX_LAG * SR)
    positive = correlation[: min(limit, correlation.size)]
    negative = correlation[max(0, correlation.size - limit):]
    best_forward = int(np.argmax(positive)) if positive.size else 0
    best_backward = int(np.argmax(negative)) if negative.size else 0
    forward_value = float(positive[best_forward]) if positive.size else -1.0
    backward_value = float(negative[best_backward]) if negative.size else -1.0
    if forward_value >= backward_value:
        return best_forward, forward_value
    return -(negative.size - best_backward), backward_value


def align(paths: list[str]) -> dict:
    """Offsets of every angle relative to the first, with the confidence of each.

    `offset` is in seconds and is **added to the timeline position**: a positive
    offset means that angle started later, so its first `offset` seconds happen
    before the reference's first frame.
    """
    angles = probe_angles(paths)
    if len(angles) < 2:
        return {"ok": False, "method": "none", "offsets": [0.0] * len(angles),
                "confidence": [0.0] * len(angles),
                "notes": ["multi-cam needs at least two angles"]}

    reference = _signal(angles[0].path)
    offsets = [0.0] * len(angles)
    confidence = [1.0] + [0.0] * (len(angles) - 1)
    notes: list[str] = []
    if reference is None:
        notes.append("the first angle has no usable audio — offsets stay 0 and every "
                     "cut is a guess. Set the offset by hand, or align on a clap.")
        return {"ok": False, "method": "none", "offsets": offsets, "confidence": confidence,
                "notes": notes, "angles": [a.__dict__ for a in angles]}

    for index in range(1, len(angles)):
        other = _signal(angles[index].path)
        if other is None:
            notes.append(f"angle {index + 1} has no usable audio — left at offset 0")
            continue
        lag, value = _best_lag(reference, other)
        offsets[index] = round(lag / SR, 3)
        confidence[index] = round(max(0.0, min(1.0, value)), 3)
        if value < 0.3:
            notes.append(f"angle {index + 1} lines up at {offsets[index]:+.2f}s but the "
                         f"match is weak ({value:.2f}) — check it before trusting it")
    strong = sum(1 for value in confidence[1:] if value >= 0.3)
    method = "audio-xcorr" if strong == len(angles) - 1 else ("partial" if strong else "none")
    return {"ok": strong > 0, "method": method, "offsets": offsets, "confidence": confidence,
            "notes": notes, "angles": [a.__dict__ for a in angles]}


def _runs(chosen: list[int]) -> list[tuple[int, int, int]]:
    """[(first frame, frame after the last, angle), …] for a per-frame choice list."""
    if not chosen:
        return []
    out: list[tuple[int, int, int]] = []
    start = 0
    for index in range(1, len(chosen) + 1):
        if index < len(chosen) and chosen[index] == chosen[start]:
            continue
        out.append((start, index, chosen[start]))
        start = index
    return out


def _activity(cues: emotion.Cues, at: float, mode: str) -> float:
    """How alive one angle is at one instant, under the chosen rule."""
    start = max(0.0, at - 0.5)
    speech = emotion.window_value(cues, start, at + 0.25, "speech")
    crowd = emotion.window_value(cues, start, at + 0.25, "crowd")
    energy = emotion.window_value(cues, start, at + 0.25, "energy")
    if mode == "speech":
        return 0.75 * speech + 0.25 * energy
    if mode == "crowd":
        return 0.8 * crowd + 0.2 * energy
    return 0.5 * speech + 0.35 * crowd + 0.15 * energy


def switch_plan(paths: list[str], offsets: list[float] | None = None, *,
                mode: str = "balanced", dwell: float = 1.2,
                beats: list[float] | None = None,
                step: float = 0.25) -> dict:
    """Which angle is on screen, and when — the plan the editor accepts or edits.

    `offsets` comes from `align()`; when it is absent every angle is assumed to
    start together, and the plan says so, because a plan built on an assumption
    must not read like a measurement.
    """
    angles = probe_angles(paths)
    if len(angles) < 2:
        return {"ok": False, "segments": [], "notes": ["multi-cam needs at least two angles"]}
    offsets = list(offsets or [0.0] * len(angles))
    if len(offsets) != len(angles):
        return {"ok": False, "segments": [], "notes": ["one offset per angle, please"]}

    cues: list[emotion.Cues | None] = []
    for angle in angles:
        try:
            cues.append(emotion.audio_cues(angle.path))
        except Exception:  # noqa: BLE001 — a silent angle contributes no opinion
            cues.append(None)

    notes: list[str] = []
    if not offsets:
        notes.append("no offsets given — the angles are assumed to start together")
    start = max([0.0] + [float(o) for o in offsets])
    end = min(float(o) + a.duration for o, a in zip(offsets, angles))
    if end - start < dwell:
        return {"ok": False, "segments": [], "notes": [*notes, "the angles do not overlap"]}

    frames: list[int] = []
    values: list[float] = []
    whoosh_at: list[int] = []
    time = start
    while time < end - 0.05:
        best, best_value = 0, -1.0
        for index, angle_cues in enumerate(cues):
            if angle_cues is None:
                continue
            value = _activity(angle_cues, time + offsets[index], mode)
            if value > best_value:
                best, best_value = index, value
        frames.append(best)
        values.append(best_value)
        # A whoosh is a transition in the material itself — a swing, a whip-pan.
        # The switcher treats it as a licence to change angle early.
        for index, angle_cues in enumerate(cues):
            if angle_cues is not None and emotion.window_value(
                angle_cues, time + offsets[index] - 0.25, time + offsets[index] + 0.25, "whoosh"
            ) > 0.35:
                whoosh_at.append(len(frames) - 1)
                break
        time += step

    if not frames:
        return {"ok": False, "segments": [], "notes": [*notes, "nothing to plan over"]}

    # ---- dwell + hysteresis: a switch has to earn the cut ------------------
    chosen: list[int] = [frames[0]]
    held = 1  # frames the current angle has been on screen
    jumps = set(whoosh_at)
    for index in range(1, len(frames)):
        current = chosen[-1]
        if frames[index] == current:
            chosen.append(current)
            held += 1
            continue
        clearly_better = values[index] >= values[index - 1] + MARGIN or index in jumps
        if held * step < dwell or not clearly_better:
            chosen.append(current)  # too soon, or not clearly better: hold the angle
            held += 1
        else:
            chosen.append(frames[index])
            held = 1

    # A run shorter than the dwell at the very end is a cut that earns nothing —
    # fold it back into the angle before it, or the promise in the notes is a lie.
    runs = _runs(chosen)
    if len(runs) > 1:
        last_start, last_end, last_angle = runs[-1]
        if (last_end - last_start) * step < dwell:
            for index in range(last_start, last_end):
                chosen[index] = runs[-2][2]

    segments: list[dict] = []
    for run_start, run_end, angle_index in _runs(chosen):
        seg_start = start + run_start * step
        seg_end = start + run_end * step if run_end < len(chosen) else end
        if beats:  # land the cut on the music when the music is close by
            near = min(beats, key=lambda b: abs(b - seg_start))
            if abs(near - seg_start) <= 0.15:
                seg_start = near
        segments.append({"start": round(seg_start, 3), "end": round(seg_end, 3),
                         "angle": angle_index,
                         "src": angles[angle_index].path,
                         # where in that angle's own file this segment lives
                         "offset": round(seg_start + offsets[angle_index], 3)})

    share = [round(100 * sum(1 for c in chosen if c == i) / len(chosen), 1)
             for i in range(len(angles))]
    return {
        "ok": True,
        "mode": mode,
        "dwell": dwell,
        "step": step,
        "segments": segments,
        "switches": max(0, len(segments) - 1),
        "share": share,
        "offsets": offsets,
        "span": {"start": round(start, 3), "end": round(end, 3)},
        "notes": [
            *notes,
            f"{len(segments)} segments, {max(0, len(segments) - 1)} switches over "
            f"{end - start:.1f}s — no angle shorter than {dwell:.1f}s",
            f"on-screen share: " + ", ".join(f"angle {i + 1} {s:.0f}%" for i, s in enumerate(share)),
        ],
    }
