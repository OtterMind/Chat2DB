"""Style analysis: turn a reference video into a template, and a template into an edit.

Two questions, one module:

* **What is this video made of?** Shot lengths, whether the cuts land on the beat,
  how the camera moves in each shot, the colour, where the speech sits, how loud
  the music is under it.
* **What would my footage look like edited that way?** Pick the strongest moments
  of the user's own material and lay them out to the same rhythm, with the same
  framing decisions, look and transitions.

Deliberately dependency-free: frames are decoded by FFmpeg into small grayscale
buffers and everything else is NumPy. OpenCV is not importable on every machine
(it needs libGL), and a style analyser that only runs on some installs is worse
than one that is a little simpler.

Nothing here copies the reference: the template is numbers and names.
"""
from __future__ import annotations

import json
import math
import subprocess
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np

from app.config import settings
from core.brain import meaning as brain_meaning
from core.brain import objective
from core.brain import race as brain_race
from core.brain import editor_brain
from core.engine import analyze as analysis
from core.engine import intent as intent_model
from core.engine import fillers as fillers_engine
from core.engine import vad as vad_engine
from core.engine import cancellation
from core.engine import audio as audio_engine
from core.engine.compose import ffmpeg_binary, probe_media

#: Frames are analysed at this size — enough for motion, cheap enough for a long file.
FRAME = 96

try:  # OpenCV arrives with scenedetect; it is optional here on purpose.
    import cv2  # type: ignore
except Exception:  # pragma: no cover - a trimmed install, or a machine without libGL
    cv2 = None  # type: ignore
#: A cut counts as "on the beat" when it is this close to one.
BEAT_TOLERANCE = 0.12


# --------------------------------------------------------------------- frames


def sample_strip(path: str, start: float, duration: float, count: int, size: int = FRAME) -> list[np.ndarray]:
    """`count` frames spread across a span — in **one** FFmpeg call.

    The first version spawned a process per frame, which cost more in process
    startup than in decoding: analysing a two-minute video meant a hundred
    invocations. One call with an fps filter is the same picture, several times
    faster, and it is why a full style analysis finishes in seconds.
    """
    if duration <= 0 or count <= 0:
        return []
    rate = max(0.05, count / duration)
    out = cancellation.run(
        [
            ffmpeg_binary(), "-hide_banner", "-loglevel", "error",
            "-ss", f"{max(0.0, start):.3f}", "-t", f"{duration:.3f}", "-i", str(path),
            "-vf", f"fps={rate:.4f},scale={size}:{size},format=gray",
            "-frames:v", str(count), "-f", "rawvideo", "-",
        ],
        capture_output=True,
    )
    frame_bytes = size * size
    total = len(out.stdout) // frame_bytes
    return [
        np.frombuffer(out.stdout[i * frame_bytes : (i + 1) * frame_bytes], dtype=np.uint8)
        .reshape(size, size)
        .astype(np.float32)
        for i in range(total)
    ]


def sample_gray(path: str, at: float, size: int = FRAME) -> np.ndarray | None:
    """One frame as a square grayscale array, or None past the end of the file."""
    out = cancellation.run(
        [
            ffmpeg_binary(), "-hide_banner", "-loglevel", "error",
            "-ss", f"{max(0.0, at):.3f}", "-i", str(path), "-frames:v", "1",
            "-vf", f"scale={size}:{size},format=gray", "-f", "rawvideo", "-",
        ],
        capture_output=True,
    )
    if len(out.stdout) < size * size:
        return None
    return np.frombuffer(out.stdout[: size * size], dtype=np.uint8).reshape(size, size).astype(np.float32)


def _phase_shift(first: np.ndarray, second: np.ndarray) -> tuple[float, float]:
    """Translation between two frames, by phase correlation. Returns (dx, dy)."""
    if cv2 is not None:
        window = cv2.createHanningWindow((first.shape[1], first.shape[0]), cv2.CV_32F)
        (dx, dy), _ = cv2.phaseCorrelate(first.astype(np.float32), second.astype(np.float32), window)
        return float(-dx), float(-dy)

    window = np.outer(np.hanning(first.shape[0]), np.hanning(first.shape[1]))
    a = np.fft.rfft2(first * window)
    b = np.fft.rfft2(second * window)
    cross = a * np.conj(b)
    magnitude = np.abs(cross)
    magnitude[magnitude == 0] = 1e-9
    correlation = np.fft.irfft2(cross / magnitude, s=first.shape)
    peak = np.unravel_index(int(np.argmax(correlation)), correlation.shape)
    dy, dx = peak
    if dy > first.shape[0] // 2:
        dy -= first.shape[0]
    if dx > first.shape[1] // 2:
        dx -= first.shape[1]
    return float(dx), float(dy)


def _log_polar_scale(first: np.ndarray, second: np.ndarray) -> float | None:
    """Zoom factor by phase correlation in log-polar space.

    A zoom about the centre is a *shift* along the log-radius axis, which turns
    the hardest measurement in this module into the easiest one. Needs OpenCV;
    without it the brute-force search below is used, which cannot see a pull-out
    reliably — that gap is stated in the docs rather than hidden.
    """
    if cv2 is None:
        return None
    size = first.shape[0]
    centre = (size / 2, size / 2)
    radius = size / 2

    # Translation first. Log-polar turns a zoom into a shift, but it turns a pan
    # into one as well, so a pan would read as a zoom unless the movement is
    # cancelled before the transform. (It did: a sideways pan reported "push".)
    dx, dy = _phase_shift(first, second)
    if abs(dx) > 0.5 or abs(dy) > 0.5:
        matrix = np.float32([[1, 0, dx], [0, 1, dy]])
        second = cv2.warpAffine(
            second.astype(np.float32), matrix, (size, size), flags=cv2.INTER_LINEAR,
            borderMode=cv2.BORDER_REPLICATE,
        )

    flags = cv2.INTER_LINEAR + cv2.WARP_FILL_OUTLIERS + cv2.WARP_POLAR_LOG
    a = cv2.warpPolar(first.astype(np.float32), (size, size), centre, radius, flags)
    b = cv2.warpPolar(second.astype(np.float32), (size, size), centre, radius, flags)
    window = cv2.createHanningWindow((size, size), cv2.CV_32F)
    (shift_x, _), response = cv2.phaseCorrelate(a, b, window)
    if response < 0.05:
        return None
    # x is log-radius: a shift of `shift_x` pixels is a scale of exp(shift * k).
    # The sign was verified against clips built to zoom by a known amount — with
    # it inverted, a push-in reported as a pull-out.
    k = math.log(radius) / size
    return float(math.exp(shift_x * k))


def _best_scale(first: np.ndarray, second: np.ndarray) -> tuple[float, float]:
    """The zoom factor that makes the first frame look most like the second.

    A short brute-force search beats anything cleverer here: five candidate
    scales, normalised correlation, pick the winner. Quadrant divergence was
    tried first and was not reliable — on self-similar content the four blocks
    lock onto different matches and a push-in reads as a pan.
    """
    size = first.shape[0]
    best_scale, best_score = 1.0, -2.0
    for scale in (0.88, 0.94, 1.0, 1.06, 1.12):
        keep = int(round(size / scale))
        if keep < 16:
            continue
        if keep <= size:
            offset = (size - keep) // 2
            candidate = _resize(first[offset : offset + keep, offset : offset + keep], size)
        else:
            small = _resize(first, max(16, int(size * size / keep)))
            candidate = np.zeros_like(first)
            offset = (size - small.shape[0]) // 2
            candidate[offset : offset + small.shape[0], offset : offset + small.shape[1]] = small
        score = _correlation(candidate, second)
        if score > best_score:
            best_scale, best_score = scale, score
    return best_scale, best_score


