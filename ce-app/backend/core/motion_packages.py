"""Motion package switcher — deliverable via differential update.

The switcher itself ships in the base app; the *packages* are plain data
(presets of motion parameters) that the app reads at runtime. Built-in presets
are bundled; extra packages can be dropped into ~/CuttingEdge/motion/ and are
picked up without a reinstall — which is exactly what makes the feature safe to
grow through a differential update (the loader is already in the base app).

Each preset tunes the app's motion language: particle density, stagger, duration
scale and easing. The frontend reads the active package and applies it as CSS
variables, so switching is instant and reversible.

Four parameters, and each one is read by something real (the contract is guarded
by `tests/test_motion.py`):

* `particles` → how many motes `LiveGlobe` draws,
* `stagger`   → the gap between siblings in a staggered rise (CSS `--m-stagger`),
* `duration`  → a multiplier on every animation/transition length (`--m-speed`),
* `ease`      → the curve those animations use (CSS `--m-ease`).

The brain picks a package the same way it picks a tool: from what it measured
(`recommend()`), and Style Match shows that pick with its reason.
"""
from __future__ import annotations

import json
from pathlib import Path

from app.config import settings

#: The parameter keys a package may carry, with the range they are clamped to.
#: A drop-in file is user data: a stray `"particles": 100000` must not hang the
#: renderer, so every number is clamped on the way in.
PARAM_RANGES: dict[str, tuple[float, float]] = {
    "particles": (0, 64),
    "stagger": (0.0, 0.6),
    "duration": (0.4, 2.5),
}
#: `ease` is a CSS timing function, so it is the one parameter that touches the
#: DOM as text. Rather than trusting it, the shape is parsed and the curve is
#: rebuilt from its own numbers — a package cannot inject CSS through it.
SAFE_EASES = ("linear", "ease", "ease-in", "ease-out", "ease-in-out")
#: The built-in packages. *Cinematic* is the reference: its numbers are the CSS
#: defaults in `global.css` (`--m-speed: 1`, `--m-stagger: 50ms`, the `--ce-ease`
#: token curve), so the app looks exactly as it did before the switcher existed
#: until the user asks for something else.
BUILTINS = [
    {"id": "cinematic", "en": "Cinematic", "fa": "سینمایی",
     "params": {"particles": 8, "stagger": 0.05, "duration": 1.0, "ease": "cubic-bezier(.22,.61,.36,1)"}},
    {"id": "energetic", "en": "Energetic", "fa": "پرانرژی",
     "params": {"particles": 20, "stagger": 0.03, "duration": 0.8, "ease": "cubic-bezier(.34,1.56,.64,1)"}},
    {"id": "calm", "en": "Calm", "fa": "آرام",
     "params": {"particles": 4, "stagger": 0.09, "duration": 1.4, "ease": "ease-in-out"}},
    {"id": "celebration", "en": "Celebration", "fa": "جشن",
     "params": {"particles": 28, "stagger": 0.025, "duration": 0.9, "ease": "cubic-bezier(.34,1.56,.64,1)"}},
]


def _safe_ease(value: object) -> str | None:
    """Return a timing function we are willing to put in the DOM, or None."""
    if not isinstance(value, str):
        return None
    text = " ".join(value.split())
    if text in SAFE_EASES:
        return text
    if text.startswith("cubic-bezier(") and text.endswith(")"):
        try:
            numbers = [float(part) for part in text[13:-1].split(",")]
        except ValueError:
            return None
        if len(numbers) == 4 and all(n == n for n in numbers):  # no NaN
            x1, y1, x2, y2 = numbers
            if 0.0 <= x1 <= 1.0 and 0.0 <= x2 <= 1.0 and abs(y1) <= 4 and abs(y2) <= 4:
                # rebuilt from the parsed numbers, never copied from the input
                return f"cubic-bezier({x1:g},{y1:g},{x2:g},{y2:g})"
    return None


