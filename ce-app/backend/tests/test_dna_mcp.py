"""Tier 3/4 — Style DNA and the local MCP server.

The DNA tests assert it is a deterministic projection of a measured template. The
MCP tests spawn the real stdio server as a subprocess and speak JSON-RPC to it, so
the protocol that an external agent would use is exercised end to end.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app
from core.engine import dna

client = TestClient(app)

TEMPLATE = {
    "shots": [{"duration": 0.8}, {"duration": 1.5}, {"duration": 3.0}, {"duration": 5.0}],
    "mean_shot": 2.0, "bpm": 120.0, "cuts_on_beat": 0.6, "speech_ratio": 0.5,
    "motion_mix": {"static": 1, "push": 3}, "look": {"temperature": 0.3, "saturation": 1.0},
}


# ------------------------------------------------------------------ DNA


def test_pacing_histogram_counts_and_shares():
    hist = dna.pacing_histogram(TEMPLATE["shots"])
    labels = [b["label"] for b in hist]
    assert labels == ["<1s", "1–2s", "2–4s", "4s+"]
    counts = {b["label"]: b["count"] for b in hist}
    assert counts == {"<1s": 1, "1–2s": 1, "2–4s": 1, "4s+": 1}
    assert round(sum(b["share"] for b in hist), 2) == 1.0


def test_dna_reads_the_look_mood():
    assert dna.style_dna({"look": {"temperature": 0.4}})["mood"] == "warm"
    assert dna.style_dna({"look": {"temperature": -0.4}})["mood"] == "cool"
    assert dna.style_dna({"look": {"saturation": 0.3}})["mood"] == "muted"
    assert dna.style_dna({"look": {"saturation": 1.5}})["mood"] == "vivid"


def test_dna_is_deterministic_and_summarises():
    a = dna.style_dna(TEMPLATE)
    b = dna.style_dna(TEMPLATE)
    assert a == b
    assert a["motion"] == "push"  # dominant motion
    assert "BPM" in a["line"] and "talk" in a["line"]


def test_dna_endpoint_with_template():
    body = client.post("/api/style/dna", json={"template": TEMPLATE}).json()
    assert body["pacing"] and body["line"]


def test_dna_endpoint_requires_an_input():
    assert client.post("/api/style/dna", json={}).status_code == 400


# ------------------------------------------------------------------ MCP server


def _mcp_roundtrip(lines: list[dict]) -> list[dict]:
    backend = Path(__file__).resolve().parents[1]
    proc = subprocess.run(
        [sys.executable, "-m", "core.mcp_server"],
        input="\n".join(json.dumps(l) for l in lines) + "\n",
        capture_output=True, text=True, timeout=60, cwd=str(backend),
    )
    return [json.loads(l) for l in proc.stdout.splitlines() if l.strip()]


def test_mcp_initialize_and_tools_list():
    out = _mcp_roundtrip([
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    ])
    init, listed = out[0], out[1]
    assert init["result"]["serverInfo"]["name"] == "cutting-edge"
    names = {t["name"] for t in listed["result"]["tools"]}
    assert {"assess", "parse_nl_command", "style_dna", "propose_clips"} <= names


def test_mcp_tools_call_parse_and_assess():
    out = _mcp_roundtrip([
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
         "params": {"name": "parse_nl_command", "arguments": {"command": "remove clips shorter than 2s"}}},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
         "params": {"name": "assess", "arguments": {
             "template": {"bpm": 120}, "footage": {"speech_ratio": 0.6}}}},
    ])
    parsed = json.loads(out[0]["result"]["content"][0]["text"])
    assert parsed["action"] == "remove_clips_shorter_than"
    assessed = json.loads(out[1]["result"]["content"][0]["text"])
    assert assessed["decisions"] and all("use" in d for d in assessed["decisions"])


def test_mcp_unknown_tool_is_an_error_not_a_crash():
    out = _mcp_roundtrip([
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
         "params": {"name": "nope", "arguments": {}}},
    ])
    assert out[0]["error"]["code"] == -32601