def _resize(frame: np.ndarray, size: int) -> np.ndarray:
    """Nearest-neighbour resize; good enough for a 96 px motion estimate."""
    rows = (np.arange(size) * frame.shape[0] / size).astype(int).clip(0, frame.shape[0] - 1)
    cols = (np.arange(size) * frame.shape[1] / size).astype(int).clip(0, frame.shape[1] - 1)
    return frame[rows][:, cols]


def _correlation(a: np.ndarray, b: np.ndarray) -> float:
    a = a - a.mean()
    b = b - b.mean()
    denominator = float(np.sqrt((a * a).sum() * (b * b).sum()))
    return float((a * b).sum() / denominator) if denominator > 0 else 0.0


# ---------------------------------------------------------------- the template


@dataclass
class Shot:
    start: float
    duration: float
    motion: str          # static | push | pull | pan | handheld
    energy: float        # 0..1, how much the picture changes


@dataclass
class Template:
    name: str
    source: str
    duration: float
    aspect: str
    width: int
    height: int
    shots: list[Shot] = field(default_factory=list)
    bpm: float = 0.0
    beats: list[float] = field(default_factory=list)
    cuts_on_beat: float = 0.0
    mean_shot: float = 0.0
    median_shot: float = 0.0
    shortest_shot: float = 0.0
    motion_mix: dict = field(default_factory=dict)
    look: dict = field(default_factory=dict)
    speech_ratio: float = 0.0
    captions: dict = field(default_factory=dict)
    hook: dict = field(default_factory=dict)
    audio: dict = field(default_factory=dict)
    transitions: dict = field(default_factory=dict)
    #: Things this analysis cannot know, said out loud rather than faked.
    unknown: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        data = asdict(self)
        data["shots"] = [asdict(s) for s in self.shots]
        return data


def _aspect_name(width: int, height: int) -> str:
    if height == 0:
        return "16:9"
    ratio = width / height
    table = {"9:16": 9 / 16, "1:1": 1.0, "4:5": 0.8, "16:9": 16 / 9, "4:3": 4 / 3}
    return min(table, key=lambda key: abs(table[key] - ratio))


def _colour_of(path: str, times: list[float]) -> dict:
    """Brightness, contrast, saturation and warmth, averaged over sampled frames."""
    values = []
    for at in times:
        out = cancellation.run(
            [
                ffmpeg_binary(), "-hide_banner", "-loglevel", "error",
                "-ss", f"{at:.3f}", "-i", str(path), "-frames:v", "1",
                "-vf", "scale=64:64,format=rgb24", "-f", "rawvideo", "-",
            ],
            capture_output=True,
        )
        if len(out.stdout) < 64 * 64 * 3:
            continue
        frame = np.frombuffer(out.stdout[: 64 * 64 * 3], dtype=np.uint8).reshape(-1, 3).astype(np.float32) / 255
        luma = frame @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
        maximum = frame.max(axis=1)
        minimum = frame.min(axis=1)
        saturation = float(np.mean((maximum - minimum) / np.clip(maximum, 1e-6, None)))
        values.append((float(luma.mean()), float(luma.std()), saturation,
                       float(frame[:, 0].mean() - frame[:, 2].mean())))
    if not values:
        return {}
    brightness, contrast, saturation, warmth = (float(np.mean([v[i] for v in values])) for i in range(4))
    return {
        # Expressed the way the editor's grade sliders take them.
        "brightness": round((brightness - 0.5) * 0.6, 3),
        "contrast": round(1.0 + (contrast - 0.22) * 1.2, 3),
        "saturation": round(0.6 + saturation * 1.2, 3),
        "temperature": round(warmth * 3.0, 3),
    }


def _classify_motion(path: str, start: float, duration: float) -> tuple[str, float]:
    """How the camera behaves inside one shot."""
    samples = max(3, min(6, int(duration / 0.4)))
    frames = sample_strip(path, start, duration, samples)
    if len(frames) < 2:
        return "static", 0.0

    scales, pans, energies = [], [], []
    measured_in_polar = False
    for a, b in zip(frames, frames[1:]):
        polar = _log_polar_scale(a, b)
        if polar is not None:
            measured_in_polar = True
        scale = polar if polar is not None else _best_scale(a, b)[0]
        dx, dy = _phase_shift(a, b)
        scales.append(scale)
        pans.append(math.hypot(dx, dy))
        energies.append(float(np.abs(a - b).mean() / 255.0))

    scale = float(np.mean(scales))
    pan = float(np.mean(pans))
    energy = float(np.mean(energies))

    # The order matters: a zoom also produces apparent translation, so the scale
    # question is asked first. Log-polar is a fine measure (a pure pan sits within
    # 0.3 % of 1.0), the brute-force fallback is coarse — hence two thresholds.
    limit = 0.01 if measured_in_polar else 0.03
    if scale > 1 + limit:
        return "push", energy
    if scale < 1 - limit:
        return "pull", energy
    if pan > 1.5:
        return "pan", energy
    if energy > 0.09:
        return "handheld", energy
    return "static", energy




def _coarse_action(path: str, duration: float) -> tuple[float, float]:
    """Average action-peak and presence over a few windows, for the brain.

    Coarse on purpose: the brain only needs "is there a sharp peak / a moving
    subject", not a per-window map; a handful of samples is enough and cheap.
    """
    if duration <= 0:
        return 0.0, 0.0
    n = 6
    acts, pres = [], []
    for i in range(n):
        start = duration * i / n
        peak, presence = _action_profile(path, start, max(0.5, duration / n))
        acts.append(peak)
        pres.append(presence)
    return (sum(acts) / n, sum(pres) / n)


def _action_profile(path: str, start: float, duration: float) -> tuple[float, float]:
    """How *burst-like* and how *occupied* a window is, from sampled frames.

    Two numbers a sport needs that mean energy does not capture:

    * **peak** — the largest frame-to-frame change inside the window. A pan is a
      steady moderate change; a spike or a jump is one violent frame. The max,
      not the mean, is what separates them.
    * **presence** — the share of consecutive frame pairs with change above a
      floor. An empty court or a rest between sets is near zero; a rally is near
      one. This is the "is the subject even in this moment" vote.

    Both come from the same grayscale strip `sample_strip` already decodes in one
    FFmpeg call, so the cost is a handful of array diffs.
    """
    frames = sample_strip(path, start, duration, 6)
    if len(frames) < 2:
        return 0.0, 0.0
    diffs = [float(np.abs(a - b).mean() / 255.0) for a, b in zip(frames, frames[1:])]
    peak = max(diffs)
    active = sum(1 for d in diffs if d > 0.02) / len(diffs)
    return peak, active

def _silent(stage: str, progress: float, label: str = "") -> None:
    """The default reporter: analysis works exactly as before when nobody is watching."""


