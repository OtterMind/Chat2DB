"""The graphics card, used where it helps and reported honestly.

The owner's instruction: *"I have a GTX 1650. Do not limit the GPU anywhere —
use it wherever it is needed."* Fair, and until now we did the opposite in three
places:

* the compositor decided NVENC was available by **grepping FFmpeg's encoder
  list**, which lists `h264_nvenc` on machines whose driver cannot run it, so
  the choice was a guess in both directions;
* nothing ever used the card for **decoding**, which is most of the work in
  building a proxy or scanning a file;
* `/api/system/doctor` returned `"cuda": {"available": false}` as a **hard-coded
  literal**, so the diagnostics screen told a user with a working card that they
  had none.

Everything here is a *probe*, never a guess: the encoder is asked to encode one
real frame, the decoder is asked to decode one real file, and `nvidia-smi` is
read only for the label. Results are cached for the life of the process, because
probing costs about a second and the answer cannot change while the app runs.

This module never raises: a machine with no card is the normal case, and it must
come back as "no", not as an error.
"""
from __future__ import annotations

import shutil
import subprocess
import time
from dataclasses import dataclass, field
from functools import lru_cache

from core.engine.compose import ffmpeg_binary

#: How long a probe may take before we call it a failure.
PROBE_TIMEOUT = 25


@dataclass
class Capabilities:
    """What this machine's graphics card can actually do for us."""

    name: str | None = None
    memory_mb: int | None = None
    driver: str | None = None
    nvenc: bool = False
    nvdec: bool = False
    whisper_device: str = "cpu"
    whisper_detail: str = ""
    notes: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "name": self.name,
            "memoryMb": self.memory_mb,
            "driver": self.driver,
            "encode": self.nvenc,
            "decode": self.nvdec,
            "whisperDevice": self.whisper_device,
            "whisperDetail": self.whisper_detail,
            "notes": self.notes,
            "used": [
                *(["export encoding (h264_nvenc)"] if self.nvenc else []),
                *(["editing proxies"] if self.nvenc else []),
                *(["decoding while scanning and building proxies"] if self.nvdec else []),
                *(["speech recognition"] if self.whisper_device == "cuda" else []),
            ],
        }


def _run(args: list[str], timeout: int = PROBE_TIMEOUT) -> subprocess.CompletedProcess:
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)


@lru_cache(maxsize=1)
def nvidia_smi() -> dict:
    """The card's own name, memory and driver — for the label, not for decisions."""
    exe = shutil.which("nvidia-smi")
    if not exe:
        return {}
    try:
        out = _run([exe, "--query-gpu=name,memory.total,driver_version",
                    "--format=csv,noheader,nounits"], timeout=10)
        line = (out.stdout or "").strip().splitlines()[0]
        name, memory, driver = (part.strip() for part in line.split(","))
        return {"name": name, "memory_mb": int(float(memory)), "driver": driver}
    except Exception:  # noqa: BLE001 — no card is a normal answer
        return {}


@lru_cache(maxsize=1)
def can_encode() -> bool:
    """Encode one real frame with NVENC.

    `ffmpeg -encoders | grep nvenc` is not evidence: the encoder is compiled in
    and listed on machines whose driver refuses it at runtime. One frame is.
    """
    try:
        out = _run([
            ffmpeg_binary(), "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "color=c=black:s=256x256:d=1",
            "-c:v", "h264_nvenc", "-frames:v", "1", "-f", "null", "-",
        ])
        return out.returncode == 0
    except Exception:  # noqa: BLE001
        return False


@lru_cache(maxsize=1)
def can_decode() -> bool:
    """Decode one real file through CUDA."""
    try:
        made = _run([
            ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25:duration=1",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-f", "mp4", "/tmp/ce-nvdec-probe.mp4" if shutil.os.name != "nt" else
            str(shutil.os.path.join(shutil.os.environ.get("TEMP", "."), "ce-nvdec-probe.mp4")),
        ])
        if made.returncode != 0:
            return False
        path = "/tmp/ce-nvdec-probe.mp4" if shutil.os.name != "nt" else \
            str(shutil.os.path.join(shutil.os.environ.get("TEMP", "."), "ce-nvdec-probe.mp4"))
        out = _run([
            ffmpeg_binary(), "-hide_banner", "-loglevel", "error",
            "-hwaccel", "cuda", "-i", path, "-frames:v", "5", "-f", "null", "-",
        ])
        return out.returncode == 0
    except Exception:  # noqa: BLE001
        return False


