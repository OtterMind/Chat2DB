"""A speech map from silero-vad, as an on-demand engine.

Everything the edit rests on starts with *where the speech is*: which moments are
candidates, where a cut may land without breaking a word, how much of the file is
talking. Today that comes from FFmpeg's `silencedetect`, which measures **energy**
— so music, traffic and a loud room all read as speech, and a quiet speaker reads
as silence. silero-vad is a model trained on speech, so it answers a different
question: not "is it loud" but "is someone talking".

**Licence:** MIT, read from the wheel's own `METADATA` rather than from a README,
because the two have disagreed before (`piper` is MIT on GitHub and GPL-3 on
PyPI — OSS_SURVEY §4).

**Cost:** the model is **2.22 MB** and `onnxruntime` already ships with
`faster-whisper`, so nothing new enters the installer. The model is fetched on
demand, the way Whisper models and the CUDA libraries already are (§4.67).

**Judgement: not yet given, and deliberately so.** A verdict of "better" needs
real speech, and a claim about a model that was not measured on the material it
will be used on is a brochure (§4.57). So `compare()` runs *both* methods on the
same file and reports the numbers, and the Settings card runs it on the user's
own footage. Until that has been read, this engine is opt-in and `silencedetect`
remains the default — nothing about the shipped behaviour changes.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from app.config import settings
from core.engine import analyze as analysis
from core.engine import audio as audio_engine
from core.engine.compose import probe_media

#: The model runs on 16 kHz mono. Its window is 512 samples and it wants the
#: previous 64 samples as context, so each step feeds 576 and advances 512.
SAMPLE_RATE = 16_000
WINDOW = 512
CONTEXT = 64

#: Above this probability a chunk counts as speech.
THRESHOLD = 0.5

_MODEL_NAME = "silero_vad.onnx"
_PACKAGE = "silero-vad"


def model_dir() -> Path:
    return Path(settings.cuttingedge_home) / "models"


def model_path() -> Path:
    return model_dir() / _MODEL_NAME


def installed() -> bool:
    """Is the model on disk *and* is there something that can run it?"""
    if not model_path().exists():
        return False
    try:
        import onnxruntime  # noqa: F401, PLC0415
    except Exception:  # noqa: BLE001
        return False
    return True


def status() -> dict:
    """What this machine has, checked rather than assumed."""
    try:
        import onnxruntime  # noqa: PLC0415

        runtime = onnxruntime.__version__
    except Exception:  # noqa: BLE001
        runtime = None
    return {
        "model": model_path().exists(),
        "modelPath": str(model_path()),
        "modelMb": round(model_path().stat().st_size / 1048576, 2) if model_path().exists() else 0.0,
        "onnxruntime": runtime,
        "ready": installed(),
        "licence": "MIT",
    }


def fetch(progress=None) -> Path:
    """Download the model — 2.22 MB — and put it where the engine looks.

    `pip download --no-deps` and not `pip install`: the package declares
    `torch>=1.12` and `torchaudio`, so installing it would pull several hundred
    megabytes of wheels to run a 2 MB model that `onnxruntime` — already in the
    installer, via faster-whisper — runs perfectly well. We take one file out of
    the wheel and leave the rest of it on PyPI.
    """
    say = progress or (lambda stage, fraction, label="": None)
    if model_path().exists():
        return model_path()

    model_dir().mkdir(parents=True, exist_ok=True)
    say("download", 0.1, "Downloading the speech model (2.2 MB)")
    with tempfile.TemporaryDirectory(prefix="ce-vad-") as tmp:
        # The packaged embeddable Python has no pip, so fetch the wheel straight
        # from PyPI with the stdlib (a wheel is just a zip).
        from core.engine import _pypi  # noqa: PLC0415

        wheel = _pypi.download_wheel(_PACKAGE, Path(tmp))

        say("extract", 0.8, "Taking the model out of the package")
        found = _extract_model(wheel)
        if found is None:
            raise RuntimeError(f"{wheels[0].name} does not contain {_MODEL_NAME}")
        shutil.copyfile(found, model_path())

    say("done", 1.0, "Speech model ready")
    return model_path()


def _extract_model(archive: Path) -> Path | None:
    """Pull `silero_vad.onnx` out of a wheel (or an sdist) into a temp folder."""
    out = archive.parent / _MODEL_NAME
    if archive.suffix == ".whl":
        with zipfile.ZipFile(archive) as wheel:
            member = next((n for n in wheel.namelist() if n.endswith(f"data/{_MODEL_NAME}")), None)
            if member is None:
                return None
            out.write_bytes(wheel.read(member))
        return out
    import tarfile  # noqa: PLC0415

    with tarfile.open(archive) as tar:
        member = next((m for m in tar.getmembers() if m.name.endswith(f"data/{_MODEL_NAME}")), None)
        if member is None:
            return None
        handle = tar.extractfile(member)
        if handle is None:
            return None
        out.write_bytes(handle.read())
    return out


# --------------------------------------------------------------------- running


@dataclass
class _Session:
    """One pass over a file, with the model's state carried between chunks."""

    session: object
    state: np.ndarray
    sr: np.ndarray


def _open():
    import onnxruntime  # noqa: PLC0415

    session = onnxruntime.InferenceSession(
        str(model_path()), providers=["CPUExecutionProvider"]
    )
    return _Session(
        session=session,
        state=np.zeros((2, 1, 128), dtype=np.float32),
        sr=np.array(SAMPLE_RATE, dtype=np.int64),
    )


