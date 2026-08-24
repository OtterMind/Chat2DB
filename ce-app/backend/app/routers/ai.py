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

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.config import settings

from core.tasks import tasks

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


#: The models this app can actually make use of, with what each one is for and
#: what it costs. Curated on purpose: the Ollama library has hundreds of tags and
#: a list of hundreds is not a recommendation. Sizes are the q4 download.
#:
#: `vision` matters here — a model that can see frames is the difference between
#: an assistant that reasons about numbers and one that has looked at the video.
CATALOGUE: tuple[dict, ...] = (
    {"name": "qwen2.5vl:3b", "job": "vision", "gb": 3.2, "vramGb": 4,
     "why": "Looks at the frames. Fits entirely on a 4 GB card."},
    {"name": "qwen2.5vl:7b", "job": "vision", "gb": 6.0, "vramGb": 8,
     "why": "The same, sharper, for an 8 GB card or larger."},
    {"name": "moondream", "job": "vision", "gb": 1.7, "vramGb": 2,
     "why": "Tiny vision model — describes a frame on almost anything."},
    {"name": "llama3.2-vision:11b", "job": "vision", "gb": 7.9, "vramGb": 12,
     "why": "Strongest local vision here, for a 12 GB card."},
    {"name": "qwen2.5:3b-instruct", "job": "planning", "gb": 1.9, "vramGb": 4,
     "why": "Plans edits on a 4 GB card without spilling into system memory."},
    {"name": "qwen2.5:7b-instruct-q4_0", "job": "planning", "gb": 4.4, "vramGb": 6,
     "why": "Better planning and better Persian; needs about 4.4 GB."},
    {"name": "gemma2:9b", "job": "planning", "gb": 5.4, "vramGb": 8,
     "why": "An alternative planner if you prefer its writing."},
)


@router.get("/models")
def models() -> dict:
    """What is worth pulling on *this* machine, and what is already here.

    The recommendation is made against the card's memory rather than a fixed
    list, so a 4 GB laptop and a 24 GB desktop are told different things.
    """
    from core.engine import gpu

    caps = gpu.capabilities()
    vram = (caps.memory_mb or 0) / 1024
    installed = {name.split(":")[0]: name for name in (_ollama_state().get("models") or [])}
    present = set(_ollama_state().get("models") or [])

    out = []
    for entry in CATALOGUE:
        fits = vram >= entry["vramGb"] if vram else None
        out.append({
            **entry,
            "installed": entry["name"] in present or entry["name"].split(":")[0] in installed,
            "fits": fits,
            "note": (
                "runs entirely on your card" if fits
                else "will spill into system memory and run slower" if fits is False
                else "no card detected — it will run on the processor"
            ),
        })
    return {"vramGb": round(vram, 1) if vram else None, "models": out}


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


# --------------------------------------------------------------------------- #
# The CUDA libraries faster-whisper needs, fetched only if the card is there.
# --------------------------------------------------------------------------- #
#
# A GTX 1650 has CUDA; `faster-whisper` still needs cuBLAS and cuDNN beside it,
# and their absence is the `Library cublas64_12.dll is not found` a user
# reported in 0.5.3. They are 553 MB and 737 MB of Windows wheels — far too much
# to ship to every user, and exactly the right thing to offer to a user who has
# an NVIDIA card and wants transcription on it.


CUDA_PACKAGES = ("nvidia-cublas-cu12", "nvidia-cudnn-cu12")


@router.get("/cuda/status")
def cuda_status() -> dict:
    """Is the card usable for speech recognition, and if not, why not?"""
    from core.engine import gpu

    caps = gpu.capabilities()
    device, detail = gpu.whisper_status()
    def _present(module: str) -> bool:
        # find_spec raises ModuleNotFoundError for a *parent* that is absent,
        # which is the normal case on a machine that never had CUDA.
        try:
            return importlib.util.find_spec(module) is not None
        except ModuleNotFoundError:
            return False

    installed = all(_present(name) for name in ("nvidia.cublas", "nvidia.cudnn"))
    return {
        "card": caps.name,
        "memoryMb": caps.memory_mb,
        "device": device,
        "detail": detail,
        "librariesInstalled": installed,
        "packages": list(CUDA_PACKAGES),
        "downloadMb": 1290,
        "canInstall": bool(caps.name) and device != "cuda",
    }


