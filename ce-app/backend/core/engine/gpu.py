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


#: Every hardware encoder worth trying, best first.
#:
#: The order is quality-per-effort on the machines people actually have: NVIDIA
#: NVENC, then Intel Quick Sync, then AMD AMF, then VAAPI on Linux. Nothing here
#: is specific to one card — a machine with an Arc, a Radeon or a 4090 takes the
#: first line that answers, and a machine with none falls through to x264.
ENCODERS: tuple[tuple[str, str, str], ...] = (
    ("h264_nvenc", "NVIDIA", "H.264"),
    ("hevc_nvenc", "NVIDIA", "H.265"),
    ("av1_nvenc", "NVIDIA", "AV1"),
    ("h264_qsv", "Intel Quick Sync", "H.264"),
    ("hevc_qsv", "Intel Quick Sync", "H.265"),
    ("h264_amf", "AMD", "H.264"),
    ("hevc_amf", "AMD", "H.265"),
    ("h264_vaapi", "VAAPI", "H.264"),
)

#: Decoders, same idea.
DECODERS: tuple[str, ...] = ("cuda", "qsv", "d3d11va", "dxva2", "vaapi", "videotoolbox")


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
    #: The encoder and decoder actually chosen for work on this machine.
    encoder: str = "libx264 (processor)"
    decoder: str = "processor"
    #: Every hardware encoder that was tried, and why it did or did not work.
    encoders: list[dict] = field(default_factory=list)
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
            "encoder": self.encoder,
            "decoder": self.decoder,
            "encoders": self.encoders,
            "notes": self.notes,
            "used": [
                *([f"export encoding ({self.encoder})"] if self.nvenc else []),
                *(["editing proxies"] if self.nvenc else []),
                *([f"decoding while scanning and building proxies ({self.decoder})"] if self.nvdec else []),
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
def probe_encoders() -> tuple[dict, ...]:
    """Try every hardware encoder on real frames, and keep the failure reasons.

    Two lessons are baked in here. `ffmpeg -encoders | grep nvenc` is not
    evidence — the encoder is listed on machines whose driver refuses it at
    runtime. And when the probe fails, **the reason is the useful part**: the
    first version of this returned a bare `False`, so a user whose card could
    decode but not encode was told "no" with no way to find out why. FFmpeg's
    own last line of stderr usually says exactly what is wrong (no NVENC-capable
    device, driver too old, session limit reached, running on the integrated
    GPU).

    The clip is 1280×720 rather than a token 256×256: some encoders refuse tiny
    frames, and a probe that fails for a reason the real work would never hit is
    worse than no probe.
    """
    import os
    import tempfile

    results: list[dict] = []
    for name, vendor, codec in ENCODERS:
        entry = {"name": name, "vendor": vendor, "codec": codec, "ok": False, "reason": ""}
        target = os.path.join(tempfile.gettempdir(), f"ce-encprobe-{name}.mp4")
        try:
            out = _run([
                ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
                # A second and a half, encoded to the end, into a real file.
                #
                # The first version asked for three frames into `-f null -` and
                # a GTX 1650 answered "Nothing was written into output file,
                # because at least one of its streams received no packets" — so
                # we told its owner their card could not encode. It can: NVENC
                # buffers several frames internally and only flushes at end of
                # stream, so a three-frame probe finishes before the encoder has
                # produced anything. The probe was wrong, not the card.
                "-f", "lavfi", "-i", f"testsrc2=size=1280x720:rate=30:duration=1.5",
                "-an", "-c:v", name, target,
            ])
            wrote = os.path.exists(target) and os.path.getsize(target) > 1024
            entry["ok"] = out.returncode == 0 and wrote
            if not entry["ok"]:
                lines = [line.strip() for line in (out.stderr or "").splitlines() if line.strip()]
                entry["reason"] = (
                    lines[-1][:200] if lines
                    else (f"exit code {out.returncode}" if out.returncode else "wrote an empty file")
                )
        except Exception as error:  # noqa: BLE001
            entry["reason"] = f"{type(error).__name__}: {error}"[:200]
        finally:
            try:
                os.remove(target)
            except OSError:
                pass
        results.append(entry)
    return tuple(results)


def best_encoder() -> dict | None:
    """The first hardware encoder on this machine that actually works."""
    for entry in probe_encoders():
        if entry["ok"]:
            return entry
    return None


@lru_cache(maxsize=1)
def can_encode() -> bool:
    """Is there *any* usable hardware encoder here?"""
    return best_encoder() is not None


@lru_cache(maxsize=1)
def best_decoder() -> str | None:
    """The first hardware decoder that survives decoding a real file."""
    import os
    import tempfile

    sample = os.path.join(tempfile.gettempdir(), "ce-hwdec-probe.mp4")
    try:
        made = _run([
            ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25:duration=1",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", sample,
        ])
        if made.returncode != 0:
            return None
        for name in DECODERS:
            out = _run([
                ffmpeg_binary(), "-hide_banner", "-loglevel", "error",
                "-hwaccel", name, "-i", sample, "-frames:v", "5", "-f", "null", "-",
            ])
            if out.returncode == 0:
                return name
    except Exception:  # noqa: BLE001
        return None
    return None


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
    chosen = best_encoder()
    caps = Capabilities(
        name=card.get("name"),
        memory_mb=card.get("memory_mb"),
        driver=card.get("driver"),
        nvenc=chosen is not None,
        nvdec=can_decode(),
    )
    caps.encoder = chosen["name"] if chosen else "libx264 (processor)"
    caps.decoder = best_decoder() or "processor"
    caps.encoders = list(probe_encoders())
    if deep:
        caps.whisper_device, caps.whisper_detail = whisper_status()

    if not card and not caps.nvenc and not caps.nvdec:
        caps.notes.append("No hardware acceleration was usable here — everything runs on the processor.")

    if not caps.nvenc:
        # Say *why*, with FFmpeg's own words. "no" with no reason is what sent
        # the owner back to ask what the number meant.
        failures = [e for e in caps.encoders if e["reason"]]
        if failures:
            caps.notes.append(f"Hardware encoding is off: {failures[0]['reason']}")
        caps.notes.append(
            "Encoding on the processor is not a failure — x264 at `veryfast` keeps up with "
            "1080p comfortably; the card matters most for 4K and long exports."
        )

    if caps.memory_mb:
        gigabytes = caps.memory_mb / 1024
        if gigabytes < 6:
            fits = "a 3B model"
        elif gigabytes < 12:
            fits = "a 7B model at q4"
        elif gigabytes < 20:
            fits = "a 13B model at q4"
        else:
            fits = "a 30B model at q4"
        caps.notes.append(
            f"{gigabytes:.0f} GB of video memory: {fits} runs entirely on the card. "
            "Anything larger spills into system memory and slows down."
        )
    return caps


# ------------------------------------------------------------------ arguments


def decode_args() -> list[str]:
    """FFmpeg arguments that put decoding on the card, when it can take it.

    These go *before* `-i` — after the input FFmpeg silently ignores them.
    Whichever backend answered first is used, so this is not an NVIDIA feature:
    an Intel laptop gets Quick Sync, an AMD desktop gets D3D11VA.
    """
    name = best_decoder()
    return ["-hwaccel", name] if name else []


def encode_args(quality: dict | None = None) -> list[str]:
    """The encoder settings for *this* machine, whatever it happens to be.

    NVIDIA, Intel and AMD each want different flags for "constant quality", so
    the mapping lives here rather than in every caller. A machine with no usable
    hardware encoder gets x264, which is not a failure — on the owner's laptop
    x264 encodes five seconds of 1080p in 0.48 s.
    """
    quality = quality or {}
    chosen = best_encoder()
    if chosen is None:
        return [
            "-c:v", "libx264",
            "-preset", str(quality.get("preset", "veryfast")),
            "-crf", str(quality.get("crf", 21)),
        ]

    name = chosen["name"]
    level = int(quality.get("nvenc_cq", 23))
    if name.endswith("_nvenc"):
        return ["-c:v", name, "-preset", str(quality.get("nvenc_preset", "p5")),
                "-rc", "vbr", "-cq", str(level), "-b:v", "0"]
    if name.endswith("_qsv"):
        return ["-c:v", name, "-global_quality", str(level), "-look_ahead", "1"]
    if name.endswith("_amf"):
        return ["-c:v", name, "-rc", "cqp", "-qp_i", str(level), "-qp_p", str(level)]
    if name.endswith("_vaapi"):
        return ["-c:v", name, "-rc_mode", "CQP", "-qp", str(level)]
    return ["-c:v", name]


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

    chosen = best_encoder()
    if chosen is not None:
        started = time.time()
        card = _run([ffmpeg_binary(), "-hide_banner", "-loglevel", "error", *source,
                     *encode_args({"nvenc_cq": 23}), "-f", "null", "-"], timeout=300)
        result["gpu"] = round(time.time() - started, 2) if card.returncode == 0 else None
        result["encoder"] = chosen["name"]
    else:
        result["gpu"] = None
        result["encoder"] = "libx264 (processor)"
        # The reason belongs next to the number, or the number raises a question
        # the user has to come back and ask.
        failed = [e for e in probe_encoders() if e["reason"]]
        result["reason"] = failed[0]["reason"] if failed else "no hardware encoder on this machine"

    if result.get("cpu") and result.get("gpu"):
        result["speedup"] = round(result["cpu"] / result["gpu"], 2)
    return result
