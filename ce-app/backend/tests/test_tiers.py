"""Tiers 1–3 — transcript editing, jump cut, arc/hook, clips board, markers,
export pack, cut inspector, and the agent surface.

Measured on synthetic media built here: a talking clip, a clip with a crowd burst
(video+audio so the motion curve has something to read), and a plain video. The
assertions are about *separation* and *complement* (keep+cut == duration), and about
endpoints returning the numbers plus the terms that produced them.
"""
from __future__ import annotations

import subprocess
import wave
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import app
from core.engine import arc_hook, clips_board, export_pack, transcript_edit
from core.engine.compose import ffmpeg_binary

client = TestClient(app)
SR = 16_000


def _write_wav(path: Path, samples: np.ndarray) -> Path:
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
    return path


def _voice(seconds=8.0) -> np.ndarray:
    t = np.arange(int(seconds * SR)) / SR
    tone = sum(g * np.sin(2 * np.pi * 180 * k * t)
               for k, g in zip(range(1, 7), [1, .6, .4, .3, .2, .15])) / 2.5
    return tone * 0.5 * (0.6 + 0.4 * np.sin(2 * np.pi * 4 * t))


def _crowd(seconds=2.0) -> np.ndarray:
    t = np.arange(int(seconds * SR)) / SR
    noise = np.random.default_rng(11).standard_normal(t.size)
    return np.diff(noise, prepend=0.0) * (0.55 + 0.45 * np.sin(2 * np.pi * 7 * t)) * 0.9


@pytest.fixture(scope="module")
def burst_mp4(tmp_path_factory) -> Path:
    """A moving picture with a crowd burst at 4–6 s — for arc/hook/markers."""
    base = tmp_path_factory.mktemp("tiers")
    wav = _write_wav(base / "a.wav", np.concatenate([
        _voice(4.0), _crowd(2.0), _voice(2.0)]))
    out = base / "burst.mp4"
    subprocess.run([
        ffmpeg_binary(), "-y", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", "testsrc=size=320x180:rate=25:duration=8",
        "-i", str(wav), "-map", "0:v", "-map", "1:a",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", str(out),
    ], check=True)
    return out


# ------------------------------------------------------------------ Tier 1


WORDS = [
    {"start": 0.0, "end": 0.5, "word": "hello"},
    {"start": 0.6, "end": 0.9, "word": "um"},
    {"start": 1.0, "end": 1.5, "word": "world"},
    {"start": 1.6, "end": 2.0, "word": "یعنی"},
    {"start": 2.1, "end": 2.6, "word": "goodbye"},
]


def test_delete_word_span_becomes_a_cut_range():
    cuts = transcript_edit.ranges_from_words(WORDS, [[1, 1]])
    assert len(cuts) == 1
    assert cuts[0][0] == pytest.approx(0.6 - 0.08, abs=1e-6)
    assert cuts[0][1] == pytest.approx(0.9 + 0.08, abs=1e-6)


def test_stale_span_is_ignored_not_fatal():
    assert transcript_edit.ranges_from_words(WORDS, [[50, 60]]) == []


def test_filler_ranges_find_en_and_fa_fillers():
    cuts = transcript_edit.filler_ranges(WORDS)
    # "um" and "یعنی" are fillers; real words are not
    assert len(cuts) == 2


def test_jumpcut_keep_and_cut_are_exact_complements():
    silences = [{"start": 3.0, "end": 4.0}]
    out = transcript_edit.jumpcut(WORDS, silences, minimum_silence=0.4)
    total = out["duration"]
    kept = sum(b - a for a, b in out["keep"])
    removed = sum(b - a for a, b in out["cuts"])
    assert kept + removed == pytest.approx(total, abs=1e-3)
    assert out["removed"] > 0


def test_jumpcut_endpoint_returns_keep_and_cut():
    body = client.post("/api/transcript/jumpcut", json={
        "words": WORDS, "silences": [{"start": 3.0, "end": 4.0}]}).json()
    assert "keep" in body and "cuts" in body and body["duration"] > 0


