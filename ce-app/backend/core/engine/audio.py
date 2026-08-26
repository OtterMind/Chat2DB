"""Audio analysis: waveform peaks and beat detection.

Both answers come from the same place — the decoded samples — and both are
computed with FFmpeg plus NumPy, which the backend already ships. The obvious
libraries here (librosa for beats, audiowaveform for peaks) would add either a
large dependency tree or a GPL binary for maths that fits on one screen.

Nothing here is clever for its own sake: an editor needs a waveform so a cut can
be aimed at a word, and beat times so a cut can land on the music.
"""
from __future__ import annotations

import hashlib
import json
import math
import subprocess
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from app.config import settings
from core.engine.compose import ffmpeg_binary

SAMPLE_RATE = 22_050


def _cache_dir() -> Path:
    path = settings.data_dir / "audio"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _cache_path(source: Path, kind: str, detail: str = "") -> Path:
    stat = source.stat()
    key = hashlib.sha1(
        f"{source.resolve()}|{stat.st_mtime_ns}|{stat.st_size}|{kind}|{detail}".encode()
    ).hexdigest()[:20]
    return _cache_dir() / f"{kind}-{key}.json"


def decode_mono(path: str, sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    """The whole file as mono float32 in [-1, 1]. Raises if there is no audio."""
    out = subprocess.run(
        [
            ffmpeg_binary(), "-hide_banner", "-loglevel", "error",
            "-i", str(path), "-vn", "-ac", "1", "-ar", str(sample_rate),
            "-f", "s16le", "-",
        ],
        capture_output=True, check=True,
    )
    if not out.stdout:
        raise ValueError("no audio stream")
    return np.frombuffer(out.stdout, dtype=np.int16).astype(np.float32) / 32768.0


# ------------------------------------------------------------------- peaks


def peaks(path: str, points: int = 800) -> dict:
    """A min/max envelope with `points` buckets, cached on disk.

    Buckets, not samples: a waveform drawn from every sample of a ten-minute file
    would move a hundred megabytes to the renderer to paint a strip 600 px wide.

    A file with no audio is not an error — it is a clip that draws no waveform —
    so it answers with an empty envelope instead of raising. Anything else fills
    the console with failures for a perfectly normal silent video.
    """
    source = Path(path)
    if not source.exists():
        raise FileNotFoundError(path)
    points = max(32, min(4000, int(points)))
    cached = _cache_path(source, "peaks", str(points))
    if cached.exists():
        return json.loads(cached.read_text(encoding="utf-8"))

    try:
        samples = decode_mono(path)
    except (ValueError, subprocess.CalledProcessError):
        empty = {"duration": 0.0, "points": 0, "peaks": []}
        cached.write_text(json.dumps(empty), encoding="utf-8")
        return empty
    duration = len(samples) / SAMPLE_RATE
    usable = (len(samples) // points) * points
    if usable == 0:
        buckets = np.abs(samples).reshape(1, -1) if len(samples) else np.zeros((1, 1))
    else:
        buckets = samples[:usable].reshape(points, -1)

    envelope = np.abs(buckets).max(axis=1)
    loudest = float(envelope.max()) or 1.0
    result = {
        "duration": round(duration, 3),
        "points": len(envelope),
        # Normalised so a quiet recording still draws a readable shape.
        "peaks": [round(float(v) / loudest, 4) for v in envelope],
    }
    cached.write_text(json.dumps(result), encoding="utf-8")
    return result


# ------------------------------------------------------------------- beats


@dataclass
class BeatResult:
    bpm: float
    beats: list[float]
    confidence: float


def _spectral_flux(samples: np.ndarray, hop: int, window: int) -> tuple[np.ndarray, float]:
    """Onset strength: how much energy *appeared* since the previous frame."""
    frames = 1 + max(0, (len(samples) - window) // hop)
    if frames < 4:
        return np.zeros(0), hop / SAMPLE_RATE
    hann = np.hanning(window).astype(np.float32)
    spectra = np.empty((frames, window // 2 + 1), dtype=np.float32)
    for i in range(frames):
        chunk = samples[i * hop : i * hop + window] * hann
        spectra[i] = np.abs(np.fft.rfft(chunk))
    # Half-wave rectified difference: only growth counts as an onset.
    diff = np.diff(spectra, axis=0)
    flux = np.maximum(diff, 0).sum(axis=1)
    flux = np.concatenate([[0.0], flux])
    # Remove the slow drift so a fade-in is not read as a beat.
    smoothed = np.convolve(flux, np.ones(16) / 16, mode="same")
    flux = np.maximum(flux - smoothed, 0)
    peak = float(flux.max()) or 1.0
    return flux / peak, hop / SAMPLE_RATE


def beats(path: str, minimum_bpm: float = 60.0, maximum_bpm: float = 200.0) -> BeatResult:
    """Tempo and beat times, from onset strength + autocorrelation.

    The tempo is the lag that best explains the onsets; the grid is then laid
    from the strongest onset so the beats sit on the music rather than on the
    start of the file.
    """
    source = Path(path)
    if not source.exists():
        raise FileNotFoundError(path)
    cached = _cache_path(source, "beats")
    if cached.exists():
        data = json.loads(cached.read_text(encoding="utf-8"))
        return BeatResult(bpm=data["bpm"], beats=data["beats"], confidence=data["confidence"])

    samples = decode_mono(path)
    hop, window = 256, 1024
    flux, seconds_per_frame = _spectral_flux(samples, hop, window)
    if flux.size < 8:
        return BeatResult(bpm=0.0, beats=[], confidence=0.0)

    centred = flux - flux.mean()
    correlation = np.correlate(centred, centred, mode="full")[len(centred) - 1 :]
    lag_low = max(1, int(round(60.0 / maximum_bpm / seconds_per_frame)))
    lag_high = min(len(correlation) - 1, int(round(60.0 / minimum_bpm / seconds_per_frame)))
    if lag_high <= lag_low:
        return BeatResult(bpm=0.0, beats=[], confidence=0.0)

    window_slice = correlation[lag_low : lag_high + 1]
    best_lag = int(np.argmax(window_slice)) + lag_low
    strength = float(window_slice.max())

    # Octave correction.
    #
    # Autocorrelation answers "what period repeats", and a beat every 0.4 s also
    # repeats every 0.8 s — often more strongly, because two beats agree better
    # than one. Without this a 150 BPM track is reported as 75. If half (or a
    # third) of the winning lag is nearly as strong and still inside the allowed
    # range, the faster reading is the true tempo.
    for divisor in (4, 3, 2):
        candidate = int(round(best_lag / divisor))
        if candidate < lag_low or candidate > lag_high:
            continue
        # Compare the best value in a small neighbourhood: the exact sample can
        # fall between two lags.
        low = max(lag_low, candidate - 1)
        high = min(lag_high, candidate + 1)
        neighbourhood = float(correlation[low : high + 1].max())
        if neighbourhood >= strength * 0.8:
            best_lag = int(np.argmax(correlation[low : high + 1])) + low
            strength = neighbourhood
            break
    confidence = strength / (float(correlation[0]) or 1.0)
    period = best_lag * seconds_per_frame
    bpm = 60.0 / period if period > 0 else 0.0

    # Phase: start the grid at the strongest onset inside the first period.
    first_period_frames = min(len(flux), best_lag)
    anchor_frame = int(np.argmax(flux[:first_period_frames])) if first_period_frames else 0
    anchor = anchor_frame * seconds_per_frame

    duration = len(samples) / SAMPLE_RATE
    times: list[float] = []
    t = anchor
    while t < duration and period > 0.05:
        times.append(round(t, 3))
        t += period

    result = BeatResult(bpm=round(bpm, 2), beats=times, confidence=round(confidence, 4))
    cached.write_text(
        json.dumps({"bpm": result.bpm, "beats": result.beats, "confidence": result.confidence}),
        encoding="utf-8",
    )
    return result


# ------------------------------------------------------------------ ducking


def voice_envelope(
    sources: list[tuple[str, float, float, float]],
    total: float,
    *,
    step: float = 0.05,
    threshold: float = 0.02,
) -> np.ndarray:
    """How loud the voice is across the whole timeline, in `step` buckets.

    `sources` is (path, timeline_start, source_offset, duration) per speaking
    clip. The result is a 0/1-ish activity curve, not a level: what ducking needs
    to know is *when* someone is speaking.
    """
    buckets = max(1, int(math.ceil(total / step)))
    activity = np.zeros(buckets, dtype=np.float32)

    for path, start, offset, duration in sources:
        try:
            samples = decode_mono(path)
        except (ValueError, subprocess.CalledProcessError, FileNotFoundError):
            continue
        begin = int(max(0.0, offset) * SAMPLE_RATE)
        end = min(len(samples), begin + int(max(0.0, duration) * SAMPLE_RATE))
        window = samples[begin:end]
        if window.size == 0:
            continue
        per_bucket = max(1, int(step * SAMPLE_RATE))
        usable = (window.size // per_bucket) * per_bucket
        if usable == 0:
            continue
        levels = np.abs(window[:usable].reshape(-1, per_bucket)).mean(axis=1)
        loud = (levels > threshold).astype(np.float32)
        first = int(round(start / step))
        last = min(buckets, first + loud.size)
        if last > first:
            activity[first:last] = np.maximum(activity[first:last], loud[: last - first])

    return activity


def ducking_points(
    activity: np.ndarray,
    *,
    step: float = 0.05,
    depth: float = 0.25,
    attack: float = 0.15,
    release: float = 0.45,
) -> list[tuple[float, float]]:
    """Turn the activity curve into (time, gain) points for the music bed.

    Attack and release are applied here, in Python, so the result is a plain
    automation curve: identical on every render, visible as numbers, and free of
    the failure mode that made `sidechaincompress` go silent when its sidechain
    input ran dry under load.
    """
    gain = np.ones_like(activity, dtype=np.float32)
    attack_steps = max(1, int(attack / step))
    release_steps = max(1, int(release / step))

    current = 1.0
    for i, speaking in enumerate(activity):
        target = depth if speaking > 0.5 else 1.0
        if target < current:
            current = max(target, current - (1.0 - depth) / attack_steps)
        else:
            current = min(target, current + (1.0 - depth) / release_steps)
        gain[i] = current

    # Keep only the points where the curve changes direction or value enough to
    # matter: a thousand identical points would make the filter string enormous.
    points: list[tuple[float, float]] = [(0.0, float(gain[0]))]
    for i in range(1, len(gain)):
        if abs(float(gain[i]) - points[-1][1]) >= 0.02:
            points.append((round(i * step, 3), float(gain[i])))
    points.append((round(len(gain) * step, 3), float(gain[-1])))
    return points