def analyse(path: str, name: str | None = None, progress=None) -> Template:
    """Measure a reference video and describe it as a template.

    `progress(stage, fraction, label)` is called at every boundary so a caller can
    show what is happening. A ten-minute reference is a minute of work; until
    0.6.0 the only thing the screen could say was "busy", and the request behind
    it died at the client's 30 second budget.
    """
    say = progress or _silent
    source = Path(path)
    if not source.exists():
        raise FileNotFoundError(path)

    say("probe", 0.02, "Reading the file")
    info = probe_media(str(source))
    duration = float(info.get("duration") or 0.0)
    width, height = int(info.get("width") or 0), int(info.get("height") or 0)

    template = Template(
        name=name or source.stem,
        source=str(source),
        duration=round(duration, 3),
        aspect=_aspect_name(width, height),
        width=width,
        height=height,
    )

    # ---- shots -----------------------------------------------------------
    say("shots", 0.08, "Finding the shot boundaries")
    cuts = [c for c in analysis.detect_scenes(str(source)) if 0.0 < c < duration]
    bounds = [0.0, *cuts, duration]
    spans = [(s, e) for s, e in zip(bounds, bounds[1:]) if e - s >= 0.2]
    for index, (start, end) in enumerate(spans):
        length = end - start
        say(
            "motion",
            0.15 + 0.45 * (index / max(1, len(spans))),
            f"Camera move, shot {index + 1} of {len(spans)}",
        )
        motion, energy = _classify_motion(str(source), start, length)
        template.shots.append(Shot(round(start, 3), round(length, 3), motion, round(energy, 4)))

    lengths = [s.duration for s in template.shots] or [duration]
    template.mean_shot = round(float(np.mean(lengths)), 3)
    template.median_shot = round(float(np.median(lengths)), 3)
    template.shortest_shot = round(float(np.min(lengths)), 3)
    template.motion_mix = {
        kind: round(sum(1 for s in template.shots if s.motion == kind) / max(1, len(template.shots)), 3)
        for kind in ("static", "push", "pull", "pan", "handheld")
    }

    # ---- music and whether the cuts follow it ----------------------------
    if info.get("has_audio"):
        say("beats", 0.62, "Listening for the tempo")
        beats = audio_engine.beats(str(source))
        template.bpm = beats.bpm
        template.beats = beats.beats
        if cuts and beats.beats:
            hits = sum(
                1 for cut in cuts if min(abs(cut - b) for b in beats.beats) <= BEAT_TOLERANCE
            )
            template.cuts_on_beat = round(hits / len(cuts), 3)

        say("speech", 0.72, "Measuring where the speech sits")
        silences = analysis.detect_silence(str(source))
        speech = analysis.keep_ranges(duration, silences)
        template.speech_ratio = round(
            sum(r.duration for r in speech) / duration if duration else 0.0, 3
        )
        template.hook = {
            "firstCut": round(cuts[0], 3) if cuts else round(duration, 3),
            "firstWord": round(speech[0].start, 3) if speech else None,
        }
        template.captions = {
            # A talking video gets captions; the *style* needs the OCR pass that
            # is not built yet, so the honest default is our clean bottom style.
            "wanted": template.speech_ratio > 0.25,
            "position": "bottom",
            "style": "outline",
            "animateWords": True,
        }
        template.audio = {
            "musicUnderVoice": -9.0 if template.speech_ratio > 0.25 else 0.0,
            # Filled in by save_template(): the reference's own track, kept
            # beside the template so the rebuild can actually use it.
            "hasBed": False,
        }
    else:
        template.hook = {"firstCut": round(cuts[0], 3) if cuts else round(duration, 3), "firstWord": None}

    # ---- colour ----------------------------------------------------------
    say("colour", 0.82, "Measuring the colour")
    template.look = _colour_of(str(source), [duration * f for f in (0.15, 0.4, 0.65, 0.9)])

    # ---- transitions -----------------------------------------------------
    # A hard cut changes the frame completely in one step; a dissolve spreads the
    # change over several frames, which is visible as a softer difference profile.
    say("transitions", 0.9, "Telling cuts from dissolves")
    soft = 0
    for cut in cuts:
        before = sample_gray(str(source), max(0.0, cut - 0.25))
        during = sample_gray(str(source), cut)
        after = sample_gray(str(source), min(duration - 0.05, cut + 0.25))
        if before is None or during is None or after is None:
            continue
        edge = float(np.abs(before - after).mean())
        middle = float(np.abs(before - during).mean())
        if edge > 1 and middle / edge < 0.65:
            soft += 1
    template.transitions = {
        "count": len(cuts),
        "soft": soft,
        "type": "fade" if cuts and soft / max(1, len(cuts)) > 0.4 else "cut",
        "duration": 0.4,
    }

    template.unknown = [
        "on-screen graphics and hand-made titles",
        "the reference's own footage and fonts (never copied)",
        "exact caption typography (needs the OCR pass)",
    ]
    say("done", 1.0, "Template ready")
    return template


# ------------------------------------------------------------------ planning


