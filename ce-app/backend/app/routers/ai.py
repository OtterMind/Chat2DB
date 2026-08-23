"""Is the local AI actually there, and is it usable?

Two optional engines make the difference between "the app works" and "the app is
clever": Ollama for judgement over text, faster-whisper for speech. Both are
local, both are large, and both are easy to have *almost* installed — a model
never pulled, a service not running, a first call that takes ninety seconds.

This router answers three questions honestly, with numbers rather than a green
tick: is it installed, is it reachable, and how fast is it on *this* machine.
"""
from __future__ import annotations

import asyncio
import importlib.util
import shutil
import subprocess
import time
from pathlib import Path

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.config import settings

router = APIRouter(prefix="/api/ai", tags=["ai"])

OLLAMA_URL = "http://127.0.0.1:11434"
OLLAMA_SITE = "https://ollama.com/download"


# ------------------------------------------------------------------ helpers


def _ollama_state() -> dict:
    """Installed? Running? Which models are pulled?"""
    binary = shutil.which("ollama")
    state: dict = {
        "name": "Ollama",
        "installed": bool(binary),
        "running": False,
        "models": [],
        "path": binary or None,
        "download": OLLAMA_SITE,
        "selected": settings.ollama_model or "llama3",
        "enabled": bool(settings.ollama_enabled),
    }
    try:
        import requests

        response = requests.get(f"{OLLAMA_URL}/api/tags", timeout=1.5)
        if response.ok:
            state["running"] = True
            state["installed"] = True
            state["models"] = [m.get("name", "") for m in response.json().get("models", [])]
    except Exception:  # noqa: BLE001 - not running is a normal answer here
        pass
    return state


def _whisper_state() -> dict:
    """Is faster-whisper importable, and which model will actually be used?

    `selected` used to be the string "base" whatever the machine had. It is now
    the model transcription will really load — the most accurate one already
    downloaded — so the card cannot claim one thing while the engine does
    another.
    """
    available = importlib.util.find_spec("faster_whisper") is not None
    from core.engine import transcribe

    models = transcribe.local_models()
    return {
        "name": "Whisper",
        "installed": available,
        "running": available,
        "models": models,
        "download": None if available else "pip install faster-whisper",
        "selected": transcribe.best_local_model(),
        "enabled": available,
    }


# ------------------------------------------------------------------- routes


@router.get("/status")
def status() -> dict:
    """What is installed right now — checked, not remembered."""
    return {"ollama": _ollama_state(), "whisper": _whisper_state()}


class PullRequest(BaseModel):
    model: str = "llama3"


@router.post("/ollama/pull")
async def pull_model(payload: PullRequest) -> dict:
    """Ask a running Ollama to download a model.

    We never install Ollama itself behind the user's back — that is a several
    hundred megabyte application from another project, and silently installing
    software is not something an editor should do. Pulling a model into an
    Ollama the user already runs is different: they asked for it.
    """
    try:
        import requests
    except ImportError as error:
        raise HTTPException(status_code=501, detail="No HTTP client in this build") from error

    state = _ollama_state()
    if not state["running"]:
        raise HTTPException(
            status_code=409,
            detail=f"Ollama is not running. Install it from {OLLAMA_SITE}, then start it.",
        )

    def _pull() -> dict:
        started = time.monotonic()
        response = requests.post(
            f"{OLLAMA_URL}/api/pull", json={"name": payload.model, "stream": False}, timeout=60 * 60
        )
        response.raise_for_status()
        return {"model": payload.model, "seconds": round(time.monotonic() - started, 1)}

    try:
        return await asyncio.get_running_loop().run_in_executor(None, _pull)
    except Exception as error:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=str(error)) from error


class WhisperRequest(BaseModel):
    size: str = "base"


@router.post("/whisper/download")
async def download_whisper(payload: WhisperRequest) -> dict:
    """Fetch a Whisper model by loading it once — that is what triggers the download."""
    if importlib.util.find_spec("faster_whisper") is None:
        raise HTTPException(
            status_code=409,
            detail="faster-whisper is not part of this build; the packaged app ships with it.",
        )

    def _load() -> dict:
        from faster_whisper import WhisperModel

        started = time.monotonic()
        WhisperModel(payload.size, device="auto", compute_type="int8")
        return {"model": payload.size, "seconds": round(time.monotonic() - started, 1)}

    try:
        return await asyncio.get_running_loop().run_in_executor(None, _load)
    except Exception as error:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=str(error)) from error


