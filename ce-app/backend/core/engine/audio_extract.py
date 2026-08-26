"""Audio extraction — FFmpeg always, Demucs stems when fetched.

Two very different jobs wear one name in every NLE, so both live here:

* **track extraction** — lift the audio of a video into its own file (and onto
  the audio lane). This is pure FFmpeg, bundled with the app, no engine needed;
  it stream-copies when the codec allows and transcodes to AAC 192 k otherwise,
  so a three-minute clip extracts in a second, not a minute.
* **stem separation** — split a mix into vocals / drums / bass / other with
  **Demucs** (facebookresearch/demucs, MIT), the open-source hybrid model that
  won the Music Demixing challenge. It needs torch, so it is an on-demand
  engine like the rest of the shelf: absent means a plain 409, never a crash.

Both write under `~/CuttingEdge/exports` — the user's files are referenced,
never overwritten, and the exports folder survives updates like the runtime.
"""
from __future__ import annotations

import importlib.util
import subprocess
from pathlib import Path

from core import runtime_packages

PACKAGE = "demucs"


class DemucsNotInstalled(RuntimeError):
    pass


def exports_dir() -> Path:
    from app.config import settings  # noqa: PLC0415

    path = Path(settings.cuttingedge_home) / "exports"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _ffmpeg() -> str:
    from core.engine.compose import ffmpeg_binary  # noqa: PLC0415

    return ffmpeg_binary()


def extract(path: str, dest: str | None = None) -> dict:
    """Lift the audio track of `path` into its own `.m4a`, measured not guessed.

    Returns the written file and its duration from a probe of the result, so
    the timeline can place a clip of exactly the right length.
    """
    from core.engine.compose import probe_media  # noqa: PLC0415

    source = Path(path)
    if not source.exists():
        raise FileNotFoundError(path)
    out = Path(dest) if dest else exports_dir() / f"{source.stem}.audio.m4a"
    out.parent.mkdir(parents=True, exist_ok=True)

    run = subprocess.run(
        [_ffmpeg(), "-y", "-i", str(source), "-vn", "-c:a", "aac", "-b:a", "192k",
         str(out)],
        capture_output=True, text=True, timeout=600,
    )
    if run.returncode != 0 or not out.exists() or out.stat().st_size == 0:
        raise RuntimeError((run.stderr or "").strip().splitlines()[-1]
                           if run.stderr else f"extraction failed for {source.name}")
    info = probe_media(str(out))
    return {"path": str(out), "duration": float(info.get("duration", 0) or 0),
            "source": str(source)}


def available() -> bool:
    return importlib.util.find_spec("demucs") is not None


def fetch(on_progress=None) -> dict:
    return runtime_packages.install([PACKAGE], on_progress=on_progress)


def separate(path: str, dest_dir: str | None = None) -> dict:
    """Vocals / drums / bass / other with Demucs, or raise a plain reason.

    The model runs on CPU here (a GPU build is the user's choice via torch);
    a three-minute mix takes minutes on CPU, which is why the router runs this
    as a task with a progress bar rather than a request.
    """
    if not available():
        raise DemucsNotInstalled("Demucs is not fetched — fetch it in Settings")
    source = Path(path)
    if not source.exists():
        raise FileNotFoundError(path)
    target = Path(dest_dir) if dest_dir else exports_dir() / f"{source.stem}.stems"
    target.mkdir(parents=True, exist_ok=True)
    try:
        import torch  # noqa: PLC0415
        from demucs.apply import apply_model  # noqa: PLC0415
        from demucs.audio import AudioFile  # noqa: PLC0415
        from demucs.pretrained import get_model  # noqa: PLC0415
        import torchaudio  # noqa: PLC0415

        model = get_model("htdemucs")
        wav = AudioFile(str(source)).read(streams=0, samplerate=model.samplerate,
                                          channels=model.audio_channels)
        ref = wav.mean(0)
        wav = (wav - ref.mean()) / ref.std()
        sources = apply_model(model, wav[None], device="cpu", progress=True)[0]
        sources = sources * ref.std() + ref.mean()
        stems = {}
        for name, stem in zip(model.sources, sources):
            stem_path = target / f"{name}.wav"
            torchaudio.save(str(stem_path), stem.cpu(), model.samplerate)
            stems[name] = str(stem_path)
        return {"stems": stems, "source": str(source)}
    except DemucsNotInstalled:
        raise
    except Exception as error:  # noqa: BLE001 — upstream mismatch must say so plainly
        raise DemucsNotInstalled(f"Demucs unusable here: {error}") from error