def _highlights(path: str, wanted: int, minimum: float, window: float = 0.0,
                prefer_speech: bool = True, intent=None,
                captions: list[dict] | None = None) -> list[dict]:
    """Candidate moments across the **whole** file, ranked by measured signals.

    Three failures lived in the version this replaces, and all three were
    measurable rather than matters of taste:

    1. **It only ever looked at the beginning.** Windows were cut inside the
       speech ranges, ranked, and truncated to `wanted` — so on 120 s of footage
       against a 12 s reference the rebuild touched **17.3 s, 14.4 % of the
       material**, and the same 17 s on a 30 s file. The other 85 % was never a
       candidate. Windows now cover the file end to end.
    2. **It could not tell one moment from another.** Every window inside a
       speech range got weight 1.0, and the only variation was a 0.85–1.0 term
       for how full the window was. Measured on 26 candidates: scores 0.998 to
       1.0, a spread of **0.002** — so the sort was decided by nothing, and
       because it is a stable sort "best" silently became "earliest". Each
       signal is now normalised across the candidates before it is weighted, so
       the ranking is a comparison rather than a constant.
    3. **It had no idea what the video was.** A lesson and a music clip were
       ranked identically. The signals are the same measurements as before —
       speech coverage, picture motion, audio activity, proximity to the
       footage's own shot changes — but `core.engine.intent` says how much each
       one is worth for *this* video. An answer rebalances measurements; it
       never replaces one, and with no answers at all the weights are neutral.

    Motion costs an FFmpeg call per window, so the cheap signals pick a
    shortlist first and only the shortlist is decoded. A two-minute file ranks
    in a couple of seconds instead of spawning a hundred processes.
    """
    info = probe_media(path)
    duration = float(info.get("duration") or 0.0)
    span = max(0.4, window or minimum)
    #: Overlap the windows a little so a good moment is not split down the middle.
    stride = max(0.25, span * 0.75)
    if duration <= 0:
        return [{"start": 0.0, "end": 0.0, "score": 0.0}]

    weights = dict(intent.signal_weights()) if intent is not None else dict(intent_model.NEUTRAL)
    if not prefer_speech:
        # A reference that barely speaks is a montage: the microphone being open
        # is not evidence of anything.
        weights["speech"] = 0.0

    # ---- candidate windows: the whole file -------------------------------
    windows: list[tuple[float, float]] = []
    cursor = 0.0
    while cursor + span * 0.7 <= duration:
        finish = min(duration, cursor + span)
        # A window that ran out of file is not a shorter candidate, it is a clip
        # the timeline cannot fill: one of these reached the edit as a 0.75 s
        # shot in a rhythm of 1.0 s ones. The end of the file gets its own
        # full-length window below.
        if finish - cursor >= span * 0.9:
            windows.append((round(cursor, 3), round(finish, 3)))
        cursor += stride
    if duration > span and (not windows or windows[-1][1] < duration - 0.05):
        # The last second of a file is a candidate like any other.
        windows.append((round(duration - span, 3), round(duration, 3)))
    if not windows:
        windows = [(0.0, round(duration, 3))]

    # ---- the cheap signals, each measured once for the whole file --------
    speech: list[tuple[float, float]] = []
    if info.get("has_audio") and weights.get("speech", 0.0) > 0:
        try:
            # Whichever speech map is chosen — the model, or the energy detector
            # every earlier release used. Both return silence as ranges, so
            # nothing downstream knows the difference.
            silences = vad_engine.silent_ranges_auto(path)
            speech = [(r.start, r.end) for r in analysis.keep_ranges(duration, silences)]
        except Exception:  # noqa: BLE001 — no audio is a normal answer
            speech = []

    envelope: list[float] = []
    envelope_duration = 0.0
    if info.get("has_audio") and weights.get("onset", 0.0) > 0:
        try:
            peaks = audio_engine.peaks(path)
            envelope = [float(v) for v in (peaks.get("peaks") or [])]
            envelope_duration = float(peaks.get("duration") or 0.0)
        except Exception:  # noqa: BLE001
            envelope = []

    cuts: list[float] = []
    if weights.get("edge", 0.0) > 0:
        try:
            cuts = [c for c in analysis.detect_scenes(path) if 0 < c < duration]
        except Exception:  # noqa: BLE001
            cuts = []

    def speech_coverage(start: float, end: float) -> float:
        if not speech:
            return 0.0
        covered = sum(max(0.0, min(e, end) - max(s, start)) for s, e in speech)
        return covered / max(1e-6, end - start)

    def audio_activity(start: float, end: float) -> float:
        if not envelope or envelope_duration <= 0:
            return 0.0
        first = int(len(envelope) * start / envelope_duration)
        last = max(first + 1, int(len(envelope) * end / envelope_duration))
        band = envelope[first:min(len(envelope), last)]
        return float(np.mean(band)) if band else 0.0

    def edge_proximity(start: float, end: float) -> float:
        """1.0 when the window starts or ends exactly on the footage's own cut."""
        if not cuts:
            return 0.0
        near = min(min(abs(start - c), abs(end - c)) for c in cuts)
        return max(0.0, 1.0 - near / max(0.5, span))

    rough: list[dict] = []
    for start, end in windows:
        signals = {
            "speech": speech_coverage(start, end),
            "onset": audio_activity(start, end),
            "edge": edge_proximity(start, end),
        }
        cheap = sum(signals[key] * weights.get(key, 0.0) for key in signals)
        rough.append({"start": start, "end": end, "signals": signals, "cheap": cheap})

    # ---- motion is expensive: only the shortlist pays for it -------------
    finalists = sorted(rough, key=lambda c: c["cheap"], reverse=True)[:max(12, min(40, wanted * 2))]
    for candidate in finalists:
        _, energy = _classify_motion(path, candidate["start"], candidate["end"] - candidate["start"])
        candidate["signals"]["motion"] = float(energy)
        peak, presence = _action_profile(path, candidate["start"], candidate["end"] - candidate["start"])
        candidate["signals"]["action"] = float(peak)
        candidate["signals"]["presence"] = float(presence)

    # ---- vision: one vote from a model that has seen the frame ------------
    # Strictly optional and off by default. When a vision model the user runs is
    # present, it scores the shortlist's frames and joins the normalisation as one
    # more signal — a vote, never a veto (§4.45). When absent, nothing changes.
    from core.engine import vision as vision_engine

    has_vision = False
    if settings.vision_enabled and vision_engine.available():
        scores = vision_engine.score_moments(
            path, [c["start"] + (c["end"] - c["start"]) / 2 for c in finalists]
        )
        if scores:
            for candidate in finalists:
                at = min(scores, key=lambda t: abs(t - (candidate["start"] + (candidate["end"] - candidate["start"]) / 2)))
                candidate["signals"]["vision"] = float(scores[at])
            has_vision = True

    # ---- normalise across the finalists, then weigh ---------------------
    # Relative on purpose: "the strongest moment in this file" is a comparison
    # between moments, and an absolute threshold would rank a quiet recording
    # as having no highlights.
    _w = dict(weights)
    if has_vision:
        _w["vision"] = vision_engine.MAX_WEIGHT

    active = [key for key in ("speech", "motion", "onset", "edge", "vision", "action", "presence")
              if _w.get(key, 0.0) > 0.0 and any(key in c["signals"] for c in finalists)]
    ranges: dict[str, tuple[float, float]] = {}
    for key in active:
        values = [c["signals"].get(key, 0.0) for c in finalists]
        low, high = min(values), max(values)
        ranges[key] = (low, max(1e-9, high - low))
    total_weight = sum(_w.get(key, 0.0) for key in active) or 1.0

    for candidate in finalists:
        score = 0.0
        for key in active:
            low, spread = ranges[key]
            value = (candidate["signals"].get(key, 0.0) - low) / spread
            score += _w.get(key, 0.0) * max(0.0, min(1.0, value))
        candidate["score"] = round(score / total_weight, 4)

    # ---- meaning: what was said, not how loud it was ---------------------
    if captions:
        for candidate in finalists:
            sense = brain_meaning.score_window(captions, candidate["start"], candidate["end"])
            candidate["score"] = round(brain_meaning.blend(candidate["score"], sense), 4)
            candidate["meaning"] = round(sense, 4)

    # ---- the user's own definition of a highlight ------------------------
    if captions and intent is not None and (intent.keep or intent.avoid):
        for candidate in finalists:
            said = " ".join(
                str(cue.get("text", ""))
                for cue in captions
                if min(float(cue.get("end", 0.0)), candidate["end"])
                > max(float(cue.get("start", 0.0)), candidate["start"])
            )
            hit = intent.keyword_score(said)
            if hit:
                candidate["score"] = round(max(0.0, min(1.0, candidate["score"] + 0.5 * hit)), 4)
                candidate["keywords"] = round(hit, 4)

    finalists.sort(key=lambda c: c["score"], reverse=True)
    return [{k: v for k, v in c.items() if k != "cheap"} for c in finalists[:max(wanted, 1)]]


