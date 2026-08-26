"""Semantic vision embeddings — the "does this frame mean X?" sensor, on demand.

The advisors' biggest single upgrade over energy-only highlights: CLIP-family
embeddings turn a frame and a concept ("ball in air", "product close-up",
"face to camera") into one cosine number. This bridge is deliberately thin:

* `open_clip` (MIT) is the fetched engine; torch-sized, so nothing ships and the
  absence is a named gap, never an error;
* the signal is a **number 0..1 per window**, fed to `_highlights` like any
  other measurement — when absent, the blend renormalises over the senses that
  are alive (the quiet-file discipline), it is never faked as 0.5.

Concepts come from the intent's focus/kind, translated by `concepts_for`, so
the sensor asks about what the user said the video is — not a hard-coded zoo.
"""
from __future__ import annotations

import importlib.util

from core import runtime_packages

PACKAGE = "open-clip-torch"

_CONCEPTS: dict[str, list[str]] = {
    "sport": ["a ball in the air", "an athlete mid jump", "a sports court"],
    "product": ["a product close-up", "hands holding an object"],
    "face": ["a face looking at the camera", "a person smiling"],
    "screen": ["a computer screen recording", "code on a screen"],
    "scenery": ["a wide landscape view", "a mountain horizon"],
    "hands": ["hands working on a task"],
}


class ClipNotInstalled(RuntimeError):
    pass


def available() -> bool:
    return importlib.util.find_spec("open_clip") is not None


def fetch(on_progress=None) -> dict:
    return runtime_packages.install([PACKAGE], on_progress=on_progress)


def concepts_for(intent: dict | None) -> list[str]:
    """The concepts worth asking the sensor, from what the user said."""
    intent = intent or {}
    out: list[str] = []
    for key in ("focus", "kind"):
        out.extend(_CONCEPTS.get(str(intent.get(key, "")), []))
    return out or ["a person", "an object close-up", "a wide view"]


def semantic_score(frame_rgb, concepts: list[str]) -> float | None:
    """Max cosine similarity of one frame against the concepts, 0..1.

    Defensive by contract: no engine → None (the caller renormalises); an
    upstream API mismatch raises a clear error rather than a wrong number,
    because a fake 0.5 wearing a measurement's clothes is the failure mode
    this whole design exists to avoid.
    """
    if not available():
        return None
    try:
        import open_clip  # noqa: PLC0415
        import torch  # noqa: PLC0415

        model, _, preprocess = open_clip.create_model_and_transforms(
            "MobileCLIP-S1", pretrained="datacomp1b")
        tokenizer = open_clip.get_tokenizer("MobileCLIP-S1")
        image = preprocess(frame_rgb).unsqueeze(0)
        text = tokenizer(concepts)
        with torch.no_grad():
            feats = model.encode_image(image)
            tfeat = model.encode_text(text)
            feats = feats / feats.norm(dim=-1, keepdim=True)
            tfeat = tfeat / tfeat.norm(dim=-1, keepdim=True)
        return float((feats @ tfeat.T).max().clamp(0.0, 1.0))
    except ClipNotInstalled:
        raise
    except Exception as error:  # noqa: BLE001 — upstream mismatch must say so plainly
        raise ClipNotInstalled(f"open_clip unusable here: {error}") from error