def _extra_dir() -> Path:
    path = Path(settings.cuttingedge_home) / "motion"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _sanitise(params: dict) -> dict:
    """Clamp the numbers, keep only a known easing curve, fill the defaults.

    A package is data the user drops in, so it is treated like any other input:
    unknown keys are dropped, numbers are clamped to `PARAM_RANGES`, and an
    `ease` that does not parse as a timing function falls back to the built-in
    curve instead of being handed to the browser.
    """
    base = dict(BUILTINS[0]["params"])
    for key, value in (params or {}).items():
        if key in PARAM_RANGES:
            try:
                number = float(value)
            except (TypeError, ValueError):
                continue
            low, high = PARAM_RANGES[key]
            base[key] = round(min(high, max(low, number)), 4)
        elif key == "ease":
            safe = _safe_ease(value)
            if safe:
                base["ease"] = safe
    if "particles" in base:
        base["particles"] = int(base["particles"])
    return base


def list_packages() -> list[dict]:
    """Built-ins plus any user-dropped packages, with the active flag."""
    out = [{**p, "params": _sanitise(p["params"])} for p in BUILTINS]
    for f in sorted(_extra_dir().glob("*.json")):
        try:
            extra = json.loads(f.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001 — a bad package is ignored, not fatal
            continue
        if isinstance(extra, dict) and extra.get("id") and isinstance(extra.get("params"), dict):
            out.append({"id": str(extra["id"]),
                        "en": str(extra.get("en") or extra["id"]),
                        "fa": str(extra.get("fa") or extra.get("en") or extra["id"]),
                        "params": _sanitise(extra["params"]),
                        "builtin": False})
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
    # The saved package is gone (a drop-in was deleted) — say so by falling back
    # to the first built-in rather than crashing the whole app on start-up.
    return _sanitise(BUILTINS[0]["params"])


def recommend(signals: dict | None = None) -> dict:
    """Which package the material argues for — from measurements, not taste.

    Uses exactly the signals the brain already measures (`core/brain/intake.py`,
    `core/engine/style.py`): tempo, action peaks, the room's reaction and how
    much of the file is speech. With nothing measured it says so and keeps the
    built-in default, because a recommendation without a measurement is a guess
    and this app does not guess.
    """
    s = signals or {}
    bpm = float(s.get("bpm", 0) or 0)
    action = float(s.get("action", 0) or 0)
    emotion = float(s.get("emotion", 0) or 0)
    speech = float(s.get("speech_ratio", 0) or 0)
    measured = bool(s.get("measured", True)) and (bpm or action or emotion or speech)

    if not measured:
        picked, en, fa = "cinematic", "nothing measured yet — the neutral package stays", \
            "هنوز چیزی سنجیده نشده — بسته‌ی خنثی می‌ماند"
    elif emotion >= 0.25:
        picked = "celebration"
        en = f"the room reacts (measured reaction {emotion:.2f})"
        fa = f"جمعیت واکنش نشان می‌دهد (واکنش سنجیده‌شده {emotion:.2f})"
    elif bpm >= 128 and action >= 0.6:
        picked = "celebration"
        en = f"fast and hard-hitting ({bpm:.0f} BPM, action {action:.2f})"
        fa = f"سریع و کوبنده ({bpm:.0f} ضرب، حرکت {action:.2f})"
    elif action >= 0.45 or bpm >= 110:
        picked = "energetic"
        en = f"fast material (action {action:.2f}, {bpm:.0f} BPM)"
        fa = f"متریال سریع (حرکت {action:.2f}، {bpm:.0f} ضرب)"
    elif speech >= 0.45:
        picked = "calm"
        en = f"{speech:.0%} of the file is speech — motion should stay out of the way"
        fa = f"{speech:.0%} از فایل گفتار است — موشن باید کنار بایستد"
    else:
        picked, en, fa = "cinematic", "no strong signal either way — the neutral package", \
            "سیگنال قوی به هیچ سمت نیست — بسته‌ی خنثی"

    known = {p["id"]: p for p in list_packages()}
    if picked not in known:  # a recommendation must be a package that exists
        picked = "cinematic"
    return {"id": picked, "params": known[picked]["params"],
            "reasonEn": en, "reasonFa": fa, "measured": bool(measured),
            "signals": {"bpm": bpm, "action": action, "emotion": emotion,
                        "speech_ratio": speech}}


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