def _brain_context(
    data: dict,
    shots: list[dict],
    source: str,
    info: dict,
    measured: list[dict],
    captions: list[dict] | None,
    music: str | None,
    intent: intent_model.Intent | None = None,
) -> objective.Context:
    """Everything the judge is allowed to know, all of it measured here.

    Beats come from the track the edit will actually play against — the music
    bed if there is one, the footage's own audio otherwise. Borrowing the
    reference's beat grid would score the cuts against music that is not in the
    edit.
    """
    beats: list[float] = []
    beat_source = music or (source if info.get("has_audio") else None)
    if beat_source:
        try:
            beats = audio_engine.beats(str(beat_source)).beats
        except Exception:  # noqa: BLE001 — no tempo is a normal answer
            beats = []

    speech: list[tuple[float, float]] = []
    duration = float(info.get("duration") or 0.0)
    if info.get("has_audio"):
        try:
            silences = vad_engine.silent_ranges_auto(source)
            speech = [(r.start, r.end) for r in analysis.keep_ranges(duration, silences)]
        except Exception:  # noqa: BLE001
            speech = []

    words: list[dict] = []
    for cue in captions or []:
        words.extend(cue.get("words") or [])

    # The transcript's story shape, measured by markers (meaning 2.0). With no
    # captions the field stays None and the term is skipped, not faked.
    narrative = None
    if captions:
        from core.brain import meaning as meaning_engine  # noqa: PLC0415

        narrative = meaning_engine.narrative_arc(captions)

    # Taste as a prior: what the user approved or rejected before nudges the
    # weights, bounded — a rebalance, never a replacement for a measurement.
    from core.brain import memory as taste  # noqa: PLC0415

    return objective.Context(
        duration=duration,
        target_shots=[float(s["duration"]) for s in shots],
        beats=beats,
        reference_cuts_on_beat=data.get("cuts_on_beat"),
        speech=speech,
        words=words,
        best_highlight=max((p.get("score", 0.0) for p in measured), default=0.0),
        # The judge is told what the user is trying to do, so a lesson that cuts
        # mid-sentence loses to one that does not. Terms that could not be
        # measured stay skipped — a weight is not a measurement.
        weights=intent.weight_multipliers() if intent is not None else {},
        narrative=narrative,
        platform=getattr(intent, "platform", None) if intent is not None else None,
        prior=taste.prior(),
    )


def _fit_to_length(shots: list[dict], seconds: float) -> list[dict]:
    """Repeat the reference's rhythm until the edit is the length asked for.

    The rebuild used to be *exactly* as long as the reference, always: a 12 s
    template turned three minutes of footage into a 12 s edit that had looked at
    the first 17 s. That is the complaint "it shortens the first video", and the
    honest fix is not to stretch the shots — a 12 s rhythm stretched to 60 s is
    five slow shots — but to run the same rhythm again, which is what an editor
    does when a reference is shorter than the story.
    """
    total = sum(float(s["duration"]) for s in shots)
    if total <= 0 or not shots:
        return shots

    if seconds <= total:
        out: list[dict] = []
        running = 0.0
        for shot in shots:
            length = float(shot["duration"])
            if running + length > seconds:
                remainder = round(seconds - running, 3)
                if remainder >= 0.2:
                    out.append({**shot, "duration": remainder})
                break
            out.append(dict(shot))
            running += length
        return out or [dict(shots[0])]

    out = [dict(s) for s in shots] * int(math.ceil(seconds / total))
    # Come back to the target by **dropping whole shots** first. Trimming only
    # the last one cannot absorb a multi-second overshoot and left a 0.2 s flash
    # on the end of the timeline (measured: a 15 s request produced 17.18 s).
    while len(out) > 1:
        without_last = sum(float(s["duration"]) for s in out[:-1])
        if without_last >= seconds:
            out.pop()
            continue
        break
    overshoot = sum(float(s["duration"]) for s in out) - seconds
    if overshoot > 0.05 and out:
        out[-1] = {**out[-1], "duration": round(max(0.2, float(out[-1]["duration"]) - overshoot), 3)}
    return out


