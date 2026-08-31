"""Motion package switcher — deliverable via differential update.

The switcher itself ships in the base app; the *packages* are plain data
(presets of motion parameters) that the app reads at runtime. Built-in presets
are bundled; extra packages can be dropped into ~/CuttingEdge/motion/ and are
picked up without a reinstall — which is exactly what makes the feature safe to
grow through a differential update (the loader is already in the base app).

Each preset tunes the app's motion language: particle density, stagger, duration
scale and easing. The frontend reads the active package and applies it as CSS
variables, so switching is instant and reversible.
"""
from __future__ import annotations

import json
from pathlib import Path

from app.config import settings

BUILTINS = [
    {"id": "cinematic", "en": "Cinematic", "fa": "سینمایی",
     "params": {"particles": 8, "stagger": 0.12, "duration": 1.15, "ease": "cubic-bezier(.22,.61,.36,1)"}},
    {"id": "energetic", "en": "Energetic", "fa": "پرانرژی",
     "params": {"particles": 20, "stagger": 0.06, "duration": 0.8, "ease": "cubic-bezier(.34,1.56,.64,1)"}},
    {"id": "calm", "en": "Calm", "fa": "آرام",
     "params": {"particles": 4, "stagger": 0.2, "duration": 1.4, "ease": "ease-in-out"}},
    {"id": "celebration", "en": "Celebration", "fa": "جشن",
     "params": {"particles": 28, "stagger": 0.05, "duration": 0.9, "ease": "cubic-bezier(.34,1.56,.64,1)"}},
]


def _extra_dir() -> Path:
    path = Path(settings.cuttingedge_home) / "motion"
    path.mkdir(parents=True, exist_ok=True)
    return path


def list_packages() -> list[dict]:
    """Built-ins plus any user-dropped packages, with the active flag."""
    out = [dict(p) for p in BUILTINS]
    for f in sorted(_extra_dir().glob("*.json")):
        try:
            extra = json.loads(f.read_text(encoding="utf-8"))
            if extra.get("id") and extra.get("params"):
                out.append(extra)
        except Exception:  # noqa: BLE001 — a bad package is ignored, not fatal
            continue
    active = get_active()
    for p in out:
        p["active"] = p["id"] == active
    return out


def get_active() -> str:
    return (settings.motion_package or "cinematic").strip() or "cinematic"


def get_params(active: str | None = None) -> dict:
    active = active or get_active()
    for p in list_packages():
        if p["id"] == active:
            return p["params"]
    return BUILTINS[0]["params"]


def set_active(package_id: str) -> dict:
    known = {p["id"] for p in list_packages()}
    if package_id not in known:
        raise ValueError(f"unknown motion package {package_id}")
    settings.motion_package = package_id
    try:
        existing = json.loads(CONFIG_TEXT()) if CONFIG_PATH().exists() else {}
        existing["motion_package"] = package_id
        CONFIG_PATH().write_text(json.dumps(existing, indent=2), encoding="utf-8")
    except Exception:  # noqa: BLE001
        pass
    return {"active": package_id, "params": get_params(package_id)}


def CONFIG_PATH() -> Path:
    return Path(settings.cuttingedge_home) / "config.json"


def CONFIG_TEXT() -> str:
    return CONFIG_PATH().read_text(encoding="utf-8")
