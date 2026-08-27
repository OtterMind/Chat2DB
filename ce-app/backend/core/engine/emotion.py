"""Cut on emotion — the roar, not the meter (advisors' B2).

Every signal the highlight scorer has is about *energy and shape*: how loud a
moment is, how much the picture moves, where a cut falls. A goal being scored is
louder than the goal *celebration*, and the celebration is the shot. What marks
that difference is not level — it is **what the sound is made of**.

So this module measures what can be measured with the maths the backend already
ships (FFmpeg + NumPy, no new dependency) and says plainly what each number is:

| cue | what is actually measured |
|---|---|
| `crowd` | broadband, noise-like, loud, not tonal — applause and cheering |
| `voiced` | tonal bursts with a 2–9 Hz amplitude rhythm — laughter *or* a sentence |
| `whoosh` | a fast broadband rise that dies again — a swing, a pass, a wipe |
| `speech` | the app's own speech map (VAD), not a second opinion of it |
| `joy` | `0.9·crowd + 0.3·voiced`, clamped — "people reacted" |

**This is not facial-emotion recognition and it does not claim to be.** The
advisors suggested a `vit-fer` model; there is no `vit-fer` on PyPI (verified:
`vit-fer`, `vit_fer`, `fer-vit` all 404), so there is no wheel whose METADATA
could be read, and a model we cannot licence-check does not enter the install
path. Two honest doors cover the visual half instead, both optional:
MediaPipe FaceLandmarker blendshapes (Apache-2.0, already an accepted on-demand
engine — smile/frown/surprise are *action units*, and are named as such), and a
`emotion.score` **provider** (`core/providers/channel.py`) for anyone who wants to plug
their own model in as a separate process. Both are absent by default and their
absence changes nothing.

Like the vision vote (§4.57) the result is **one vote, never a veto**: the
weight is capped at `MAX_WEIGHT` and the numbers are shown to the user in
Settings → *Cut on emotion* → *Measure it*, so the verdict stays with their own
footage rather than with a claim in this file.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from app.config import settings
from core.engine import audio as audio_engine

#: Cue frames per second. 4 fps is 250 ms — shorter than a clap burst, long
#: enough to hold an amplitude-modulation rhythm.
FPS = 4.0
SR = 16_000

#: The most emotion may move a candidate's highlight score. A measurement with a
#: threshold nobody has verified on the user's footage gets a seat, not a veto.
MAX_WEIGHT = 0.25
#: How much of the blend a detected face may take, when one is detected at all.
FACE_WEIGHT = 0.35

#: Amplitude-modulation band that reads as "a person reacting" (Hz). Laughter
#: sits near 3–6 Hz, shouts and clapping bursts near 4–9 Hz; a held note or a
#: steady room tone sits under it, a hiss over it.
AM_LOW, AM_HIGH = 2.0, 9.0

#: Part of the cache key. The cached cue JSON is a product of *this* maths, so
#: changing a formula has to invalidate it — otherwise an edit to a threshold
#: reads back the numbers the old threshold produced and looks like it did nothing.
CUE_VERSION = 2


class NoAudio(ValueError):
    """Raised when a file has no audio stream to measure."""


@dataclass
class Cues:
    """One row per cue frame — the measured reaction of the room over time."""

    fps: float
    duration: float
    energy: list[float]
    crowd: list[float]
    voiced: list[float]
    whoosh: list[float]
    speech: list[float]
    joy: list[float]

    @property
    def frames(self) -> int:
        return len(self.joy)

    def time_of(self, index: int) -> float:
        return index / self.fps

    def as_dict(self) -> dict:
        return {
            "fps": self.fps, "duration": round(self.duration, 3), "frames": self.frames,
            "energy": [round(v, 4) for v in self.energy],
            "crowd": [round(v, 4) for v in self.crowd],
            "voiced": [round(v, 4) for v in self.voiced],
            "whoosh": [round(v, 4) for v in self.whoosh],
            "speech": [round(v, 4) for v in self.speech],
            "joy": [round(v, 4) for v in self.joy],
        }


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def _band_fractions(power: np.ndarray, freqs: np.ndarray) -> dict[str, float]:
    """Where the frame's energy sits, as fractions of the 80–8000 Hz total.

    `hf` matters as much as the flatness does: a 220 Hz voice has almost nothing
    above 1 kHz, so its flatness up there describes the noise floor, not the
    sound. Weighting flatness by the share of energy that is actually up there is
    what keeps a bass voice from reading as applause.
    """
    band = (freqs >= 80) & (freqs <= 8000)
    total = float(power[band].sum())
    if total <= 0:
        return {"low": 0.0, "mid": 0.0, "high": 0.0, "hf": 0.0}

    def share(lo: float, hi: float) -> float:
        sel = band & (freqs >= lo) & (freqs < hi)
        return float(power[sel].sum()) / total

    return {"low": share(80, 300), "mid": share(300, 3400),
            "high": share(3400, 8000), "hf": share(1000, 8000)}


def _flatness(power: np.ndarray, freqs: np.ndarray) -> float:
    """Spectral flatness over 1–8 kHz: 1.0 = white noise, ~0 = a single tone."""
    sel = (freqs >= 1000) & (freqs <= 8000)
    band = power[sel]
    if band.size < 8:
        return 0.0
    band = np.maximum(band, 1e-12)
    geometric = float(np.exp(np.mean(np.log(band))))
    arithmetic = float(np.mean(band))
    if arithmetic <= 0:
        return 0.0
    return _clamp01(geometric / arithmetic)


def _harmonicity(frame: np.ndarray, sample_rate: int) -> float:
    """Autocorrelation peak in the 80–400 Hz lag range: 1.0 = perfectly tonal.

    Voice and laughter are periodic; applause and crowd noise are not. This is
    the one number that separates "a room full of people" from "a person".
    """
    x = frame - float(np.mean(frame))
    energy = float(np.dot(x, x))
    if energy <= 1e-9:
        return 0.0
    spectrum = np.fft.rfft(x, n=1 << int(np.ceil(np.log2(2 * x.size))))
    correlation = np.fft.irfft(spectrum * np.conj(spectrum))[: x.size]
    low, high = int(sample_rate / 400), int(sample_rate / 80)
    if high <= low + 1 or high >= correlation.size:
        return 0.0
    peak = float(np.max(correlation[low:high]))
    return _clamp01(peak / energy)


def _am_rate(frame: np.ndarray, sample_rate: int) -> float:
    """Dominant amplitude-modulation rate of one cue frame, in Hz.

    Measured by zero-crossings of the demeaned 10 ms RMS envelope — no filter
    bank, no wavelet, just the rhythm of the bursts.
    """
    hop = max(16, int(sample_rate * 0.01))
    count = frame.size // hop
    if count < 4:
        return 0.0
    envelope = np.array([
        float(np.sqrt(np.mean(np.square(frame[i * hop:(i + 1) * hop])))) for i in range(count)
    ])
    envelope = envelope - float(np.mean(envelope))
    if float(np.max(np.abs(envelope))) <= 1e-9:
        return 0.0
    signs = np.sign(envelope)
    signs[signs == 0] = 1.0
    crossings = int(np.sum(signs[1:] != signs[:-1]))
    return crossings / 2.0 / (count * hop / sample_rate)


def _speech_coverage(path: str, duration: float, fps: float) -> list[float]:
    """The app's own speech map, resampled onto the cue grid (never re-guessed)."""
    frames = max(1, int(duration * fps))
    try:
        from core.engine import analyze, vad as vad_engine  # noqa: PLC0415

        silences = vad_engine.silent_ranges_auto(path)
        speech = analyze.keep_ranges(duration, silences)
    except Exception:  # noqa: BLE001 — no audio / no VAD means "no speech map"
        return [0.0] * frames
    out: list[float] = []
    for index in range(frames):
        start, end = index / fps, (index + 1) / fps
        covered = sum(max(0.0, min(r.end, end) - max(r.start, start)) for r in speech)
        out.append(_clamp01(covered / max(1e-6, end - start)))
    return out


