"""A model that has seen frames — the missing sense of the highlight scorer.

Every signal the scorer has today is about *energy and shape*: how loud the
audio is, how much the picture moves, where a cut falls. None of them know what
is in the frame. A face turning to the camera, a product held up, a goal being
scored — the moments a person keeps — are invisible to all of it. That is the
gap the user reported as "the highlight detection is weak", and it is the one a
vision model closes.

This module is deliberately **not a dependency**: it is a bridge to a model the
user already runs. The models (qwen2.5-vl, moondream, llama3.2-vision) live in
the user's own Ollama, listed in the Settings catalogue (§4.62) with the memory
each needs, so a 4 GB laptop is never promised an 11 B model. Nothing is bundled,
nothing is installed, and without a running vision model the scorer behaves
exactly as it did before — a boost that is absent is not a regression.

**No verdict is claimed here.** Judging whether the model's "this frame is
interesting" agrees with a human needs the user's own footage and their own
model, so `preview()` runs the question on a file the user chooses and shows the
scores in the open, and the blend weight stays small and off by default. The
machinery is measured for what it can be: frames extracted, a well-formed
request, a graceful answer when no model is listening.
"""
from __future__ import annotations

import base64
import subprocess
import tempfile
from pathlib import Path

from app.config import settings
from core.engine.compose import ffmpeg_binary, probe_media

OLLAMA_URL = "http://127.0.0.1:11434"

#: The models the Settings catalogue already knows are vision models. Only these
#: are asked to look at frames; a text model handed a picture returns noise.
VISION_MODELS = ("qwen2.5vl", "moondream", "llama3.2-vision", "llava")

#: The most a frame may move a candidate's score. Kept deliberately small: a
#: model's opinion is one vote among measured ones, never a veto (§4.45 — the
#: rule plan must not be made worse by a model).
MAX_WEIGHT = 0.3


def _requests():
    try:
        import requests  # noqa: PLC0415

        return requests
    except Exception:  # noqa: BLE001
        return None


def running() -> bool:
    requests = _requests()
    if requests is None:
        return False
    try:
        return requests.get(f"{OLLAMA_URL}/api/tags", timeout=1.5).ok
    except Exception:  # noqa: BLE001 — not running is a normal answer
        return False


def pulled_models() -> list[str]:
    requests = _requests()
    if requests is None:
        return []
    try:
        response = requests.get(f"{OLLAMA_URL}/api/tags", timeout=1.5)
        if not response.ok:
            return []
        return [str(m.get("name", "")) for m in response.json().get("models", [])]
    except Exception:  # noqa: BLE001
        return []


def _vision_model_name(pulled: list[str]) -> str | None:
    """The first pulled model that is a vision model, preferring the smallest."""
    for prefix in VISION_MODELS:
        for name in pulled:
            if name.startswith(prefix):
                return name
    return None


def chosen_model() -> str | None:
    return _vision_model_name(pulled_models())


def available() -> bool:
    """A vision model is pulled *and* Ollama is answering."""
    if not (settings.vision_enabled if hasattr(settings, "vision_enabled") else True):
        return False
    return running() and chosen_model() is not None


def status() -> dict:
    pulled = pulled_models()
    return {
        "enabled": bool(getattr(settings, "vision_enabled", False)),
        "running": running(),
        "visionPulled": chosen_model(),
        "pulled": pulled,
        "candidates": list(VISION_MODELS),
        "ready": available(),
    }


# --------------------------------------------------------------------- frames


def extract_frames(path: str, times: list[float], *, size: int = 320) -> list[dict]:
    """Small JPEGs at the given times, base64-encoded for the chat request.

    Small on purpose: the model is judging "is anything interesting here", not
    reading a barcode, and a 320 px frame keeps the request light enough to send
    a dozen at a time over localhost.
    """
    out: list[dict] = []
    with tempfile.TemporaryDirectory(prefix="ce-vision-") as tmp:
        for at in times:
            frame = Path(tmp) / f"f{int(at * 1000)}.jpg"
            run = subprocess.run(
                [ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
                 "-ss", f"{max(0.0, at):.3f}", "-i", path,
                 "-vf", f"scale={size}:-1", "-frames:v", "1", "-q:v", "4", str(frame)],
                capture_output=True,
            )
            if run.returncode == 0 and frame.exists() and frame.stat().st_size > 0:
                out.append({
                    "time": round(at, 2),
                    "image": base64.b64encode(frame.read_bytes()).decode("ascii"),
                })
    return out


# -------------------------------------------------------------------- scoring

_PROMPT = (
    "You are judging which moments of a video are worth keeping in a short edit. "
    "Each attached image is one moment, in order. For each, in one word of "
    "reasoning at most, rate how visually interesting or important it is from 0 "
    "to 1 (a face or a clear subject high, a blank wall low). "
    'Answer JSON: {"scores": [n, n, ...]} with one number per image.'
)


def score_moments(path: str, times: list[float], model: str | None = None) -> dict[float, float] | None:
    """Ask a vision model how interesting each moment looks. None if it cannot."""
    requests = _requests()
    if requests is None:
        return None
    model = model or chosen_model()
    if model is None or not running():
        return None

    frames = extract_frames(path, times)
    if not frames:
        return None

    try:
        response = requests.post(
            f"{OLLAMA_URL}/api/chat",
            json={
                "model": model,
                "messages": [
                    {"role": "user", "content": _PROMPT,
                     "images": [f["image"] for f in frames]}
                ],
                "stream": False,
                "format": "json",
            },
            timeout=120,
        )
        text = response.json().get("message", {}).get("content", "")
    except Exception:  # noqa: BLE001 — a model that refuses is an absent model
        return None

    scores = _parse_scores(text, len(frames))
    if scores is None:
        return None
    return {frames[i]["time"]: scores[i] for i in range(len(frames))}


def _parse_scores(text: str, expected: int) -> list[float] | None:
    import json  # noqa: PLC0415
    import re  # noqa: PLC0415

    match = re.search(r"\{.*\}", text, re.S)
    if not match:
        return None
    try:
        data = json.loads(match.group(0))
    except json.JSONDecodeError:
        return None
    raw = data.get("scores") if isinstance(data, dict) else None
    if not isinstance(raw, list):
        return None
    values = []
    for item in raw[:expected]:
        try:
            values.append(max(0.0, min(1.0, float(item))))
        except (TypeError, ValueError):
            continue
    return values if len(values) == expected else None


def preview(path: str, *, count: int = 6) -> dict:
    """The honest measurement: a file the user chooses, scores in the open."""
    duration = float(probe_media(path).get("duration") or 0.0)
    if duration <= 0:
        return {"duration": 0.0, "ready": available(), "scores": None, "model": chosen_model()}
    times = [round(duration * (i + 0.5) / count, 2) for i in range(count)]
    return {
        "duration": round(duration, 2),
        "ready": available(),
        "model": chosen_model(),
        "scores": score_moments(path, times) if available() else None,
    }