def probabilities(samples: np.ndarray) -> np.ndarray:
    """One speech probability per 512-sample chunk.

    The model is stateful: it carries 128 numbers between chunks, which is what
    lets it hear a word that straddles a boundary. Resetting the state per chunk
    still runs, and still looks plausible, and quietly gets boundaries wrong —
    so the state is created once per file, here.
    """
    if not installed():
        raise RuntimeError("the speech model is not installed")
    session = _open()
    if samples.size == 0:
        return np.zeros(0, dtype=np.float32)

    padded = np.concatenate([np.zeros(CONTEXT, dtype=np.float32), samples])
    total = (len(padded) - CONTEXT) // WINDOW
    out = np.zeros(total, dtype=np.float32)
    for index in range(total):
        start = index * WINDOW
        chunk = padded[start : start + WINDOW + CONTEXT].reshape(1, -1)
        result, session.state = session.session.run(  # type: ignore[attr-defined]
            None, {"input": chunk, "state": session.state, "sr": session.sr}
        )
        out[index] = float(result[0][0])
    return out


def silent_ranges(
    path: str,
    *,
    threshold: float = THRESHOLD,
    min_silence: float = 0.35,
    padding: float = 0.05,
) -> list[analysis.Range]:
    """Silent stretches, in the same shape `analyze.detect_silence` returns.

    Same return type on purpose: a caller that already inverts silence into
    speech with `keep_ranges()` can take either source, which is what makes this
    a drop-in comparison rather than a second system.
    """
    info = probe_media(path)
    duration = float(info.get("duration") or 0.0)
    if not info.get("has_audio") or duration <= 0:
        return []

    samples = audio_engine.decode_mono(path, sample_rate=SAMPLE_RATE)
    if samples.size == 0:
        return []
    probs = probabilities(samples.astype(np.float32))
    if probs.size == 0:
        return []

    step = WINDOW / SAMPLE_RATE
    speech = probs >= threshold
    silences: list[analysis.Range] = []
    cursor = 0.0
    for index, is_speech in enumerate(speech):
        at = index * step
        if is_speech:
            if at - cursor >= min_silence:
                silences.append(analysis.Range(round(cursor, 3), round(at + padding, 3)))
            cursor = at + step
    tail = (probs.size * step) - cursor
    if tail >= min_silence:
        silences.append(analysis.Range(round(cursor, 3), round(duration, 3)))
    return silences


def silent_ranges_auto(path: str, **kwargs) -> list[analysis.Range]:
    """Silent stretches from whichever engine the user chose.

    The default is the one every release so far used, and it stays the default
    until the comparison below has been read on real speech. If the model is
    chosen but missing — a fresh install, a machine that never fetched it — this
    falls back rather than failing: an edit that cannot be built is worse than
    one built on the older measurement, and the fallback is reported.
    """
    if (settings.speech_engine or "energy").strip().lower() == "silero" and installed():
        return silent_ranges(path, **kwargs)
    return analysis.detect_silence(path)


def speech_ranges(path: str, **kwargs) -> list[analysis.Range]:
    """Where someone is talking, as ranges — the thing the edit actually wants."""
    info = probe_media(path)
    duration = float(info.get("duration") or 0.0)
    return analysis.keep_ranges(duration, silent_ranges(path, **kwargs))


# ------------------------------------------------------------------ measuring


def compare(path: str) -> dict:
    """Run both speech maps on the same file and report what they disagree about.

    This is the honest form of "is the model better?". Not a claim in a document:
    the user's own footage, both methods, the numbers side by side, and the time
    each took. Until someone has read this on material that matters, the engine
    stays opt-in.
    """
    info = probe_media(path)
    duration = float(info.get("duration") or 0.0)
    out: dict = {
        "file": Path(path).name,
        "duration": round(duration, 3),
        "hasAudio": bool(info.get("has_audio")),
        "silero": None,
        "silencedetect": None,
        "ready": installed(),
    }
    if not info.get("has_audio") or duration <= 0:
        return out

    began = time.perf_counter()
    old = analysis.detect_silence(path)
    old_seconds = time.perf_counter() - began
    old_speech = analysis.keep_ranges(duration, old)

    out["silencedetect"] = {
        "speechRatio": round(sum(r.duration for r in old_speech) / duration, 4),
        "regions": len(old_speech),
        "seconds": round(old_seconds, 3),
        "first": round(old_speech[0].start, 3) if old_speech else None,
    }

    if installed():
        began = time.perf_counter()
        new_speech = speech_ranges(path)
        new_seconds = time.perf_counter() - began
        out["silero"] = {
            "speechRatio": round(sum(r.duration for r in new_speech) / duration, 4),
            "regions": len(new_speech),
            "seconds": round(new_seconds, 3),
            "first": round(new_speech[0].start, 3) if new_speech else None,
        }
        # How far apart the two are, in seconds of the file: the sum of the
        # symmetric difference. Zero means they agree everywhere.
        out["disagreementRatio"] = round(
            _symmetric_difference(old_speech, new_speech) / duration, 4
        )
    return out


def _covered(ranges: list[analysis.Range], start: float, end: float) -> float:
    return sum(max(0.0, min(r.end, end) - max(r.start, start)) for r in ranges)


def _symmetric_difference(a: list[analysis.Range], b: list[analysis.Range]) -> float:
    """Seconds where exactly one of the two says "speech", sampled finely."""
    end = max([r.end for r in (*a, *b)] or [0.0])
    if end <= 0:
        return 0.0
    step = 0.05
    total = 0.0
    at = 0.0
    while at < end:
        in_a = _covered(a, at, at + step) > step * 0.5
        in_b = _covered(b, at, at + step) > step * 0.5
        if in_a != in_b:
            total += step
        at += step
    return total