def audio_cues(path: str, fps: float = FPS) -> Cues:
    """Measure every cue frame of a file. Cached per file, like the other audio maths."""
    source = Path(path)
    cache = audio_engine._cache_path(source, "cues", f"v{CUE_VERSION}-{fps:g}")
    if cache.exists():
        try:
            doc = json.loads(cache.read_text(encoding="utf-8"))
            return Cues(fps=float(doc["fps"]), duration=float(doc["duration"]),
                        energy=doc["energy"], crowd=doc["crowd"], voiced=doc["voiced"],
                        whoosh=doc["whoosh"], speech=doc["speech"], joy=doc["joy"])
        except Exception:  # noqa: BLE001 — a bad cache is rebuilt, never fatal
            pass

    samples = audio_engine.decode_mono(path, SR)
    duration = samples.size / SR
    hop = max(1, int(SR / fps))
    count = max(1, samples.size // hop)

    levels: list[float] = []
    crowd_raw: list[float] = []
    voiced_raw: list[float] = []
    flat: list[float] = []
    hf: list[float] = []
    tone: list[float] = []
    am: list[float] = []
    speech_share: list[float] = []

    window = np.hanning(hop)
    freqs = np.fft.rfftfreq(hop, 1.0 / SR)
    for index in range(count):
        frame = samples[index * hop:(index + 1) * hop]
        if frame.size < hop:
            frame = np.pad(frame, (0, hop - frame.size))
        levels.append(float(np.sqrt(np.mean(np.square(frame)))))
        power = np.abs(np.fft.rfft(frame * window)) ** 2
        bands = _band_fractions(power, freqs)
        flat.append(_flatness(power, freqs))
        hf.append(bands["hf"])
        tone.append(_harmonicity(frame, SR))
        am.append(_am_rate(frame, SR))
        speech_share.append(bands["mid"])

    peak_level = max(levels) if levels else 0.0
    if peak_level <= 0:
        raise NoAudio("the file has no audible samples")
    # Relative, not absolute: "loud" is a comparison inside this file, so a quiet
    # room recording is scored against itself and never reads as dead air.
    level = [v / peak_level for v in levels]

    crowd: list[float] = []
    voiced: list[float] = []
    for index in range(count):
        noise_like = flat[index] * _clamp01(hf[index] * 2.5)
        loud = _clamp01(level[index] * 1.5)
        tonal = tone[index]
        crowd.append(_clamp01(noise_like * 1.6 * loud * (1.0 - _clamp01(tonal * 2.0))))
        rhythm = 1.0 if AM_LOW <= am[index] <= AM_HIGH else 0.35
        voiced.append(_clamp01(tonal * 1.6 * loud * _clamp01(speech_share[index] * 2.0) * rhythm))

    whoosh: list[float] = []
    for index in range(count):
        rise = max(0.0, level[index] - (level[index - 1] if index else 0.0))
        # A whoosh is a *transient*: it rises fast and dies again. Without this
        # gate a sustained applause bed — which also rises fast, every clap —
        # would read as a whoosh in every frame, and the multi-cam switcher would
        # treat the whole rally as a transition.
        after = level[index + 1:index + 3]
        decays = bool(after) and min(after) < level[index] * 0.6
        gate = 1.0 if decays else 0.15
        whoosh.append(_clamp01(rise * 3.0 * flat[index] * _clamp01(hf[index] * 2.5) * 1.6
                               * (1.0 - _clamp01(tone[index] * 2.0)) * gate))

    # Crowd-dominant on purpose. `voiced` cannot tell laughter from a sentence —
    # both are tonal bursts with a syllable rhythm — so it adds a little and the
    # broadband roar carries the verdict. Measured on the synthetic fixtures:
    # applause joy 0.57 vs a steady voice 0.25, which is the right way round.
    joy = [_clamp01(0.9 * crowd[i] + 0.3 * voiced[i]) for i in range(count)]
    speech = _speech_coverage(path, duration, fps)
    if len(speech) < count:
        speech += [0.0] * (count - len(speech))
    cues = Cues(fps=fps, duration=duration, energy=[_clamp01(v) for v in level],
                crowd=crowd, voiced=voiced, whoosh=whoosh, speech=speech[:count], joy=joy)
    try:
        cache.write_text(json.dumps(cues.as_dict()), encoding="utf-8")
    except OSError:
        pass
    return cues


def window_value(cues: Cues, start: float, end: float, key: str) -> float:
    """The mean of one cue over a time window — 0.0 when the window is empty."""
    values = getattr(cues, key)
    first = max(0, int(start * cues.fps))
    last = max(first + 1, int(end * cues.fps))
    band = values[first:min(len(values), last)]
    return float(np.mean(band)) if len(band) else 0.0


# --------------------------------------------------------------------- faces


def face_model_path() -> Path:
    return Path(settings.cuttingedge_home) / "runtime" / "models" / "face_landmarker.task"


FACE_MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/"
    "face_landmarker/float16/1/face_landmarker.task"
)


