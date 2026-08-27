"""Tier 3 — an agent-native surface over the editor brain.

Advisor 2's ask: the 17-tool brain should be reachable as a protocol, so an
external agent (Claude/Cursor/Codex, or a local MCP bridge) can drive the real
timeline instead of a black-box render. This router is the stable HTTP half of
that: it publishes each tool as a JSON-Schema-described action and executes the
deterministic ones; the ones that mutate the client timeline are returned as
validated actions for the renderer to apply (it owns the undo stack).

A full MCP stdio server can wrap these three endpoints unchanged — the contract
is already tool/schema/call, which is all MCP adds.
"""
from __future__ import annotations

import re

from fastapi import APIRouter
from pydantic import BaseModel

from core.brain import editor_brain

router = APIRouter(prefix="/api/agent", tags=["agent"])

#: Deterministic actions an agent may request; each mirrors a real editor tool.
ACTIONS: dict[str, dict] = {
    "remove_clips_shorter_than": {
        "description": "Delete every clip shorter than min_duration seconds",
        "params": {"min_duration": {"type": "number", "minimum": 0.1}},
    },
    "set_speed": {
        "description": "Set playback speed for a time range",
        "params": {"start": {"type": "number"}, "end": {"type": "number"},
                   "speed": {"type": "number", "minimum": 0.25, "maximum": 4}},
    },
    "highlight_subtitle": {
        "description": "Highlight subtitle words matching a keyword",
        "params": {"keyword": {"type": "string"}, "color": {"type": "string"}},
    },
    "remove_silence": {
        "description": "Jump-cut silences longer than threshold",
        "params": {"minimum_silence": {"type": "number", "minimum": 0.1}},
    },
    "cut_on_beat": {
        "description": "Split the selected clip on the music beats",
        "params": {},
    },
}


@router.get("/tools")
def tools() -> dict:
    """The brain's inventory + the executable actions, as JSON-Schema tools."""
    brain_tools = [
        {"name": f"assess_{t['id']}", "description": f"{t['en']} — {t['when']}",
         "inputSchema": {"type": "object", "properties": {}}}
        for t in editor_brain.TOOLS
    ]
    action_tools = [
        {"name": name, "description": spec["description"],
         "inputSchema": {"type": "object", "properties": spec["params"]}}
        for name, spec in ACTIONS.items()
    ]
    return {"tools": brain_tools + action_tools, "count": len(brain_tools) + len(action_tools)}


_NUM = re.compile(r"(\d+(?:\.\d+)?)")


def parse_nl(command: str) -> dict:
    """A deterministic first pass from natural language to an action.

    Persian and English both. Numbers are pulled from the sentence; a speed like
    «۱.۵x» or "1.5x" is read from the token followed by x/×. When nothing matches,
    the answer says so instead of guessing — an agent that cannot be parsed is a
    no-op, not a wrong edit.
    """
    text = (command or "").lower()
    nums = [float(x) for x in _NUM.findall(text.replace("۱", "1").replace("۲", "2")
             .replace("۳", "3").replace("۴", "4").replace("۵", "5")
             .replace("۰", "0").replace("ٔ", ""))]

    if ("زیر" in text or "shorter" in text or "کوتاه" in text):
        return {"action": "remove_clips_shorter_than",
                "params": {"min_duration": nums[0] if nums else 2.0}}
    if ("سرعت" in text or "speed" in text):
        speed = 1.5
        match = re.search(r"(\d+(?:[.,]\d+)?)\s*[x×]", text)
        if match:
            speed = float(match.group(1).replace(",", "."))
        elif nums:
            speed = nums[0]
        return {"action": "set_speed", "params": {"start": 0, "end": 0, "speed": speed}}
    if ("هایلایت" in text or "highlight" in text):
        keyword = command.split("«")[-1].split("»")[0] if "«" in command else (
            command.split("'")[-1].split("'")[0] if "'" in command else command.split()[-1])
        return {"action": "highlight_subtitle", "params": {"keyword": keyword, "color": "#FF2D9C"}}
    if ("سکوت" in text or "silence" in text):
        return {"action": "remove_silence",
                "params": {"minimum_silence": nums[0] if nums else 0.4}}
    if ("ضرب" in text or "beat" in text):
        return {"action": "cut_on_beat", "params": {}}
    return {"action": None, "params": {}, "note": "could not parse that into an edit action"}


class NLRequest(BaseModel):
    command: str


@router.post("/nl")
def nl(payload: NLRequest) -> dict:
    return parse_nl(payload.command)


class CallRequest(BaseModel):
    action: str
    params: dict = {}


@router.post("/call")
def call(payload: CallRequest) -> dict:
    """Validate an action against the schema; the renderer applies the result."""
    if payload.action not in ACTIONS:
        return {"ok": False, "error": f"unknown action `{payload.action}`"}
    spec = ACTIONS[payload.action]
    params = dict(payload.params)
    for key, meta in spec["params"].items():
        if key in params and meta.get("type") == "number":
            try:
                value = float(params[key])
            except (TypeError, ValueError):
                return {"ok": False, "error": f"`{key}` must be a number"}
            if "minimum" in meta and value < meta["minimum"]:
                value = meta["minimum"]
            if "maximum" in meta and value > meta["maximum"]:
                value = meta["maximum"]
            params[key] = value
    return {"ok": True, "action": payload.action, "params": params,
            "appliedBy": "renderer"}
