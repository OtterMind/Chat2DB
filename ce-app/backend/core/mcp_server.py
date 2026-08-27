"""Tier 3/4 — a local MCP server over stdio, wrapping the editor brain.

Advisor 2 asked for the brain to be reachable as a protocol so an external agent
(Claude Code, Cursor, Codex) can drive the *real* editor instead of a black-box
render. This is the stdio half of that: a minimal Model-Context-Protocol JSON-RPC
server (`initialize` / `tools/list` / `tools/call`) whose tools call the same
engine functions the FastAPI routers use — one source of truth, two doors.

It runs as `python -m core.mcp_server` and speaks line-delimited JSON-RPC on
stdin/stdout. Nothing here imports a model at startup; every handler imports its
engine lazily, so the server boots instantly on a machine with no GPU and no
torch, and a tool that needs an absent engine reports that instead of crashing.
"""
from __future__ import annotations

import json
import sys

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "cutting-edge", "version": "0.9.41"}


def _tool(name: str, description: str, properties: dict, handler):
    return {
        "name": name,
        "description": description,
        "inputSchema": {"type": "object", "properties": properties},
    }, handler


def _h_assess(args: dict) -> dict:
    from core.brain import editor_brain  # noqa: PLC0415

    return {"decisions": editor_brain.assess(
        args.get("template", {}), args.get("footage", {}), args.get("intent"))}


def _h_parse_nl(args: dict) -> dict:
    from app.routers.agent import parse_nl  # noqa: PLC0415

    return parse_nl(args.get("command", ""))


def _h_dna(args: dict) -> dict:
    from core.engine import dna  # noqa: PLC0415

    return dna.style_dna(args.get("template", {}))


def _h_arc(args: dict) -> dict:
    from core.engine import arc_hook  # noqa: PLC0415

    return arc_hook.emotional_arc(args["path"], float(args.get("fps", 2.0)))


def _h_hook(args: dict) -> dict:
    from core.engine import arc_hook  # noqa: PLC0415

    return arc_hook.hook_score(args["path"], float(args.get("start", 0.0)),
                               float(args.get("end", 3.0)))


def _h_propose(args: dict) -> dict:
    from core.engine import clips_board  # noqa: PLC0415

    return clips_board.propose(args["path"], int(args.get("n", 8)),
                               args.get("persona", "sport"),
                               float(args.get("intensity", 0.5)))


TOOLS: list[dict] = []
_HANDLERS: dict[str, object] = {}


def _register(entry, handler):
    TOOLS.append(entry)
    _HANDLERS[entry["name"]] = handler


_register(*_tool(
    "assess", "The editor brain: one use/skip decision per tool, with reasons.",
    {"template": {"type": "object"}, "footage": {"type": "object"},
     "intent": {"type": "object"}}, _h_assess))
_register(*_tool(
    "parse_nl_command", "Parse a natural-language edit command into an action.",
    {"command": {"type": "string"}}, _h_parse_nl))
_register(*_tool(
    "style_dna", "The compact style fingerprint of a measured template.",
    {"template": {"type": "object"}}, _h_dna))
_register(*_tool(
    "emotional_arc", "The footage as a 0..1 reaction curve.",
    {"path": {"type": "string"}, "fps": {"type": "number"}}, _h_arc))
_register(*_tool(
    "hook_score", "How hard the first seconds grab, 0-100 with reasons.",
    {"path": {"type": "string"}, "start": {"type": "number"},
     "end": {"type": "number"}}, _h_hook))
_register(*_tool(
    "propose_clips", "A board of ranked clip cards with score, hook and reason.",
    {"path": {"type": "string"}, "n": {"type": "number"},
     "persona": {"type": "string"}, "intensity": {"type": "number"}}, _h_propose))


def handle(request: dict) -> dict | None:
    """One JSON-RPC request → one response (None for notifications)."""
    method = request.get("method")
    req_id = request.get("id")
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": req_id, "result": {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": SERVER_INFO,
        }}
    if method in ("notifications/initialized", "initialized"):
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS}}
    if method == "tools/call":
        params = request.get("params") or {}
        name = params.get("name")
        arguments = params.get("arguments") or {}
        handler = _HANDLERS.get(name)
        if handler is None:
            return {"jsonrpc": "2.0", "id": req_id, "error": {
                "code": -32601, "message": f"unknown tool `{name}`"}}
        try:
            result = handler(arguments)
        except Exception as error:  # noqa: BLE001 — a tool failure is a result, not a crash
            return {"jsonrpc": "2.0", "id": req_id, "result": {
                "content": [{"type": "text", "text": f"error: {error}"}],
                "isError": True}}
        return {"jsonrpc": "2.0", "id": req_id, "result": {
            "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
            "isError": False}}
    return {"jsonrpc": "2.0", "id": req_id, "error": {
        "code": -32601, "message": f"method `{method}` not supported"}}


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except ValueError:
            continue
        response = handle(request)
        if response is not None:
            sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