def face_available() -> bool:
    """MediaPipe installed *and* its 3.7 MB landmark model on disk. No faking."""
    import importlib.util  # noqa: PLC0415

    return importlib.util.find_spec("mediapipe") is not None and face_model_path().exists()


def fetch_face_model(on_progress=None) -> Path:
    """Download the FaceLandmarker model on demand (Apache-2.0, Google)."""
    import requests  # noqa: PLC0415

    target = face_model_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".part")
    with requests.get(FACE_MODEL_URL, stream=True, timeout=60) as response:
        response.raise_for_status()
        total = int(response.headers.get("content-length") or 0)
        seen = 0
        with tmp.open("wb") as handle:
            for chunk in response.iter_content(chunk_size=64 * 1024):
                if not chunk:
                    continue
                handle.write(chunk)
                seen += len(chunk)
                if on_progress and total:
                    on_progress(seen, total)
    tmp.replace(target)
    return target


_LANDMARKER = None


def _landmarker():
    global _LANDMARKER
    if _LANDMARKER is None:
        import mediapipe as mp  # noqa: PLC0415
        from mediapipe.tasks import python as mp_python  # noqa: PLC0415
        from mediapipe.tasks.python import vision as mp_vision  # noqa: PLC0415

        options = mp_vision.FaceLandmarkerOptions(
            base_options=mp_python.BaseOptions(model_asset_path=str(face_model_path())),
            output_face_blendshapes=True, num_faces=1,
        )
        _LANDMARKER = mp_vision.FaceLandmarker.create_from_options(options)
    return _LANDMARKER