def build_timeline(
    template: Template | dict,
    source: str,
    name: str = "Styled edit",
    music: str | None = None,
    captions: list[dict] | None = None,
    progress=None,
    brain: bool = True,
    model: str | None = None,
    intent: dict | intent_model.Intent | None = None,
) -> dict:
    """Cut the user's footage into the shape of the template.

    Returns an editor document (tracks, clips, transitions) — the same structure
    the timeline saves and the compositor renders, so what the user sees in the
    editor is exactly what will be exported.

    This is the *automatic* door of the app: no prompt. Whatever the template
    implies is carried out here — the cut rhythm, the colour, the camera moves,
    and, when they are available, the captions and a ducked music bed. Anything
    that could not be done is reported in `summary.skipped` rather than quietly
    dropped.

    `intent` is the one thing a frame cannot say: what the video is for. It is
    optional and neutral by default — a rebuild with no answers behaves exactly
    as it did before — but it is what tells the difference between a lesson and
    a music clip, and it is the only way the user can ask for a length that is
    not the reference's. Everything it changes is reported in `summary.intent`.
    """
    say = progress or _silent
    say("plan", 0.05, "Reading the template")
    data = template.as_dict() if isinstance(template, Template) else dict(template)
    shots = data.get("shots") or []
    if not shots:
        shots = [{"duration": max(1.0, data.get("mean_shot") or 2.0), "motion": "static"}]

    # ---- what the user said the video is for -----------------------------
    wanted = intent if isinstance(intent, intent_model.Intent) else intent_model.Intent.from_dict(intent)
    factor = wanted.shot_length_factor()
    if factor != 1.0:
        shots = [{**s, "duration": round(max(0.2, float(s["duration"]) * factor), 3)} for s in shots]
    if wanted.seconds > 0:
        shots = _fit_to_length(shots, wanted.seconds)

    # ---- the hook ---------------------------------------------------------
    # `hook.firstCut` is how long the reference waited before its first cut. It
    # is resolved **here**, before the candidate moments are measured, because
    # the windows those moments are cut into are sized from the longest shot on
    # the timeline: extending the opening afterwards could never be reproduced
    # by a window that was already cut to the old length. (Measured: a 4 s shot
    # with a 7 s hook came back at 4.0 s.)
    hook = data.get("hook") or {}
    first_cut = float(hook.get("firstCut") or 0.0)
    if shots and first_cut > 0.2 and first_cut > float(shots[0]["duration"]):
        # Only ever **extend** the opening. In a template we analysed, the
        # reference's first shot *is* `firstCut`, so this can only lengthen a
        # hand-made or edited one. The form this replaced assigned the value
        # outright inside a 6 s window — free to chop an opening down to a
        # fraction of a second, and blind to a genuinely held 7 s intro.
        opening = dict(shots[0])
        opening["duration"] = round(first_cut, 3)
        shots = [opening, *shots[1:]]

    info = probe_media(source)
    source_duration = float(info.get("duration") or 0.0)
    shortest = min(float(s["duration"]) for s in shots)
    say("highlights", 0.2, "Choosing the strongest moments")
    # The reference's own median shot is the natural size for a candidate
    # window; fall back to the shots we were given when the template is thin.
    typical = float(data.get("median_shot") or 0.0) or float(
        np.median([float(s["duration"]) for s in shots])
    )
    speech_ratio = float(data.get("speech_ratio") or 0.0)
    # A reference that barely speaks is a montage, so the microphone being open
    # proves nothing — unless the user has just said this video is a lesson, in
    # which case the microphone is the whole point.
    # A restriction the transcript can screen for is just another phrase the
    # owner asked to avoid, so it travels through the mechanism that already
    # exists instead of growing a second one.
    screened = wanted
    extra = wanted.restriction_markers()
    if extra:
        screened = intent_model.Intent.from_dict({
            **wanted.as_dict(),
            "avoid": [*wanted.avoid, *extra],
        })

    hunts_speech = speech_ratio >= 0.2
    if wanted.prefers_speech() is not None:
        hunts_speech = bool(wanted.prefers_speech())
    measured = _highlights(
        source,
        # Ask for far more candidates than shots, so the planner has somewhere
        # else to go instead of using the same moment again.
        wanted=max(len(shots) * 4, 24),
        minimum=max(0.4, shortest * 0.8),
        # A window must be able to hold the **longest** shot on the timeline, not
        # just the reference's median: with a calm rhythm or a requested length
        # the shots grow past `typical`, and every clip was then clamped back to
        # the window (a 60 s request produced 47 s). Measured, not assumed.
        window=max(0.5, typical, max(float(s["duration"]) for s in shots)),
        prefer_speech=hunts_speech,
        # Loudness finds energy; the transcript finds the sentence where the
        # point is made; the user's own words outrank both. All three are scored
        # in one place now, so nothing can disagree with itself about the order.
        intent=screened,
        captions=captions,
    )

    # ---- the brain --------------------------------------------------------
    # Measuring produced the candidate moments; *choosing and ordering* them is
    # a judgement, so it is raced: the deterministic rule planner against a
    # local Ollama model, both scored by the same objective function. The rule
    # plan is always a candidate, so the model can only win by being better.
    say("plan", 0.45, "Choosing an order")
    # Your own track wins; otherwise the reference's own soundtrack, if this
    # template kept one. Resolved *here*, before the planners run, so the cut
    # points are scored against the beats of the track that will really play.
    used_reference_bed = False
    reference_bed = (data.get("audio") or {}).get("bed")
    if wanted.music == "none":
        # "No music" means no music — including the one the template kept.
        music = None
    elif wanted.music == "mine":
        pass  # only a track the user brought; the reference's bed stays out
    elif not music and reference_bed and Path(reference_bed).exists():
        music = reference_bed
        used_reference_bed = True

    brain_context = _brain_context(data, shots, source, info, measured, captions, music, screened)

    def _feat(p: dict) -> tuple | None:
        s = p.get("signals") or {}
        if not s:
            return None
        return (float(s.get("speech", 0.0)), float(s.get("motion", 0.0)),
                float(s.get("action", 0.0)), float(s.get("presence", 0.0)))

    decision = brain_race.race(
        [objective.Pick(p["start"], p["end"], p.get("score", 0.0), features=_feat(p))
         for p in measured],
        brain_context,
        transcript=captions,
        use_llm=brain,
        model=model,
    )
    picks = [{"start": p.start, "end": p.end, "score": p.score} for p in decision.picks]
    if not picks:  # a planner that produced nothing must not empty the timeline
        picks = measured
    say("layout", 0.6, "Laying the clips out to the rhythm")

    clips: list[dict] = []
    transitions: list[dict] = []
    cursor = 0.0
    look = data.get("look") or {}
    transition_kind = (data.get("transitions") or {}).get("type", "cut")
    transition_length = float((data.get("transitions") or {}).get("duration", 0.4))
    counted = (data.get("transitions") or {}).get("count") or 0
    soft_ratio = ((data.get("transitions") or {}).get("soft") or 0) / counted if counted else 0.0
    soft_every = max(1, round(1 / soft_ratio)) if soft_ratio > 0.05 else 10**6

    # The single best moment, for the slow-mo beat when the user asked for one.
    best_index = (
        max(range(len(picks)), key=lambda i: picks[i].get("score", 0.0))
        if (wanted.slowmo and picks) else -1
    )

    for index, shot in enumerate(shots):
        want = float(shot["duration"])
        # The winner normally returns one pick per shot. When it returns fewer,
        # take the next *unused* measured window rather than cycling — cycling
        # is what put the same half second on the timeline twenty times.
        if index < len(picks):
            pick = picks[index]
        else:
            spare = [
                m for m in measured
                if all(abs(m["start"] - used["start"]) > 0.2 for used in picks[:index])
            ]
            pick = spare[(index - len(picks)) % len(spare)] if spare else picks[index % len(picks)]
        available = max(0.2, pick["end"] - pick["start"])
        length = min(want, available, max(0.2, source_duration - pick["start"]))
        if length < 0.2:
            continue

        # Slow-mo: the same source window at half speed, twice the screen time.
        # The source consumed is length*speed = the original window, so nothing is
        # invented; the highlight simply lingers.
        speed = 1.0
        if index == best_index:
            speed = 0.5
            length = length * 2

        motion = shot.get("motion", "static")
        keyframes: list[dict] = []
        if motion == "push":
            keyframes = [{"t": 0, "scale": 1.0}, {"t": round(length, 3), "scale": 1.12}]
        elif motion == "pull":
            keyframes = [{"t": 0, "scale": 1.12}, {"t": round(length, 3), "scale": 1.0}]
        elif motion == "pan":
            keyframes = [{"t": 0, "x": -0.06, "scale": 1.1}, {"t": round(length, 3), "x": 0.06, "scale": 1.1}]
        elif motion == "handheld":
            # Measured since 0.5.0 and, until now, quietly dropped: a shot the
            # analyser called handheld came out perfectly still.
            step = max(0.15, length / 4)
            keyframes = [
                {"t": 0, "x": 0.0, "y": 0.0, "scale": 1.06},
                {"t": round(step, 3), "x": 0.012, "y": -0.010, "scale": 1.06},
                {"t": round(step * 2, 3), "x": -0.010, "y": 0.012, "scale": 1.06},
                {"t": round(step * 3, 3), "x": 0.008, "y": 0.008, "scale": 1.06},
                {"t": round(length, 3), "x": 0.0, "y": 0.0, "scale": 1.06},
            ]

        clip = {
            "id": f"s{index}",
            "trackId": "v1",
            "start": round(cursor, 3),
            "duration": round(length, 3),
            "offset": round(pick["start"], 3),
            "sourceDuration": round(source_duration, 3),
            "src": source,
            "label": f"{index + 1:02d} · {motion}",
            "color": "#6366F1",
            "props": {
                "adjust": {
                    "brightness": look.get("brightness", 0.0),
                    "contrast": look.get("contrast", 1.0),
                    "saturation": look.get("saturation", 1.0),
                    "temperature": look.get("temperature", 0.0),
                    "sharpen": 0.0,
                    "vignette": 0.0,
                },
                **({"keyframes": keyframes} if keyframes else {}),
                **({"speed": speed} if speed != 1.0 else {}),
            },
        }
        clips.append(clip)

        # The reference's *proportion* of soft cuts, not all-or-nothing. A
        # template with 40 % dissolves used to produce either none of them (the
        # type came out "cut") or one at every junction.
        wants_soft = transition_kind != "cut" or (soft_ratio > 0.05 and index % soft_every == 0)
        if index > 0 and wants_soft:
            transitions.append({
                "id": f"t{index}",
                "trackId": "v1",
                "fromClipId": clips[-2]["id"],
                "toClipId": clip["id"],
                "type": transition_kind if transition_kind != "cut" else "fade",
                "duration": min(transition_length, length / 2, clips[-2]["duration"] / 2),
            })

        cursor += length

    applied = ["cut to the template rhythm", "colour", "camera moves", "aspect"]
    skipped: list[str] = []
    if wanted.seconds > 0:
        applied.append(f"length set to {wanted.seconds:g} s")
    if wanted.slowmo and best_index >= 0:
        applied.append("half-speed slow-mo on the best moment")
    markers = wanted.restriction_markers()
    if markers:
        # One line, not the words themselves: a summary that prints a swear list
        # is a summary the user has to read to believe, and the words are in the
        # code where they can be checked.
        applied.append(f"screening the transcript for {len(markers)} banned phrases")
    # A restriction that is now checkable, is checked — and one that is not, is
    # still reported honestly rather than silently skipped.
    from core.engine import ocr as ocr_engine

    ocr_here = ocr_engine.installed()
    if "no_on_screen_text" in wanted.restrictions:
        if ocr_here:
            coverage = ocr_engine.text_coverage(source, every=3.0)
            if coverage > 0.2:
                skipped.append(
                    f"on-screen text on {coverage * 100:.0f}% of sampled frames — "
                    "OCR can read it and warn, not erase it"
                )
            else:
                applied.append("no on-screen text detected")
        else:
            skipped.append("no on-screen text (the OCR engine is not fetched)")
    for limit in wanted.cannot_honour():
        # `no_on_screen_text` is handled above once the engine exists; the rest
        # (identity, brand entities) still need a pass that is not built.
        if limit.startswith("no_on_screen_text") and ocr_here:
            continue
        skipped.append(f"{limit} — cannot be checked yet")
    if factor != 1.0:
        applied.append(f"rhythm {'slowed' if factor > 1 else 'tightened'} × {factor:.2f}")
    if transitions:
        applied.append(f"{len(transitions)} × {transition_kind}")

    # ---- captions -------------------------------------------------------
    if captions and wanted.clean_fillers:
        # Filler words make captions longer than the shot and light up words
        # nobody should read; remove them before the cues touch the timeline.
        captions = fillers_engine.clean_cues(captions)
    caption_style = dict(data.get("captions") or {})
    caption_choice = wanted.caption_preference()
    if caption_choice:
        caption_style.update(caption_choice)
        if not caption_choice["wanted"]:
            # The owner said no subtitles. A transcript that arrived anyway is not
            # a reason to overrule them — dropping it is reported, not hidden.
            captions = None
            skipped.append("captions (you asked for none)")
    if captions:
        for index, cue in enumerate(captions):
            start = max(0.0, float(cue.get("start", 0.0)))
            end = max(start + 0.3, float(cue.get("end", start + 1.0)))
            if start >= cursor:
                break
            clips.append({
                "id": f"c{index}",
                "trackId": "t1",
                "start": round(start, 3),
                "duration": round(min(end, cursor) - start, 3),
                "offset": 0,
                "sourceDuration": round(end - start, 3),
                "src": None,
                "text": str(cue.get("text", "")).strip(),
                "words": cue.get("words") or [],
                "label": str(cue.get("text", ""))[:24],
                "color": "#0EA5E9",
                "props": {
                    "position": caption_style.get("position", "bottom"),
                    "textStyle": caption_style.get("style", "outline"),
                    "animateWords": bool(caption_style.get("animateWords", True)),
                },
            })
        applied.append(f"{len(captions)} captions")
    elif caption_style.get("wanted"):
        skipped.append("captions (speech recognition is not installed)")

    # ---- music ----------------------------------------------------------
    if used_reference_bed:
        applied.append("the reference's own soundtrack")

    if music:
        under = float((data.get("audio") or {}).get("musicUnderVoice", 0.0))
        clips.append({
            "id": "music",
            "trackId": "a1",
            "start": 0.0,
            "duration": round(cursor, 3),
            "offset": 0.0,
            "sourceDuration": round(float(probe_media(music).get("duration") or cursor), 3),
            "src": music,
            "label": Path(music).stem[:24],
            "color": "#10B981",
            # A bed under speech ducks; without speech it just plays.
            "props": {"duck": under < 0, "volume": 0.9},
        })
        applied.append("music bed" + (" with ducking" if under < 0 else ""))
    elif wanted.music == "none":
        # The owner asked for silence under the voice; reporting that as
        # something we failed to do would be arguing with them.
        applied.append("no music, as asked")
    elif (data.get("audio") or {}).get("musicUnderVoice", 0.0) < 0:
        skipped.append("music (the template has one, you did not give me a track)")

    say("done", 1.0, "Edit ready")
    return {
        "name": name,
        "aspect": data.get("aspect", "9:16"),
        "template": data.get("name"),
        "timeline": {
            "tracks": [
                {"id": "v1", "kind": "video", "name": "Video 1", "muted": False, "locked": False},
                {"id": "a1", "kind": "audio", "name": "Audio", "muted": False, "locked": False},
                {"id": "t1", "kind": "text", "name": "Text", "muted": False, "locked": False},
            ],
            "clips": clips,
            "transitions": transitions,
        },
        "brain": editor_brain.assess(
            data,
            {"speech_ratio": speech_ratio,
             "action": _coarse_action(source, source_duration)[0],
             "presence": _coarse_action(source, source_duration)[1]},
            intent=wanted.as_dict(),
        ),
        "summary": {
            "shots": len([c for c in clips if c["trackId"] == "v1"]),
            "duration": round(cursor, 3),
            "fromHighlights": len(picks),
            "motion": [c["label"].split("· ")[-1] for c in clips if c["trackId"] == "v1"],
            "captions": len([c for c in clips if c["trackId"] == "t1"]),
            "bpm": data.get("bpm", 0.0),
            "applied": applied,
            "skipped": skipped,
            # What the answers changed, said out loud: an answer with no visible
            # effect is an answer the user will not trust twice.
            "intent": wanted.as_dict(),
            "intentSaid": wanted.describe(),
            # How much of the user's own file the edit draws from. It was 14.4 %
            # on a 120 s file before the candidate windows covered the file, and
            # a number like that is the only honest way to keep it honest.
            "sourceSpanUsed": round(
                100 * max((c["offset"] + c["duration"] for c in clips if c["trackId"] == "v1"),
                          default=0.0) / source_duration, 1,
            ) if source_duration > 0 else 0.0,
            # The race, in the open: who planned, what each scored, who won.
            # "rules 0.71 · ollama:qwen2.5 0.83 → used ollama:qwen2.5" is the
            # only honest answer to "did the AI help?" — and sometimes it is no.
            "brain": decision.as_dict_without_picks(),
        },
    }


