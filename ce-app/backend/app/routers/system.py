import json, platform, shutil
from pathlib import Path
import psutil
from fastapi import APIRouter
from app import __version__
from app.models.settings import SettingsModel
from app.schemas.job import SystemInfo

router = APIRouter(prefix="/api/system", tags=["system"])

def _graphics() -> dict:
    """The real answer about the graphics card, cached inside `core.engine.gpu`."""
    from core.engine import gpu

    caps = gpu.capabilities()
    return {
        "available": bool(caps.nvenc or caps.nvdec or caps.name),
        "name": caps.name,
        "driver": caps.driver,
        "memoryMb": caps.memory_mb,
        "encode": caps.nvenc,
        "decode": caps.nvdec,
    }


@router.get("/doctor")
def run_doctor():
    return {
        "system": {"platform": platform.platform(), "python_version": platform.python_version(),
                   "cpu_count": psutil.cpu_count(), "memory_gb": round(psutil.virtual_memory().total/(1024**3),1),
                   "disk_free_gb": round(shutil.disk_usage(Path.home()).free/(1024**3),1)},
        "ffmpeg": {"found": bool(shutil.which("ffmpeg")), "path": shutil.which("ffmpeg")},
        # This used to be the literal `False`, so a machine with a working card
        # was told it had none. It is a probe now (see core/engine/gpu.py).
        "cuda": _graphics(),
        "warnings": [] if shutil.which("ffmpeg") else ["FFmpeg not found — install ffmpeg to enable video processing"],
        "errors": [], "healthy": True,
    }

@router.get("/info", response_model=SystemInfo)
def system_info():
    ff = shutil.which("ffmpeg")
    return SystemInfo(version=__version__, python_version=platform.python_version(), platform=platform.platform(),
                      ffmpeg_found=bool(ff), ffmpeg_path=ff,
                      cuda_available=bool(_graphics()["available"]),
                      cuda_version=_graphics().get("driver"),
                      disk_free_gb=round(shutil.disk_usage(Path.home()).free/(1024**3),1),
                      memory_gb=round(psutil.virtual_memory().total/(1024**3),1))

@router.get("/settings")
def get_settings():
    return SettingsModel.get_all()

@router.put("/settings")
def update_settings(data: dict):
    for key, value in data.items():
        SettingsModel.set(key, json.dumps(value) if isinstance(value,(dict,list)) else str(value))
    return {"status":"saved"}

@router.get("/attribution")
def attribution() -> dict:
    """Every shipped library with its licence, for the credit screen."""
    from core.engine import attribution as attr

    return attr.full()