def expression_from_blendshapes(categories) -> dict:
    """Blendshape scores → three named action units. Pure maths, testable alone.

    These are *muscle positions*, not feelings: a smile score is how far the
    mouth corners are pulled, and saying more than that would be a guess wearing
    a measurement's clothes.
    """
    values = {getattr(c, "category_name", ""): float(getattr(c, "score", 0.0) or 0.0)
              for c in (categories or [])}

    def pair(left: str, right: str) -> float:
        return (values.get(left, 0.0) + values.get(right, 0.0)) / 2.0

    smile = pair("mouthSmileLeft", "mouthSmileRight")
    frown = max(pair("mouthFrownLeft", "mouthFrownRight"),
                pair("browDownLeft", "browDownRight") * 0.6)
    surprise = max(pair("eyeWideLeft", "eyeWideRight"), values.get("jawOpen", 0.0) * 0.7)
    return {"smile": _clamp01(smile), "frown": _clamp01(frown),
            "surprise": _clamp01(surprise),
            "joy": _clamp01(smile * 1.2 + 0.3 * surprise)}


def face_expression(gray_frame) -> dict | None:
    """One grayscale analysis frame → action units, or None when nobody is seen."""
    if not face_available():
        return None
    try:
        import cv2  # noqa: PLC0415
        import mediapipe as mp  # noqa: PLC0415

        rgb = cv2.cvtColor(gray_frame, cv2.COLOR_GRAY2RGB)
        result = _landmarker().detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
        shapes = list(getattr(result, "face_blendshapes", []) or [])
        if not shapes:
            return None
        return expression_from_blendshapes(shapes[0].category)
    except Exception:  # noqa: BLE001 — an upstream mismatch degrades, never crashes
        return None