def test_transcript_cuts_endpoint():
    body = client.post("/api/transcript/cuts", json={"words": WORDS, "spans": [[0, 1]]}).json()
    assert len(body["cuts"]) == 1 and body["removed"] > 0


# ------------------------------------------------------------------ Tier 1/2 arc + hook


def test_emotional_arc_returns_points_and_terms(burst_mp4):
    arc = arc_hook.emotional_arc(str(burst_mp4), fps=2.0)
    assert arc["points"], "an 8 s clip must produce a curve"
    assert all(0.0 <= p["score"] <= 1.0 for p in arc["points"])
    assert "motion" in arc["terms"]  # video present
    assert arc["duration"] == pytest.approx(8.0, abs=0.6)


def test_emotional_arc_peaks_at_the_burst(burst_mp4):
    arc = arc_hook.emotional_arc(str(burst_mp4), fps=2.0)
    peak = max(arc["points"], key=lambda p: p["score"])
    assert 3.0 <= peak["t"] <= 6.5  # the crowd burst window


def test_hook_score_is_a_labeled_hundred(burst_mp4):
    hook = arc_hook.hook_score(str(burst_mp4), 0.0, 3.0)
    assert 0 <= hook["score"] <= 100
    assert hook["label"] and hook["color"].startswith("#")
    assert isinstance(hook["reasons"], list)


def test_hook_endpoint(burst_mp4):
    body = client.post("/api/board/hook", json={"path": str(burst_mp4)}).json()
    assert "score" in body and "label" in body


def test_arc_endpoint(burst_mp4):
    body = client.post("/api/board/arc", json={"path": str(burst_mp4)}).json()
    assert body["points"]


# ------------------------------------------------------------------ Tier 2 board + markers


def test_propose_returns_ranked_cards_with_reasons(burst_mp4):
    board = clips_board.propose(str(burst_mp4), n=4, persona="sport")
    assert board["cards"]
    for card in board["cards"]:
        assert card["reason"] and 0 <= card["hook"] <= 100
    scores = [c["score"] + c["hook"] / 100 for c in board["cards"]]
    assert scores == sorted(scores, reverse=True)


def test_sports_markers_find_the_crowd_burst(burst_mp4):
    markers = clips_board.sports_markers(str(burst_mp4))["markers"]
    crowd = [m for m in markers if m["type"] == "crowd"]
    assert crowd, "the 4–6 s applause must produce a crowd marker"
    assert any(3.5 <= m["t"] <= 6.5 for m in crowd)
    assert all(0.0 <= m["conf"] <= 1.0 for m in markers)


def test_export_pack_writes_the_deliverables(tmp_path, burst_mp4):
    cues = [{"text": "hello", "start": 0.0, "end": 1.0}]
    pack = export_pack.build_pack(
        str(burst_mp4), str(tmp_path / "pack"), cues=cues,
        meta={"name": "test", "hook": 70, "hookLabel": "⚡ Strong",
              "reasons": ["the crowd reacts"]},
        chapters=[{"t": 4, "title": "the point"}], name="final")
    names = set(pack["files"])
    assert "final.mp4" in names
    assert "final.srt" in names
    assert "description.md" in names
    assert "meta.json" in names
    md = (Path(pack["dir"]) / "description.md").read_text(encoding="utf-8")
    assert "the point" in md and "Strong" in md


# ------------------------------------------------------------------ cut inspector


def test_explain_cut_returns_the_terms():
    body = client.post("/api/board/explain-cut", json={
        "start": 1.0, "end": 3.0, "duration": 10.0,
        "beats": [1.0, 2.0, 3.0], "speech": [[1.0, 3.0]]}).json()
    assert "terms" in body and "headline" in body
    assert body["total"] >= 0.0


# ------------------------------------------------------------------ Tier 3 agent


def test_agent_tools_include_brain_and_actions():
    body = client.get("/api/agent/tools").json()
    names = {t["name"] for t in body["tools"]}
    assert "remove_clips_shorter_than" in names
    assert any(n.startswith("assess_") for n in names)
    assert body["count"] == len(body["tools"])