def whisper_status() -> tuple[str, str]:
    """Which device speech recognition will really load on, and why.

    A GTX 1650 has CUDA; faster-whisper still needs cuBLAS and cuDNN next to it,
    and their absence is the `cublas64_12.dll is not found` a user reported in
    0.5.3. So this reports the *reason*, not just a yes or no — the Settings
    card offers the download when the card is there and the libraries are not.
    """
    try:
        import ctranslate2  # type: ignore
    except Exception:  # noqa: BLE001
        return "cpu", "faster-whisper is not installed"

    try:
        count = ctranslate2.get_cuda_device_count()
    except Exception as error:  # noqa: BLE001
        return "cpu", f"CUDA could not be queried ({error})"

    if count <= 0:
        return "cpu", "no CUDA device is visible to CTranslate2"

    # The device exists; the libraries may still be missing, and the only honest
    # way to know is to load something.
    try:
        from faster_whisper import WhisperModel  # type: ignore

        from core.engine.transcribe import best_local_model

        WhisperModel(best_local_model(), device="cuda", compute_type="float16")
        return "cuda", "float16 on the GPU"
    except Exception as error:  # noqa: BLE001
        text = str(error)
        if "cublas" in text.lower() or "cudnn" in text.lower():
            return "cpu", "the CUDA libraries (cuBLAS/cuDNN) are missing — Settings can fetch them"
        return "cpu", text[:160]


def capabilities(deep: bool = False) -> Capabilities:
    """Everything the app knows about this machine's card.

    `deep` also loads a Whisper model to see whether the GPU path really works,
    which takes seconds — so the quick call is the default and the Settings card
    asks for the deep one when the user presses "check".
    """
    card = nvidia_smi()
    caps = Capabilities(
        name=card.get("name"),
        memory_mb=card.get("memory_mb"),
        driver=card.get("driver"),
        nvenc=can_encode(),
        nvdec=can_decode(),
    )
    if deep:
        caps.whisper_device, caps.whisper_detail = whisper_status()

    if not card:
        caps.notes.append("No NVIDIA card detected — everything runs on the processor.")
    if card and not caps.nvenc:
        caps.notes.append(
            "The card is there but FFmpeg could not encode with it; the driver may be older "
            "than this build of FFmpeg expects."
        )
    if caps.memory_mb and caps.memory_mb < 6000:
        caps.notes.append(
            f"{caps.memory_mb} MB of video memory: a 7B language model at q4 needs about "
            "4.4 GB and will spill into system memory. A 3B model runs entirely on the card."
        )
    return caps


# ------------------------------------------------------------------ arguments


def decode_args() -> list[str]:
    """FFmpeg arguments that put decoding on the card, when it can take it.

    These go *before* `-i`. Decoding is most of the work in building a proxy or
    scanning a long file, and it was the largest thing we were leaving on the
    table.
    """
    return ["-hwaccel", "cuda"] if can_decode() else []


def encode_args(quality: dict | None = None) -> list[str]:
    """The encoder settings for this machine — NVENC when it is real, x264 otherwise."""
    quality = quality or {}
    if can_encode():
        return [
            "-c:v", "h264_nvenc",
            "-preset", str(quality.get("nvenc_preset", "p5")),
            "-rc", "vbr",
            "-cq", str(quality.get("nvenc_cq", 23)),
            "-b:v", "0",
        ]
    return [
        "-c:v", "libx264",
        "-preset", str(quality.get("preset", "veryfast")),
        "-crf", str(quality.get("crf", 21)),
    ]


# ----------------------------------------------------------------- benchmark


def benchmark(seconds: int = 5, width: int = 1920, height: int = 1080) -> dict:
    """Encode the same clip both ways on *this* machine and report the times.

    A claim about a graphics card that is not measured on the machine it runs on
    is a brochure. This is the number the Settings card shows.
    """
    source = ["-f", "lavfi", "-i", f"testsrc2=size={width}x{height}:rate=30:duration={seconds}"]
    result: dict = {"seconds": seconds, "resolution": f"{width}x{height}"}

    started = time.time()
    cpu = _run([ffmpeg_binary(), "-hide_banner", "-loglevel", "error", *source,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
                "-f", "null", "-"], timeout=300)
    result["cpu"] = round(time.time() - started, 2) if cpu.returncode == 0 else None

    if can_encode():
        started = time.time()
        gpu = _run([ffmpeg_binary(), "-hide_banner", "-loglevel", "error", *source,
                    "-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "23", "-b:v", "0",
                    "-f", "null", "-"], timeout=300)
        result["gpu"] = round(time.time() - started, 2) if gpu.returncode == 0 else None
    else:
        result["gpu"] = None

    if result.get("cpu") and result.get("gpu"):
        result["speedup"] = round(result["cpu"] / result["gpu"], 2)
    return result
