"""Cutting Edge (CE) — the editing assistant.

The assistant never writes code and never touches media. It emits **operations**
against the edit model, from a fixed whitelist, which the editor validates and
applies as a single undoable step. That is what makes "ask it for anything" safe:
the worst a bad answer can do is produce an edit you press Ctrl+Z on.

Two planners share one schema:

* a rule-based planner that runs offline with no key at all, covering the
  intents people actually ask for, in English and Persian;
* an LLM planner used when a provider is configured, which is handed the same
  schema plus a compact description of the timeline.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

from app.config import settings

# --------------------------------------------------------------------------- #
# Operation schema — the entire vocabulary the assistant may use.
# --------------------------------------------------------------------------- #

OPERATIONS: dict[str, str] = {
    "removeSilence": "Detect and cut silent gaps in a clip. args: clipId?",
    "splitScenes": "Split a clip at shot changes. args: clipId?",
    "splitAt": "Split a clip at a timeline position. args: clipId?, at (seconds)",
    "setSpeed": "Change playback rate. args: clipId?, speed (0.25-4)",
    "setVolume": "Set clip loudness. args: clipId?, volume (0-2)",
    "mute": "Mute or unmute a clip. args: clipId?, muted (bool)",
    "setOpacity": "Set clip opacity. args: clipId?, opacity (0-1)",
    "fade": "Fade a clip in and/or out. args: clipId?, fadeIn?, fadeOut? (seconds)",
    "reverse": "Play a clip backwards. args: clipId?, reversed (bool)",
    "crop": "Crop edges as fractions. args: clipId?, left?, top?, right?, bottom? (0-0.45)",
    "transform": "Scale, rotate or move a clip. args: clipId?, scale?, rotate?, x?, y?",
    "addTransition": "Put a transition after a clip. args: clipId?, type?, duration?",
    "addTransitionsEverywhere": "Add the same transition between every neighbouring pair. args: type?, duration?",
    "removeTransition": "Remove the transition after a clip. args: clipId?",
    "trimTo": "Shorten the whole timeline to a target length. args: seconds",
    "deleteClip": "Remove a clip. args: clipId?",
    "duplicateClip": "Duplicate a clip. args: clipId?",
    "setExport": "Choose the export format. args: width?, height?, fps?, quality?",
    "setFilter": "Apply a colour look. args: clipId?, filter (none|warm|cool|cinematic|vivid|bw|sepia|vintage|matte|night)",
    "setAdjust": "Grade the picture. args: clipId?, brightness?, contrast?, saturation?, temperature?, sharpen?, vignette?",
    "setAnimation": "Animate the clip in/out. args: clipId?, animIn?, animOut? (none|fade|zoomIn|zoomOut), duration?",
    "denoise": "Reduce background noise. args: clipId?, amount (0-1)",
    "enhanceVoice": "Clean up speech: high-pass, presence, compression, loudness. args: clipId?, enabled?",
}

TRANSITION_WORDS = {
    "fade": "fade", "محو": "fade", "dissolve": "dissolve", "حل": "dissolve",
    "wipe": "wipeleft", "پاک": "wipeleft", "slide": "slideleft", "لغزش": "slideleft",
    "zoom": "zoomin", "زوم": "zoomin", "circle": "circleopen", "دایره": "circleopen",
    "pixel": "pixelize", "پیکسل": "pixelize", "black": "fadeblack", "سیاه": "fadeblack",
}

LOOK_WORDS = {
    "warm": "warm", "گرم": "warm",
    "cool": "cool", "سرد": "cool",
    "cinematic": "cinematic", "سینمایی": "cinematic", "film": "cinematic",
    "vivid": "vivid", "پرمایه": "vivid", "saturated": "vivid",
    "black and white": "bw", "b&w": "bw", "سیاه و سفید": "bw", "سیاه‌وسفید": "bw", "grayscale": "bw",
    "sepia": "sepia", "سپیا": "sepia",
    "vintage": "vintage", "قدیمی": "vintage", "retro": "vintage",
    "matte": "matte", "مات": "matte",
    "night": "night", "شب": "night",
}

FORMAT_WORDS: dict[str, tuple[int, int]] = {
    "9:16": (1080, 1920), "۹:۱۶": (1080, 1920), "shorts": (1080, 1920),
    "reels": (1080, 1920), "tiktok": (1080, 1920), "vertical": (1080, 1920),
    "عمودی": (1080, 1920), "استوری": (1080, 1920),
    "1:1": (1080, 1080), "square": (1080, 1080), "مربع": (1080, 1080),
    "4:5": (1080, 1350), "portrait": (1080, 1350),
    "16:9": (1920, 1080), "youtube": (1920, 1080), "افقی": (1920, 1080), "landscape": (1920, 1080),
    "4k": (3840, 2160), "uhd": (3840, 2160),
}


@dataclass
class Plan:
    ops: list[dict[str, Any]] = field(default_factory=list)
    explanation: str = ""
    source: str = "rules"
    warnings: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "ops": self.ops,
            "explanation": self.explanation,
            "source": self.source,
            "warnings": self.warnings,
        }


# --------------------------------------------------------------------------- #
# Rule-based planner — works with no key, no network, no model.
# --------------------------------------------------------------------------- #

def _number(text: str, *patterns: str, default: float | None = None) -> float | None:
    for pattern in patterns:
        match = re.search(pattern, text)
        if match:
            try:
                return float(match.group(1).replace("٫", ".").replace("،", ""))
            except ValueError:
                continue
    return default


def _persian_digits(text: str) -> str:
    return text.translate(str.maketrans("۰۱۲۳۴۵۶۷۸۹", "0123456789"))


def rule_based_plan(prompt: str, timeline: dict) -> Plan:
    text = _persian_digits(prompt.lower().strip())
    ops: list[dict[str, Any]] = []
    notes: list[str] = []

    def wants(*words: str) -> bool:
        return any(w in text for w in words)

    # --- silence ---------------------------------------------------------
    if wants("silence", "silent", "سکوت", "مکث", "pause"):
        ops.append({"op": "removeSilence"})
        notes.append("remove silent gaps")

    # --- scenes ----------------------------------------------------------
    if wants("scene", "shot", "نما", "صحنه", "کات"):
        ops.append({"op": "splitScenes"})
        notes.append("split at shot changes")

    # --- speed -----------------------------------------------------------
    speed = _number(text, r"(\d+(?:\.\d+)?)\s*(?:x|×|برابر)", r"speed\s*(\d+(?:\.\d+)?)")
    if speed and speed > 0:
        if wants("slow", "کند", "آهسته") and speed > 1:
            speed = 1 / speed
        clamped = max(0.25, min(4.0, speed))
        # Out-of-range requests are clamped, never silently dropped: doing nothing
        # is the one answer an assistant must not give.
        if abs(clamped - speed) > 1e-6:
            notes.append(f"clamped {speed:g}x to {clamped:g}x")
        ops.append({"op": "setSpeed", "speed": clamped})
        notes.append(f"set speed to {clamped:g}x")
    elif wants("faster", "speed up", "سریع‌تر", "سریعتر", "تندتر"):
        ops.append({"op": "setSpeed", "speed": 1.5})
        notes.append("speed up 1.5x")
    elif wants("slower", "slow down", "کندتر", "آهسته‌تر"):
        ops.append({"op": "setSpeed", "speed": 0.5})
        notes.append("slow down to 0.5x")

    # --- audio -----------------------------------------------------------
    if wants("mute", "بی‌صدا", "بیصدا", "صدا رو ببند"):
        ops.append({"op": "mute", "muted": True})
        notes.append("mute the clip")
    volume = _number(text, r"volume\s*(?:to\s*)?(\d+)\s*%", r"صدا\s*(?:رو\s*)?(\d+)\s*(?:٪|درصد)")
    if volume is not None:
        ops.append({"op": "setVolume", "volume": max(0.0, min(2.0, volume / 100))})
        notes.append(f"volume {volume:g}%")

    # --- fades -----------------------------------------------------------
    if wants("fade in", "محو ورودی", "فید این"):
        ops.append({"op": "fade", "fadeIn": 0.7})
        notes.append("fade in")
    if wants("fade out", "محو خروجی", "فید اوت"):
        ops.append({"op": "fade", "fadeOut": 0.7})
        notes.append("fade out")

    # --- transitions -----------------------------------------------------
    if wants("transition", "ترنزیشن", "گذار"):
        kind = next((v for k, v in TRANSITION_WORDS.items() if k in text), "fade")
        duration = _number(text, r"(\d+(?:\.\d+)?)\s*(?:s|sec|ثانیه)", default=0.5) or 0.5
        everywhere = wants("all", "every", "همه", "بین همه", "each")
        ops.append({
            "op": "addTransitionsEverywhere" if everywhere else "addTransition",
            "type": kind,
            "duration": max(0.1, min(2.0, duration)),
        })
        notes.append(f"{kind} transition{'s everywhere' if everywhere else ''}")

    # --- reverse / opacity / crop ---------------------------------------
    if wants("reverse", "backward", "معکوس", "برعکس"):
        ops.append({"op": "reverse", "reversed": True})
        notes.append("reverse playback")

    opacity = _number(text, r"opacity\s*(?:to\s*)?(\d+)\s*%", r"شفافیت\s*(\d+)\s*(?:٪|درصد)")
    if opacity is not None:
        ops.append({"op": "setOpacity", "opacity": max(0.0, min(1.0, opacity / 100))})
        notes.append(f"opacity {opacity:g}%")

    # --- colour ----------------------------------------------------------
    look = next((v for k, v in LOOK_WORDS.items() if k in text), None)
    if look and wants("look", "filter", "grade", "فیلتر", "رنگ", "حالت", "make it"):
        ops.append({"op": "setFilter", "filter": look})
        notes.append(f"{look} look")

    if wants("brighter", "روشن‌تر", "روشنتر"):
        ops.append({"op": "setAdjust", "brightness": 0.12})
        notes.append("brighter")
    elif wants("darker", "تاریک‌تر", "تاریکتر"):
        ops.append({"op": "setAdjust", "brightness": -0.12})
        notes.append("darker")
    if wants("more contrast", "کنتراست بیشتر"):
        ops.append({"op": "setAdjust", "contrast": 1.25})
        notes.append("more contrast")
    if wants("sharper", "واضح‌تر", "شارپ"):
        ops.append({"op": "setAdjust", "sharpen": 0.6})
        notes.append("sharper")
    if wants("vignette", "وینیت"):
        ops.append({"op": "setAdjust", "vignette": 0.5})
        notes.append("vignette")

    # --- animation ---------------------------------------------------------
    if wants("zoom in", "زوم به داخل", "زوم بده"):
        ops.append({"op": "setAnimation", "animIn": "zoomIn"})
        notes.append("zoom-in animation")
    elif wants("zoom out", "زوم به بیرون"):
        ops.append({"op": "setAnimation", "animOut": "zoomOut"})
        notes.append("zoom-out animation")
    elif wants("animate", "انیمیشن"):
        ops.append({"op": "setAnimation", "animIn": "fade", "animOut": "fade"})
        notes.append("fade animation")

    # --- audio cleanup -----------------------------------------------------
    if wants("noise", "نویز", "خش"):
        ops.append({"op": "denoise", "amount": 0.7})
        notes.append("noise reduction")
    if wants("enhance voice", "clean up the voice", "بهبود صدا", "صدا رو تمیز", "voice clearer"):
        ops.append({"op": "enhanceVoice", "enabled": True})
        notes.append("voice enhancement")

    # --- length ----------------------------------------------------------
    target = _number(
        text,
        r"(?:under|within|to)\s*(\d+(?:\.\d+)?)\s*(?:s|sec|second|ثانیه)",
        r"(\d+(?:\.\d+)?)\s*(?:s|sec|second|ثانیه)\s*(?:or less|یا کمتر|بشه|کن)",
    )
    if target and wants("shorten", "trim", "cut to", "کوتاه", "برسان", "برسون"):
        ops.append({"op": "trimTo", "seconds": target})
        notes.append(f"trim to {target:g}s")

    # --- export format ---------------------------------------------------
    for word, (width, height) in FORMAT_WORDS.items():
        if word in text:
            ops.append({"op": "setExport", "width": width, "height": height})
            notes.append(f"export {width}x{height}")
            break

    if not ops:
        return Plan(
            ops=[],
            explanation="",
            source="rules",
            warnings=["no-match"],
        )

    return Plan(ops=ops, explanation="; ".join(notes), source="rules")


# --------------------------------------------------------------------------- #
# LLM planner
# --------------------------------------------------------------------------- #

SYSTEM_PROMPT = """You are the editing assistant inside a video editor.