# -------------------------------------------------------------------- storage


def templates_dir():
    from app.config import settings

    path = Path(settings.cuttingedge_home) / "templates"
    path.mkdir(parents=True, exist_ok=True)
    return path


def save_template(template: Template) -> Path:
    """Write the template, and keep the reference's soundtrack next to it."""
    bed = extract_bed(template.source, template.name) if template.source else None
    if bed is not None:
        audio = dict(template.audio or {})
        audio["hasBed"] = True
        audio["bed"] = str(bed)
        template.audio = audio

    target = templates_dir() / f"{template.name}.cetemplate"
    target.write_text(json.dumps(template.as_dict(), indent=2), encoding="utf-8")
    return target


def bed_path(name: str) -> Path:
    """Where a template keeps the reference's own soundtrack."""
    return templates_dir() / f"{name}.bed.m4a"


def extract_bed(source: str, name: str) -> Path | None:
    """Keep the reference's audio with the template.

    The template used to carry the *behaviour* of the music (tempo, how far it
    ducks under a voice) and never the music itself, on copyright grounds. The
    owner of this project asked for the track as well and takes that decision:
    the file is theirs, the export is theirs, and refusing to copy an audio
    stream that FFmpeg can read in one line was us making their decision for
    them.

    It is stored beside the `.cetemplate` so it survives the reference being
    moved or deleted, and it is only ever placed on the timeline when the user
    asks for it.
    """
    target = bed_path(name)
    if target.exists():
        return target
    info = probe_media(source)
    if not info.get("has_audio"):
        return None
    result = subprocess.run(
        [
            ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(source), "-vn", "-ac", "2", "-ar", "48000",
            "-c:a", "aac", "-b:a", "192k", str(target),
        ],
        capture_output=True,
    )
    if result.returncode != 0 or not target.exists():
        return None
    return target


