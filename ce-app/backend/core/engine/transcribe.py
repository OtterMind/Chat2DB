"""Speech to text, and the caption cues built from it.

`faster-whisper` is an optional heavyweight: the module imports it lazily and
reports its absence as a normal condition, so an installation without the model
still runs the editor — captions are simply unavailable until it is present.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

_MODEL: Any = None
_MODEL_NAME = ""

#: Best first. Whisper accuracy climbs steeply from `base` to `small`, and the
#: default used to be `base` for no reason other than that it is the smallest.
#: We never download one here — we use the best model the machine already has.
MODEL_PREFERENCE = ("large-v3", "large-v2", "medium", "small", "base", "tiny")


def local_models() -> list[str]:
    """Whisper models already on this machine, from the Hugging Face cache."""
    from pathlib import Path as _Path

    cache = _Path.home() / ".cache" / "huggingface" / "hub"
    if not cache.exists():
        return []
    return sorted(
        folder.name.replace("models--Systran--faster-whisper-", "")
        for folder in cache.glob("models--Systran--faster-whisper-*")
    )


def best_local_model(default: str = "base") -> str:
    """The most accurate model already downloaded, or the default.

    Nothing is fetched: if the user has `base` and `small`, captions are
    transcribed with `small`, because it is there and it is better.
    """
    present = set(local_models())
    for name in MODEL_PREFERENCE:
        if name in present:
            return name
    return default


class TranscriberUnavailable(RuntimeError):
    """Raised when speech recognition cannot run on this installation."""


def availability() -> dict:
    try:
        import faster_whisper  # noqa: F401
    except Exception as exc:  # noqa: BLE001
        return {"available": False, "reason": str(exc)}
    return {"available": True, "model": _MODEL_NAME or "not loaded"}


def _load(model_size: str | None = None):
    global _MODEL, _MODEL_NAME
    if _MODEL is not None:
        return _MODEL
    model_size = model_size or best_local_model()
    try:
        from faster_whisper import WhisperModel
    except Exception as exc:  # noqa: BLE001
        raise TranscriberUnavailable(
            "Speech recognition is not installed in this build (faster-whisper missing)"
        ) from exc
    # device="auto" reaches for CUDA when an NVIDIA driver is present, and then
    # fails at load time on a machine without the CUDA runtime:
    #   "Library cublas64_12.dll is not found or cannot be loaded"
    # That is a normal Windows machine with a graphics card, not a broken one, so
    # the CPU path is a fallback rather than an error. Reported by a user, fixed
    # here, and pinned down by tests/test_transcribe.py.
    # float16 on a real CUDA runtime is both faster *and* more accurate than
    # int8; int8 is the fallback, not the goal. Each rung is tried in order and
    # a machine without CUDA simply lands on the last one.
    attempts: list[tuple[str, str]] = [
        ("cuda", "float16"),
        ("auto", "int8"),
        ("cpu", "int8"),
    ]
    last_error: Exception | None = None
    for device, compute in attempts:
        try:
            _MODEL = WhisperModel(model_size, device=device, compute_type=compute)
            _MODEL_NAME = f"{model_size} ({device})"
            return _MODEL
        except Exception as exc:  # noqa: BLE001 - try the next device
            last_error = exc
    raise TranscriberUnavailable(
        f"Speech recognition could not start on this machine: {last_error}"
    ) from last_error


def group_words(words: list[dict], max_chars: int = 42, max_gap: float = 0.8) -> list[dict]:
    """Pack word timings into caption-sized lines.

    Splitting on length *and* on pauses keeps lines readable and stops a caption
    from spanning a silence, which is what makes automatic captions feel wrong.
    """
    cues: list[dict] = []
    current: list[dict] = []

    def flush() -> None:
        if not current:
            return
        cues.append({
            "start": current[0]["start"],
            "end": current[-1]["end"],
            "text": " ".join(w["text"].strip() for w in current).strip(),
            "words": [dict(w) for w in current],
        })
        current.clear()

    for word in words:
        if current:
            pending = sum(len(w["text"]) + 1 for w in current)
            gap = word["start"] - current[-1]["end"]
            if pending + len(word["text"]) > max_chars or gap > max_gap:
                flush()
        current.append(word)
    flush()
    return cues


def transcribe_to_cues(path: str, *, language: str | None = None, max_chars: int = 42,
                       align: bool = False) -> dict:
    model = _load()
    segments, info = model.transcribe(
        path, language=language, word_timestamps=True, vad_filter=True
    )

    words: list[dict] = []
    plain: list[str] = []
    for segment in segments:
        plain.append(segment.text.strip())
        for word in getattr(segment, "words", None) or []:
            words.append({
                "start": round(float(word.start), 3),
                "end": round(float(word.end), 3),
                "text": word.word.strip(),
            })

    detected = getattr(info, "language", language) or "unknown"

    # Optional refinement: snap word edges to the audio with whisperX when the
    # engine is fetched, so karaoke highlights fire on the word, not near it.
    # Never raises — on any machine without it the timings above are used as-is.
    alignment = "off"
    if align and words:
        from core.engine import whisperx_align  # noqa: PLC0415

        refined = whisperx_align.align(path, words, language=str(detected))
        if refined.get("words"):
            words = refined["words"]
        alignment = refined.get("status", "off")

    return {
        "language": detected,
        "duration": round(float(getattr(info, "duration", 0.0)), 3),
        "text": " ".join(plain).strip(),
        "words": words,
        "cues": group_words(words, max_chars=max_chars),
        "alignment": alignment,
    }


def save_transcription(result: dict, destination: Path) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    return destination


# Backwards-compatible surface used by the job pipeline.
class TranscribeEngine:
    @staticmethod
    def transcribe(audio_path: Path) -> dict:
        return transcribe_to_cues(str(audio_path))

    @staticmethod
    def save_transcription(result: dict, destination: Path) -> Path:
        return save_transcription(result, destination)