@pytest.mark.parametrize("command,action", [
    ("برش‌های زیر ۲ ثانیه رو حذف کن", "remove_clips_shorter_than"),
    ("remove clips shorter than 2 seconds", "remove_clips_shorter_than"),
    ("سرعت بخش اول رو 1.5x کن", "set_speed"),
    ("highlight the word 'تنش'", "highlight_subtitle"),
    ("حذف سکوت‌های بلند", "remove_silence"),
    ("ببر روی ضرب", "cut_on_beat"),
])
def test_nl_parse_fa_and_en(command, action):
    from app.routers.agent import parse_nl
    assert parse_nl(command)["action"] == action


def test_nl_speed_reads_the_multiplier():
    from app.routers.agent import parse_nl
    assert parse_nl("speed 2x")["params"]["speed"] == 2.0


def test_nl_unparseable_is_a_noop_not_a_guess():
    from app.routers.agent import parse_nl
    assert parse_nl("blah blah")["action"] is None


def test_agent_call_clamps_and_validates():
    ok = client.post("/api/agent/call", json={
        "action": "set_speed", "params": {"start": 0, "end": 5, "speed": 99}}).json()
    assert ok["ok"] is True and ok["params"]["speed"] == 4.0  # clamped to schema max
    bad = client.post("/api/agent/call", json={"action": "nope", "params": {}}).json()
    assert bad["ok"] is False


def test_the_brain_offers_the_new_tools():
    from core.brain import editor_brain

    ids = {t["id"] for t in editor_brain.TOOLS}
    assert {"text_based_edit", "jump_cut", "hook_lab", "batch_clips",
            "export_pack", "sports_markers", "agent_tools"} <= ids
    assessment = editor_brain.assess(
        {"bpm": 0, "shots": []}, {"speech_ratio": 0.6, "action": 0.7, "duration": 120}, {"kind": "sport"})
    assert len(assessment) == len(editor_brain.TOOLS)
    by = {a["tool"]: a for a in assessment}
    assert by["batch_clips"]["use"] is True     # 120 s holds several shorts
    assert by["sports_markers"]["use"] is True  # sport action
    assert by["text_based_edit"]["use"] is True


# ------------------------------------------------------------------ hook lab + intensity


def test_hook_lab_returns_five_distinct_variants(burst_mp4):
    lab = clips_board.hook_lab(str(burst_mp4))
    kinds = {v["kind"] for v in lab["variants"]}
    assert len(lab["variants"]) == 5
    assert {"zoom-punch", "jump-in", "text-card", "reverse-tease", "reaction"} <= kinds
    for v in lab["variants"]:
        assert 0 <= v["hook"] <= 100 and v["params"]


def test_hook_lab_endpoint(burst_mp4):
    body = client.post("/api/board/hook-lab", json={"path": str(burst_mp4)}).json()
    assert body["variants"] and body["base"] is not None


def test_intensity_pops_captions_and_punches_video():
    from core.engine.style import _apply_intensity

    clips = [
        {"text": "hi", "props": {}},
        {"src": "/x.mp4", "duration": 2.0, "props": {}},
    ]
    hot = _apply_intensity(clips, 0.9)
    cold = _apply_intensity(clips, 0.1)
    assert hot[0]["props"]["animateWords"] is True
    assert hot[1]["keyframes"], "high intensity adds a zoom punch"
    assert cold[0]["props"]["animateWords"] is False
    assert not cold[1].get("keyframes")


def test_intensity_never_moves_a_cut():
    from core.engine.style import _apply_intensity

    clips = [{"src": "/x.mp4", "start": 1.0, "duration": 2.0, "offset": 1.0, "props": {}}]
    hot = _apply_intensity(clips, 1.0)
    assert hot[0]["start"] == 1.0 and hot[0]["duration"] == 2.0 and hot[0]["offset"] == 1.0


def test_propose_intensity_does_not_crash_and_returns_cards(burst_mp4):
    hot = clips_board.propose(str(burst_mp4), n=4, intensity=0.9)
    cold = clips_board.propose(str(burst_mp4), n=4, intensity=0.1)
    assert isinstance(hot["cards"], list) and isinstance(cold["cards"], list)
