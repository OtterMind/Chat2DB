"""Word-level forced alignment — on-demand, degrade-safe.

faster-whisper already gives us word timings (`word_timestamps=True`), and the
karaoke captions in `subtitles.build_ass` light word by word off them. Those
timings are good, but they drift: a word's edges can be a frame or two off, so a
highlight can light slightly before or after the word is actually spoken. A
forced **aligner** snaps each word's edges to the audio. `whisperX` (BSD-3) is the
standard bridge, and for Persian the Apache-2.0 wav2vec2 aligner
(`jonatasgrosman/wav2vec2-large-xlsr-53-persian`) gives tight Persian edges —
exactly the karaoke case the owner's footage needs.

This module is deliberately thin and defensive, and — unlike `rife.py` — it
**never raises into the caller**. Alignment is a *refinement* of timings we
already have, not a thing that invents data: on every machine without the engine
(or if it fails), `align()` hands back the input words unchanged with a status, so
the karaoke flow keeps working on faster-whisper's timings. It is only exercised
when the user has fetched the engine; otherwise `available()` is False. Nothing
ships in the installer, and the aligner weights are fetched on demand.
"""
from __future__ import annotations

import importlib.util

from core import runtime_packages

#: The bridge package plus the Persian aligner's runtime needs. `torch`/`torchaudio`
#: are the heavy part and are what `engines.ENGINES` marks as `heavy`; the aligner
#: weights themselves are pulled from Hugging Face on first use, not shipped.
PACKAGES = ["whisperx"]

#: Apache-2.0 wav2vec2 aligner for Persian (verify the model card's licence before
#: shipping anything that bundles it; here it is fetched to the user's own cache).
PERSIAN_ALIGNER = "jonatasgrosman/wav2vec2-large-xlsr-53-persian"


class WhisperXNotInstalled(RuntimeError):
    """Raised only by callers that *require* alignment; `align()` never raises it."""


def available() -> bool:
    """Is the whisperX bridge importable on this machine?"""
    return importlib.util.find_spec("whisperx") is not None


def fetch(on_progress=None) -> dict:
    """Fetch the whisperX bridge into the user's runtime dir.

    The Persian aligner *weights* are pulled from Hugging Face by whisperX on
    first `align_words` call into the user's own HF cache — not vendored here.
    """
    return runtime_packages.install(PACKAGES, on_progress=on_progress)


def _to_words(aligned) -> list[dict]:
    """whisperX's aligned word rows → our `{start, end, text}` word shape.

    Kept as its own function so the conversion is testable without whisperX.
    """
    out: list[dict] = []
    for row in getattr(aligned, "word_segments", None) or []:
        start = getattr(row, "start", None)
        end = getattr(row, "end", None)
        text = (getattr(row, "word", "") or "").strip()
        if start is None or end is None or not text:
            continue
        out.append({"start": round(float(start), 3), "end": round(float(end), 3),
                    "text": text})
    return out


def align(audio_path: str, words: list[dict], language: str = "fa") -> dict:
    """Snap `words` to the audio, or hand them back unchanged.

    Returns `{"words": [...], "aligner": <name|None>, "status": ...}`. `status`
    is `"aligned"`, `"no-engine"`, or `"error"`. The karaoke caller only needs
    `words`; `status` lets it say honestly whether alignment ran.
    """
    if not available():
        return {"words": [dict(w) for w in words], "aligner": None,
                "status": "no-engine"}
    try:
        import whisperx  # noqa: PLC0415

        audio = whisperx.load_audio(audio_path)
        segments = [{"start": w["start"], "end": w["end"], "text": w["text"]}
                    for w in words]
        model = whisperx.load_align_model(language_code=language, device="cpu")
        aligned, _ = whisperx.align(segments, model, None, audio, device="cpu",
                                    return_char_alignments=False)
        out = _to_words(aligned)
        if not out:  # alignment produced nothing usable — keep the originals
            return {"words": [dict(w) for w in words], "aligner": None,
                    "status": "empty"}
        return {"words": out, "aligner": PERSIAN_ALIGNER if language == "fa" else None,
                "status": "aligned"}
    except Exception as error:  # noqa: BLE001 — a refinement must never break captions
        return {"words": [dict(w) for w in words], "aligner": None,
                "status": f"error: {error}"}
