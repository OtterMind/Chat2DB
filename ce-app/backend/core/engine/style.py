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

from core.brain import meaning as brain_meaning
from core.brain import objective
from core.brain import race as brain_race
from core.engine import analyze as analysis
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
        template.audio = {"musicUnderVoice": -9.0 if template.speech_ratio > 0.25 else 0.0}
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
        "the reference's own footage, music and fonts (never copied)",
        "exact caption typography (needs the OCR pass)",
    ]
    say("done", 1.0, "Template ready")
    return template


# ------------------------------------------------------------------ planning


def _highlights(path: str, wanted: int, minimum: float, window: float = 0.0) -> list[dict]:
    """Candidate moments in the user's footage — **many small ones**, best first.

    This function used to return whole ranges: one uninterrupted minute of
    talking came back as a single pick, and a twenty-shot template then took
    twenty clips from the same starting second. The result was the same
    half-second of footage repeated twenty times, which is exactly how it
    looked. (Measured on a 60 s clip against a 20-shot template: 20 clips, one
    unique offset.)

    So a long range is now **sliced into shot-sized windows**, each scored on
    its own, and the ranking happens between windows. A speech range of a
    minute becomes forty candidates instead of one.
    """
    info = probe_media(path)
    duration = float(info.get("duration") or 0.0)
    span_length = max(0.4, window or minimum)
    #: Overlap the windows a little so a good moment is not split down the middle.
    stride = max(0.25, span_length * 0.75)

    ranges: list[tuple[float, float, float]] = []  # (start, end, weight)

    if info.get("has_audio"):
        silences = analysis.detect_silence(path)
        for span in analysis.keep_ranges(duration, silences):
            if span.duration >= minimum * 0.6:
                ranges.append((span.start, span.end, 1.0))

    if not ranges:
        cuts = [c for c in analysis.detect_scenes(path) if 0 < c < duration]
        bounds = [0.0, *cuts, duration]
        for start, end in zip(bounds, bounds[1:]):
            if end - start >= minimum * 0.6:
                _, energy = _classify_motion(path, start, end - start)
                ranges.append((start, end, 0.2 + energy))

    if not ranges:
        ranges = [(0.0, duration, 1.0)]

    picks: list[dict] = []
    for start, end, weight in ranges:
        cursor = start
        while cursor + span_length * 0.7 <= end:
            finish = min(end, cursor + span_length)
            picks.append({
                "start": round(cursor, 3),
                "end": round(finish, 3),
                # Prefer the *middle* of a long take: the first second of a
                # sentence is usually someone drawing breath.
                "score": round(weight * (0.85 + 0.15 * min(1.0, (finish - cursor) / span_length)), 4),
            })
            cursor += stride
        if not picks or picks[-1]["end"] < end - 0.05:
            if end - cursor > 0.2:
                picks.append({"start": round(cursor, 3), "end": round(end, 3), "score": round(weight * 0.8, 4)})

    if not picks:
        picks = [{"start": 0.0, "end": duration, "score": 1.0}]

    picks.sort(key=lambda p: p["score"], reverse=True)
    return picks[: max(wanted, 1)]