Convert the user's request into operations on the timeline. Reply with JSON only:
{"ops": [...], "explanation": "one short sentence"}

Allowed operations (use no others):
%s

Rules:
- omit clipId to act on the selected clip, or the first clip when nothing is selected
- never invent clip ids; only use ids from the timeline description
- keep values inside the documented ranges
- prefer the smallest set of operations that satisfies the request
- if the request is impossible with these operations, return {"ops": [], "explanation": "why"}
"""


def _provider_config() -> tuple[str, str, str] | None:
    """(provider, api_key, model) for whichever provider is configured."""
    if settings.ollama_enabled:
        return ("ollama", "", settings.ollama_model or "llama3")
    if settings.openai_api_key:
        return ("openai", settings.openai_api_key, "gpt-4o-mini")
    if settings.gemini_api_key:
        return ("gemini", settings.gemini_api_key, "gemini-1.5-flash")
    if settings.anthropic_api_key:
        return ("anthropic", settings.anthropic_api_key, "claude-3-5-haiku-latest")
    return None


def _chat(prompt: str, timeline_text: str) -> str | None:
    config = _provider_config()
    if not config:
        return None
    provider, key, model = config
    system = SYSTEM_PROMPT % json.dumps(OPERATIONS, indent=1)
    user = f"Timeline:\n{timeline_text}\n\nRequest: {prompt}"

    import requests  # imported lazily so the editor works without network deps

    try:
        if provider == "ollama":
            response = requests.post(
                "http://127.0.0.1:11434/api/chat",
                json={
                    "model": model,
                    "messages": [
                        {"role": "system", "content": system},
                        {"role": "user", "content": user},
                    ],
                    "stream": False,
                    "format": "json",
                },
                timeout=90,
            )
            return response.json().get("message", {}).get("content")

        if provider == "openai":
            response = requests.post(
                f"{settings.openai_base_url.rstrip('/')}/chat/completions",
                headers={"Authorization": f"Bearer {key}"},
                json={
                    "model": model,
                    "messages": [
                        {"role": "system", "content": system},
                        {"role": "user", "content": user},
                    ],
                    "response_format": {"type": "json_object"},
                    "temperature": 0.2,
                },
                timeout=60,
            )
            return response.json()["choices"][0]["message"]["content"]

        if provider == "gemini":
            response = requests.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}",
                json={
                    "systemInstruction": {"parts": [{"text": system}]},
                    "contents": [{"parts": [{"text": user}]}],
                    "generationConfig": {"responseMimeType": "application/json", "temperature": 0.2},
                },
                timeout=60,
            )
            return response.json()["candidates"][0]["content"]["parts"][0]["text"]

        if provider == "anthropic":
            response = requests.post(
                "https://api.anthropic.com/v1/messages",
                headers={"x-api-key": key, "anthropic-version": "2023-06-01"},
                json={
                    "model": model,
                    "max_tokens": 1024,
                    "system": system,
                    "messages": [{"role": "user", "content": user}],
                },
                timeout=60,
            )
            return response.json()["content"][0]["text"]
    except Exception:  # noqa: BLE001 — the caller falls back to rules
        return None
    return None


def _parse_ops(raw: str) -> tuple[list[dict], str]:
    match = re.search(r"\{.*\}", raw, re.S)
    if not match:
        return [], ""
    try:
        data = json.loads(match.group(0))
    except json.JSONDecodeError:
        return [], ""
    ops = [op for op in data.get("ops", []) if isinstance(op, dict) and op.get("op") in OPERATIONS]
    return ops, str(data.get("explanation", ""))


def describe_timeline(timeline: dict) -> str:
    """A compact, token-cheap description of what is on the timeline."""
    lines = []
    for track in timeline.get("tracks", []):
        clips = [c for c in timeline.get("clips", []) if c.get("trackId") == track.get("id")]
        lines.append(f"- lane {track.get('id')} ({track.get('kind')}): {len(clips)} clip(s)")
        for clip in sorted(clips, key=lambda c: c.get("start", 0))[:12]:
            props = clip.get("props") or {}
            extra = []
            if props.get("speed", 1) != 1:
                extra.append(f"speed {props['speed']}x")
            if props.get("muted"):
                extra.append("muted")
            lines.append(
                f"    id={clip.get('id')} \"{clip.get('label', '')}\" "
                f"start={clip.get('start', 0):.2f}s len={clip.get('duration', 0):.2f}s"
                + (f" [{', '.join(extra)}]" if extra else "")
            )
    if timeline.get("transitions"):
        lines.append(f"- {len(timeline['transitions'])} transition(s)")
    return "\n".join(lines) or "(empty timeline)"


def make_plan(prompt: str, timeline: dict, *, prefer_llm: bool = True) -> Plan:
    """LLM first when one is configured, rules otherwise — and rules as a net."""
    if prefer_llm and _provider_config():
        raw = _chat(prompt, describe_timeline(timeline))
        if raw:
            ops, explanation = _parse_ops(raw)
            if ops:
                provider = _provider_config()
                return Plan(ops=ops, explanation=explanation, source=provider[0] if provider else "llm")

    plan = rule_based_plan(prompt, timeline)
    if not plan.ops:
        plan.explanation = (
            "I could not turn that into an edit. Try: remove silence, split scenes, "
            "2x speed, add fade transitions, mute, trim to 30 seconds, export 9:16."
        )
    return plan