# --------------------------------------------------------------- the verdict


def sources() -> list[dict]:
    """What is actually able to weigh in right now — shown, not assumed."""
    rows = [{
        "id": "audio", "name": "Audio cues (built-in)",
        "active": True, "licence": "ours (NumPy + FFmpeg)",
        "detail": "crowd / laughter / whoosh from spectrum shape, level and rhythm",
    }, {
        "id": "face", "name": "MediaPipe FaceLandmarker",
        "active": face_available(), "licence": "Apache-2.0",
        "detail": "on-demand engine + 3.7 MB model — smile / frown / surprise action units",
    }]
    try:
        from core.providers import channel as providers  # noqa: PLC0415

        plugged = providers.hook_providers("emotion.score")
    except Exception:  # noqa: BLE001
        plugged = []
    rows.append({
        "id": "provider", "name": "Installed providers",
        "active": bool(plugged), "licence": "declared per provider",
        "detail": ", ".join(p["id"] for p in plugged) if plugged
        else "none — drop a folder in ~/CuttingEdge/providers",
    })
    return rows


def available() -> bool:
    """The built-in cue maths always is; that is what makes this a measurement."""
    return True


def _provider_scores(path: str, times: list[float]) -> dict[float, float]:
    try:
        from core.providers import channel as providers  # noqa: PLC0415

        answers = providers.hook("emotion.score", {"path": str(path), "times": times}, timeout=15.0)
    except Exception:  # noqa: BLE001
        return {}
    out: dict[float, float] = {}
    for answer in answers:
        for key, value in (answer.get("scores") or {}).items():
            try:
                out[round(float(key), 2)] = _clamp01(float(value))
            except (TypeError, ValueError):
                continue
    return out


def score_moments(path: str, times: list[float]) -> dict[float, float]:
    """Emotion strength (0..1) at each moment — one vote for the highlight scorer."""
    times = [float(t) for t in times]
    if not times:
        return {}
    try:
        cues = audio_cues(path)
    except Exception:  # noqa: BLE001 — no audio is a normal answer
        return {}
    out = {round(t, 2): _clamp01(window_value(cues, t - 1.0, t + 0.5, "joy")) for t in times}

    plugged = _provider_scores(path, times)
    for key, value in plugged.items():
        out[key] = _clamp01(0.5 * out.get(key, 0.0) + 0.5 * value)

    if face_available():
        from core.engine.style import sample_gray  # noqa: PLC0415

        faces = 0
        for time in times:
            frame = sample_gray(path, time)
            if frame is None:
                continue
            expression = face_expression(frame)
            if not expression:
                continue
            key = round(float(time), 2)
            out[key] = _clamp01((1 - FACE_WEIGHT) * out.get(key, 0.0) + FACE_WEIGHT * expression["joy"])
            faces += 1
        if not faces:
            pass  # nobody detected — the audio vote stands alone, unchanged
    return out


def preview(path: str, count: int = 12) -> dict:
    """The numbers, in the open — the Settings *Measure it* button.

    Returns the strongest cue frames of the file with the raw cues behind them,
    so "did it work?" is answered by the user's own footage and not by a claim.
    """
    cues = audio_cues(path)
    order = sorted(range(cues.frames), key=lambda i: -cues.joy[i])
    peaks: list[dict] = []
    for index in order:
        at = round(cues.time_of(index), 2)
        if all(abs(at - p["t"]) > 1.0 for p in peaks):
            peaks.append({"t": at, "joy": round(cues.joy[index], 3),
                          "crowd": round(cues.crowd[index], 3),
                          "voiced": round(cues.voiced[index], 3),
                          "whoosh": round(cues.whoosh[index], 3),
                          "speech": round(cues.speech[index], 3)})
        if len(peaks) >= max(1, min(count, 40)):
            break
    return {
        "duration": round(cues.duration, 2),
        "frames": cues.frames,
        "meanJoy": round(float(np.mean(cues.joy)) if cues.joy else 0.0, 3),
        "peaks": sorted(peaks, key=lambda p: p["t"]),
        "sources": sources(),
    }