def _brain_context(
    data: dict,
    shots: list[dict],
    source: str,
    info: dict,
    measured: list[dict],
    captions: list[dict] | None,
    music: str | None,
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
            silences = analysis.detect_silence(source)
            speech = [(r.start, r.end) for r in analysis.keep_ranges(duration, silences)]
        except Exception:  # noqa: BLE001
            speech = []

    words: list[dict] = []
    for cue in captions or []:
        words.extend(cue.get("words") or [])

    return objective.Context(
        duration=duration,
        target_shots=[float(s["duration"]) for s in shots],
        beats=beats,
        reference_cuts_on_beat=data.get("cuts_on_beat"),
        speech=speech,
        words=words,
        best_highlight=max((p.get("score", 0.0) for p in measured), default=0.0),
    )


def build_timeline(
    template: Template | dict,
    source: str,
    name: str = "Styled edit",
    music: str | None = None,
    captions: list[dict] | None = None,
    progress=None,
    brain: bool = True,
    model: str | None = None,
) -> dict:
    """Cut the user's footage into the shape of the template.

    Returns an editor document (tracks, clips, transitions) — the same structure
    the timeline saves and the compositor renders, so what the user sees in the
    editor is exactly what will be exported.

    This is the *automatic* door of the app: no prompt, no parameters. Whatever
    the template implies is carried out here — the cut rhythm, the colour, the
    camera moves, and, when they are available, the captions and a ducked music
    bed. Anything that could not be done is reported in `summary.skipped` rather
    than quietly dropped.
    """
    say = progress or _silent
    say("plan", 0.05, "Reading the template")
    data = template.as_dict() if isinstance(template, Template) else dict(template)
    shots = data.get("shots") or []
    if not shots:
        shots = [{"duration": max(1.0, data.get("mean_shot") or 2.0), "motion": "static"}]

    info = probe_media(source)
    source_duration = float(info.get("duration") or 0.0)
    shortest = min(float(s["duration"]) for s in shots)
    say("highlights", 0.2, "Choosing the strongest moments")
    typical = float(np.median([float(s["duration"]) for s in shots]))
    measured = _highlights(
        source,
        # Ask for far more candidates than shots, so the planner has somewhere
        # else to go instead of using the same moment again.
        wanted=max(len(shots) * 4, 24),
        minimum=max(0.4, shortest * 0.8),
        window=max(0.5, typical),
    )

    # ---- meaning ----------------------------------------------------------
    # Loudness finds energy; the transcript finds the sentence where the point
    # is made. When there are captions, half of a moment's strength comes from
    # what was said in it (`core/brain/meaning.py`).
    if captions:
        strongest = max((p.get("score", 0.0) for p in measured), default=0.0) or 1.0
        for moment in measured:
            sense = brain_meaning.score_window(captions, moment["start"], moment["end"])
            moment["score"] = brain_meaning.blend(moment.get("score", 0.0) / strongest, sense)
            moment["meaning"] = round(sense, 4)

    # ---- the brain --------------------------------------------------------
    # Measuring produced the candidate moments; *choosing and ordering* them is
    # a judgement, so it is raced: the deterministic rule planner against a
    # local Ollama model, both scored by the same objective function. The rule
    # plan is always a candidate, so the model can only win by being better.
    say("plan", 0.45, "Choosing an order")
    brain_context = _brain_context(data, shots, source, info, measured, captions, music)
    decision = brain_race.race(
        [objective.Pick(p["start"], p["end"], p.get("score", 0.0)) for p in measured],
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

    for index, shot in enumerate(shots):
        want = float(shot["duration"])
        pick = picks[index % len(picks)]  # the brain returns one per shot; this is the net
        available = max(0.2, pick["end"] - pick["start"])
        length = min(want, available, max(0.2, source_duration - pick["start"]))
        if length < 0.2:
            continue

        motion = shot.get("motion", "static")
        keyframes: list[dict] = []
        if motion == "push":
            keyframes = [{"t": 0, "scale": 1.0}, {"t": round(length, 3), "scale": 1.12}]
        elif motion == "pull":
            keyframes = [{"t": 0, "scale": 1.12}, {"t": round(length, 3), "scale": 1.0}]
        elif motion == "pan":
            keyframes = [{"t": 0, "x": -0.06, "scale": 1.1}, {"t": round(length, 3), "x": 0.06, "scale": 1.1}]

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
    if transitions:
        applied.append(f"{len(transitions)} × {transition_kind}")

    # ---- captions -------------------------------------------------------
    caption_style = data.get("captions") or {}
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
        "summary": {
            "shots": len([c for c in clips if c["trackId"] == "v1"]),
            "duration": round(cursor, 3),
            "fromHighlights": len(picks),
            "motion": [c["label"].split("· ")[-1] for c in clips if c["trackId"] == "v1"],
            "captions": len([c for c in clips if c["trackId"] == "t1"]),
            "bpm": data.get("bpm", 0.0),
            "applied": applied,
            "skipped": skipped,
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
    target = templates_dir() / f"{template.name}.cetemplate"
    target.write_text(json.dumps(template.as_dict(), indent=2), encoding="utf-8")
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


def delete_template(name: str) -> None:
    (templates_dir() / f"{name}.cetemplate").unlink(missing_ok=True)