def list_templates() -> list[dict]:
    out = []
    for file in sorted(templates_dir().glob("*.cetemplate"), key=lambda p: p.stat().st_mtime, reverse=True):
        try:
            data = json.loads(file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        out.append({
            "name": data.get("name", file.stem),
            "shots": len(data.get("shots") or []),
            "duration": data.get("duration", 0.0),
            "bpm": data.get("bpm", 0.0),
            "aspect": data.get("aspect", ""),
            "updatedAt": file.stat().st_mtime,
        })
    return out


def load_template(name: str) -> dict:
    file = templates_dir() / f"{name}.cetemplate"
    if not file.exists():
        raise FileNotFoundError(name)
    return json.loads(file.read_text(encoding="utf-8"))


MOTIONS = ("static", "push", "pull", "pan", "handheld")
ASPECTS = ("9:16", "1:1", "4:5", "16:9", "4:3")


def validate_template(doc: dict) -> list[str]:
    """What is wrong with a template document, or [] when it is sound.

    Import is an interface that crosses a boundary (a file someone else made),
    and interfaces get checked — an unchecked `.cetemplate` is how a number the
    render cannot honour reaches the compositor (§4.75).
    """
    errors: list[str] = []
    if not isinstance(doc, dict):
        return ["the template is not a JSON object"]
    if not str(doc.get("name", "")).strip():
        errors.append("the template has no name")
    try:
        if float(doc.get("duration", -1)) < 0:
            errors.append("duration is negative")
    except (TypeError, ValueError):
        errors.append("duration is not a number")
    shots = doc.get("shots")
    if not isinstance(shots, list) or not shots:
        errors.append("the template has no shots")
    else:
        for index, shot in enumerate(shots):
            if not isinstance(shot, dict):
                errors.append(f"shot {index} is not an object")
                continue
            try:
                if float(shot.get("duration", 0)) <= 0:
                    errors.append(f"shot {index} has no length")
            except (TypeError, ValueError):
                errors.append(f"shot {index} length is not a number")
            if shot.get("motion", "static") not in MOTIONS:
                errors.append(f"shot {index} names an unknown camera move")
    if doc.get("aspect") and doc["aspect"] not in ASPECTS:
        errors.append(f"aspect {doc.get('aspect')!r} is not one the canvas knows")
    return errors


def import_template(doc: dict, name: str | None = None) -> Path:
    """Save a template document that came from outside, after checking it.

    The caller may rename it (an imported file should not clobber an existing
    gallery entry by accident unless asked). Raises ValueError with the joined
    problems when the document is not sound.
    """
    errors = validate_template(doc)
    if errors:
        raise ValueError("; ".join(errors))
    template = Template.from_dict if hasattr(Template, "from_dict") else None
    doc = dict(doc)
    if name:
        doc["name"] = name
    template = Template(**{
        **{k: v for k, v in doc.items() if k != "shots"},
        "shots": [Shot(
            start=float(sh.get("start", 0.0)), duration=float(sh["duration"]),
            motion=str(sh.get("motion", "static")), energy=float(sh.get("energy", 0.0)),
        ) for sh in doc["shots"]],
    })
    return save_template(template)


def starters() -> list[dict]:
    """A few hand-authored rhythms so a fresh gallery is not an empty room.

    These are not analysed from a video; they are editing grammars written down —
    a fast on-beat cut, a slow held one, a talking-head pace — each labelled as a
    starter so nobody mistakes one for a measured reference. Saving one copies it
    into the user's gallery where it can be deleted like anything else.
    """
    def make(name, bpm, lengths, motion, aspect):
        shots, at = [], 0.0
        for length in lengths:
            shots.append(Shot(start=round(at, 3), duration=length, motion=motion, energy=0.5))
            at += length
        return Template(
            name=name, source="", duration=round(at, 3), aspect=aspect,
            width=1080, height=1920 if aspect == "9:16" else 1080,
            shots=shots, bpm=float(bpm), cuts_on_beat=0.6,
            mean_shot=round(at / len(lengths), 3), median_shot=float(lengths[len(lengths) // 2]),
            shortest_shot=float(min(lengths)),
            motion_mix={motion: 1.0}, transitions={"count": len(lengths) - 1, "soft": 0, "type": "cut", "duration": 0.4},
            captions={"wanted": False}, audio={"hasBed": False},
        ).as_dict()

    return [
        make("starter · fast on-beat", 128, [0.5, 0.75, 0.5, 0.75, 0.5, 1.0, 0.5, 0.75], "static", "9:16"),
        make("starter · slow held", 80, [3.0, 2.5, 3.5, 3.0], "push", "16:9"),
        make("starter · talking head", 100, [2.0, 1.5, 2.5, 1.5, 2.0], "static", "9:16"),
    ]


def delete_template(name: str) -> None:
    (templates_dir() / f"{name}.cetemplate").unlink(missing_ok=True)


def suggest_transitions(timeline: dict, bpm: float = 120.0) -> list[dict]:
    """An "AI transitions" pass: one transition per video junction, sized to the
    music and varied by position, instead of a single type everywhere.

    The duration is half a beat (a cut that lands on the music reads as
    intentional), clamped so a very fast or very slow tempo cannot produce a
    subliminal flash or a dissolve longer than the clip. The type alternates
    between a soft and a directional move so a montage does not read as one
    repeated dissolve. It only *suggests*; the caller applies.
    """
    clips = [c for c in timeline.get("clips", []) if c.get("trackId") == "v1"]
    clips.sort(key=lambda c: float(c.get("start", 0)))
    half_beat = (60.0 / max(40.0, bpm)) / 2.0
    duration = round(max(0.2, min(0.8, half_beat)), 3)
    soft = ["fade", "smoothleft", "circleopen"]
    hard = ["slideleft", "wipeleft", "distance"]

    out = []
    for index in range(len(clips) - 1):
        a, b = clips[index], clips[index + 1]
        # Contiguous junction only; a gap means the user left a deliberate break.
        if abs((float(a["start"]) + float(a["duration"])) - float(b["start"])) > 0.05:
            continue
        family = soft if index % 2 == 0 else hard
        out.append({
            "fromClipId": a["id"], "toClipId": b["id"],
            "type": family[(index // 2) % len(family)],
            "duration": duration,
        })
    return out


def recipes() -> list[dict]:
    """Ready-made edit recipes: a starter rhythm plus the intent that makes it
    a *kind* of video — one click on Style Match, no questionnaire."""
    sts = starters()
    pick = lambda i: sts[i] if len(sts) > i else sts[0]
    return [
        {"id": "reels-punch", "fa": "ریلز کوبندهٔ ورزشی", "en": "Punchy sport reel",
         "intent": {"kind": "sport", "goal": "hook", "energy": "punchy",
                    "platform": "instagram_reels"},
         "template": pick(0)},
        {"id": "lesson-calm", "fa": "آموزش آرام با زیرنویس فارسی", "en": "Calm lesson, Persian captions",
         "intent": {"kind": "tutorial", "goal": "teach", "energy": "calm", "captions": "fa"},
         "template": pick(2)},
        {"id": "vlog-story", "fa": "ولاگ روایی متعادل", "en": "Balanced story vlog",
         "intent": {"kind": "vlog", "goal": "story", "energy": "balanced"},
         "template": pick(1)},
    ]