class SelectRequest(BaseModel):
    model: str


@router.post("/ollama/select")
def select_model(payload: SelectRequest) -> dict:
    """Remember which pulled model the assistant should talk to."""
    settings.ollama_model = payload.model
    settings.ollama_enabled = True
    try:
        import json

        from app.config import CONFIG_PATH

        existing = {}
        if CONFIG_PATH.exists():
            existing = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        existing.update({"ollama_model": payload.model, "ollama_enabled": True})
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(existing, indent=2), encoding="utf-8")
    except Exception:  # noqa: BLE001 - the choice still applies to this session
        pass
    return {"model": payload.model}


@router.post("/test")
async def test_engines() -> dict:
    """Measure both engines on this machine: does it answer, and how fast.

    A green tick that means "the import worked" is worthless — the number people
    need is seconds. Transcription is timed on three seconds of synthetic speech,
    Ollama on a one-word prompt.
    """
    loop = asyncio.get_running_loop()
    report: dict = {"ollama": {}, "whisper": {}}

    # ---- Ollama ----------------------------------------------------------
    def _ping_ollama() -> dict:
        try:
            import requests
        except ImportError:
            # A trimmed build without the HTTP client: say so, do not crash the
            # whole self-test (this exact case took the endpoint down once).
            return {"ok": False, "detail": "the HTTP client is not part of this build"}

        state = _ollama_state()
        if not state["running"]:
            return {"ok": False, "detail": "not running"}

        # Use a model that is actually pulled. The configured default was
        # "llama3", so on a machine holding qwen2.5 the call came back
        # "404 Not Found" — which reads like a broken URL and is really
        # "that model is not here". Prefer the configured one *if* it exists.
        installed = state["models"]
        configured = settings.ollama_model
        if configured and any(m == configured or m.startswith(f"{configured}:") for m in installed):
            model = configured
        elif installed:
            model = installed[0]
        else:
            return {
                "ok": False,
                "detail": "Ollama is running but has no models. Pull one with the button above.",
            }
        started = time.monotonic()
        try:
            response = requests.post(
                f"{OLLAMA_URL}/api/generate",
                json={"model": model, "prompt": "Reply with the single word: ready", "stream": False},
                timeout=120,
            )
            response.raise_for_status()
            answer = (response.json().get("response") or "").strip()
        except Exception as error:  # noqa: BLE001
            detail = str(error)[:160]
            if "404" in detail:
                detail = f"Ollama has no model called '{model}' — pull it, or pick one of: {', '.join(installed[:4])}"
            return {"ok": False, "detail": detail}
        return {
            "ok": True,
            "model": model,
            "seconds": round(time.monotonic() - started, 1),
            "answer": answer[:60],
        }

    # ---- Whisper ---------------------------------------------------------
    def _ping_whisper() -> dict:
        if importlib.util.find_spec("faster_whisper") is None:
            return {"ok": False, "detail": "faster-whisper is not installed"}
        from core.engine.compose import ffmpeg_binary
        from core.engine.transcribe import transcribe_to_cues

        sample = settings.work_dir / "ai-selftest.wav"
        sample.parent.mkdir(parents=True, exist_ok=True)
        if not sample.exists():
            subprocess.run(
                [
                    ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=220:duration=3", str(sample),
                ],
                check=True,
            )
        started = time.monotonic()
        try:
            result = transcribe_to_cues(str(sample))
        except Exception as error:  # noqa: BLE001
            return {"ok": False, "detail": str(error)[:160]}
        return {
            "ok": True,
            "seconds": round(time.monotonic() - started, 1),
            "cues": len(result.get("cues") or []),
            "language": result.get("language"),
        }

    report["ollama"], report["whisper"] = await asyncio.gather(
        loop.run_in_executor(None, _ping_ollama),
        loop.run_in_executor(None, _ping_whisper),
    )
    return report