@router.post("/cuda/install")
def cuda_install() -> dict:
    """Fetch cuBLAS and cuDNN — as a task, with a bar, and only once.

    Two things this gets right that the first version did not: it reports
    progress instead of blocking for twenty minutes in silence, and it installs
    into `~/CuttingEdge/runtime/py`, which the installer never replaces — so the
    1.3 GB is downloaded once and survives every future update.
    """
    from core import runtime_packages
    from core.engine import gpu

    caps = gpu.capabilities()
    if not caps.name:
        raise HTTPException(
            status_code=409,
            detail="No NVIDIA card was found, so these libraries would do nothing here.",
        )

    def work(reporter) -> dict:
        result = runtime_packages.install(
            list(CUDA_PACKAGES),
            on_progress=lambda stage, fraction, label="": reporter.stage(stage, fraction, label),
        )
        device, detail = gpu.whisper_status()
        return {**result, "device": device, "detail": detail}

    return tasks.start("cuda:install", work).as_dict()


@router.post("/ollama/pull/start")
def ollama_pull_start(payload: PullRequest) -> dict:
    """Pull a model with a real progress bar.

    Ollama's own `/api/pull` streams `completed`/`total` byte counts, so the bar
    is the download rather than a guess. The model lands in Ollama's own store,
    which is not ours and not inside our installation folder — so it also
    survives every update, and a re-pull resumes the layers it already has.
    """
    def work(reporter) -> dict:
        import json as _json

        import requests

        reporter.stage("connect", 0.02, f"Asking Ollama for {payload.model}")
        with requests.post(
            "http://127.0.0.1:11434/api/pull",
            json={"model": payload.model, "stream": True},
            stream=True, timeout=(10, 3600),
        ) as response:
            if response.status_code != 200:
                raise RuntimeError(f"Ollama answered {response.status_code}: {response.text[:200]}")
            last = ""
            for line in response.iter_lines():
                reporter.check()
                if not line:
                    continue
                try:
                    event = _json.loads(line)
                except ValueError:
                    continue
                status = str(event.get("status", ""))
                total = float(event.get("total") or 0)
                done = float(event.get("completed") or 0)
                if total > 0:
                    fraction = max(0.02, min(0.99, done / total))
                    label = f"{status} · {done / 1e9:.1f} / {total / 1e9:.1f} GB"
                else:
                    fraction, label = 0.02, status or last
                last = status
                reporter.stage("download", fraction, label)
                if status == "success":
                    break
        reporter.stage("done", 1.0, f"{payload.model} is ready")
        return {"model": payload.model}

    return tasks.start("ollama:pull", work).as_dict()


@router.post("/whisper/download/start")
def whisper_download_start(payload: WhisperRequest) -> dict:
    """Fetch a speech model with a bar, into the Hugging Face cache.

    That cache is in the user's profile, not in our installation folder, so it
    survives updates and a half-finished download resumes rather than restarting.
    """
    def work(reporter) -> dict:
        started = time.monotonic()
        repo = f"Systran/faster-whisper-{payload.size}"
        try:
            from huggingface_hub import snapshot_download  # ships with faster-whisper

            seen: dict[str, tuple[float, float]] = {}

            class Bar:
                """A tqdm stand-in: the hub calls it, we turn it into a stage."""

                def __init__(self, *_args, total=0, desc="", **_kwargs):
                    self.total = float(total or 0)
                    self.desc = str(desc or "")
                    self.n = 0.0
                    seen[self.desc] = (0.0, self.total)

                def update(self, amount=1):
                    self.n += float(amount or 0)
                    seen[self.desc] = (self.n, self.total)
                    done = sum(a for a, _ in seen.values())
                    total = sum(b for _, b in seen.values()) or 1.0
                    reporter.stage(
                        "download",
                        max(0.02, min(0.99, done / total)),
                        f"{done / 1e6:.0f} / {total / 1e6:.0f} MB",
                    )

                def close(self):
                    return None

                def __enter__(self):
                    return self

                def __exit__(self, *_exc):
                    return False

                def set_description(self, text=""):
                    self.desc = str(text)

                def refresh(self):
                    return None

            reporter.stage("connect", 0.02, f"Fetching {repo}")
            snapshot_download(repo_id=repo, tqdm_class=Bar)
        except Exception:  # noqa: BLE001 — fall back to the old way, which works
            reporter.stage("download", 0.1, "Downloading (no progress available)")
            from faster_whisper import WhisperModel

            WhisperModel(payload.size, device="auto", compute_type="int8")

        reporter.stage("done", 1.0, f"{payload.size} is ready")
        return {"model": payload.size, "seconds": round(time.monotonic() - started, 1)}

    return tasks.start("whisper:download", work).as_dict()
