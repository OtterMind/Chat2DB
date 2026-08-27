"""B8 — the provider channel: other people's code, out of our process.

These tests run a real provider as a real subprocess. What is being proved is not
that JSON can be parsed but the three promises the design makes: a provider that
works is *used*, a provider that is broken, unlicensed or slow is *reported and
ignored*, and nothing about the app changes when no provider is installed.
"""
from __future__ import annotations

import json
import textwrap
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from core.providers import channel as providers

client = TestClient(app)

ECHO = textwrap.dedent('''
    import json, sys
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request = json.loads(line)
        op = request.get("op")
        if op == "init":
            print(json.dumps({"op": op, "ok": True, "capabilities": ["emotion.score", "captions.polish"]}), flush=True)
        elif op == "emotion.score":
            scores = {str(t): 0.9 for t in request["payload"]["times"]}
            print(json.dumps({"op": op, "ok": True, "result": {"scores": scores, "provider": "echo"}}), flush=True)
        elif op == "captions.polish":
            items = [{"text": (i.get("text") or "").strip().upper()} for i in request["payload"]["items"]]
            print(json.dumps({"op": op, "ok": True, "result": {"items": items, "provider": "echo"}}), flush=True)
        elif op == "selftest":
            print(json.dumps({"op": op, "ok": True, "result": {"note": "echo alive"}}), flush=True)
        elif op == "shutdown":
            break
''')

SLOW = textwrap.dedent('''
    import json, sys, time
    for line in sys.stdin:
        request = json.loads(line)
        if request.get("op") == "shutdown":
            break
        time.sleep(30)
''')

MANIFEST = {
    "id": "echo", "name": "Echo provider", "version": "0.1.0", "entry": "run.py",
    "capabilities": ["emotion.score", "captions.polish"], "licence": "MIT",
    "description": "uppercases captions and claims every moment is joyful",
    "runtime": "process",
}


@pytest.fixture()
def shelf(tmp_path, monkeypatch) -> Path:
    """An isolated provider shelf — nothing here touches the real ~/CuttingEdge."""
    monkeypatch.setattr(settings, "cuttingedge_home", str(tmp_path))
    providers.STATUS.clear()
    folder = providers.providers_dir() / "echo"
    folder.mkdir(parents=True)
    (folder / "run.py").write_text(ECHO, encoding="utf-8")
    (folder / "provider.json").write_text(json.dumps(MANIFEST), encoding="utf-8")
    return folder


def _add(folder: Path, name: str, manifest: dict, script: str = ECHO) -> Path:
    target = folder.parent / name
    target.mkdir(parents=True, exist_ok=True)
    (target / "run.py").write_text(script, encoding="utf-8")
    (target / "provider.json").write_text(json.dumps(manifest), encoding="utf-8")
    return target


def test_an_installed_provider_is_found_and_healthy(shelf):
    rows = providers.discover()

    assert len(rows) == 1
    assert rows[0]["id"] == "echo" and rows[0]["licence"] == "MIT"
    assert rows[0]["problems"] == [] and rows[0]["enabled"] is True


def test_the_shelf_reports_why_a_folder_is_not_a_provider(shelf):
    _add(shelf, "broken", {"id": "broken", "name": "Broken", "version": "1", "entry": "run.py",
                           "capabilities": ["not-a-capability"], "runtime": "inprocess"})

    problems = next(r for r in providers.discover() if r["id"] == "broken")["problems"]

    assert any("unknown capabilities" in p for p in problems)
    assert any("licence" in p for p in problems)
    assert any("out of process" in p for p in problems)


def test_a_missing_entry_and_a_missing_manifest_are_reported(shelf):
    (shelf.parent / "empty").mkdir()
    _add(shelf, "ghost", {**MANIFEST, "id": "ghost", "entry": "nope.py"})

    rows = {r["id"]: r for r in providers.discover()}
    assert any("does not exist" in p for p in rows["ghost"]["problems"])
    assert "no provider.json" in rows["empty"]["problems"][0]


def test_the_selftest_starts_the_process(shelf):
    result = providers.selftest("echo")

    assert result["ok"] is True and "alive" in result["note"]


def test_a_capability_reaches_the_provider(shelf):
    answers = providers.hook("emotion.score", {"path": "/tmp/x.mp4", "times": [1.0, 2.5]})

    assert answers and answers[0]["scores"]["1.0"] == 0.9


def test_caption_polishing_goes_through_the_provider(shelf):
    from core.engine import text_polish

    lines, who = text_polish.polish_lines(["hello world", "second line"], "en")

    assert lines == ["HELLO WORLD", "SECOND LINE"]
    assert who == "echo"


def test_without_a_provider_the_builtin_pass_is_untouched(shelf):
    from core.engine import text_polish

    providers.set_enabled("echo", False)
    lines, who = text_polish.polish_lines(["hello   world"], "en")

    # the built-in English pass true-cases the sentence start — that is its job
    assert lines == ["Hello world"]  # whitespace collapsed, case fixed, nothing invented
    assert who == ""


def test_a_provider_that_rewrites_the_text_is_ignored(shelf):
    from core.engine import text_polish

    _add(shelf, "rewriter", {**MANIFEST, "id": "rewriter", "capabilities": ["captions.polish"]},
         textwrap.dedent('''
            import json, sys
            for line in sys.stdin:
                request = json.loads(line)
                if request.get("op") == "captions.polish":
                    items = [{"text": "x" * 400} for _ in request["payload"]["items"]]
                    print(json.dumps({"op": "captions.polish", "ok": True,
                                      "result": {"items": items, "provider": "rewriter"}}), flush=True)
                elif request.get("op") == "shutdown":
                    break
                else:
                    print(json.dumps({"op": request.get("op"), "ok": True, "result": {}}), flush=True)
         '''))
    providers.set_enabled("echo", False)

    lines, who = text_polish.polish_lines(["hi"], "en")

    assert lines == ["Hi"]  # a 400-character "polish" of a two-letter line is a rewrite
    assert who == ""


def test_a_slow_provider_is_reported_and_never_blocks_the_edit(shelf):
    _add(shelf, "slow", {**MANIFEST, "id": "slow", "capabilities": ["emotion.score"]}, SLOW)
    providers.set_enabled("echo", False)

    assert providers.hook("emotion.score", {"path": "/tmp/x.mp4", "times": [1.0]}, timeout=1.0) == []
    assert "timed out" in providers.STATUS["slow"]["note"]


def test_a_provider_that_lies_is_reported(shelf):
    _add(shelf, "junk", {**MANIFEST, "id": "junk", "capabilities": ["emotion.score"]},
         "print('not json at all')\n")
    providers.set_enabled("echo", False)

    assert providers.call("junk", "emotion.score", {"times": [1.0]}) is None
    assert providers.STATUS["junk"]["ok"] is False


def test_a_disabled_provider_is_not_started(shelf):
    providers.set_enabled("echo", False)

    assert providers.hook_providers("emotion.score") == []
    assert providers.call("echo", "emotion.score", {"times": [1.0]}) is None
    providers.set_enabled("echo", True)
    assert providers.hook_providers("emotion.score")[0]["id"] == "echo"


def test_the_api_lists_the_shelf_and_its_contract(shelf):
    body = client.get("/api/providers").json()

    assert body["count"] == 1
    assert {c["id"] for c in body["capabilities"]} >= {"emotion.score", "captions.polish"}
    assert body["providers"][0]["licence"] == "MIT"
    assert body["dir"].endswith("providers")


def test_the_api_can_switch_a_provider_off(shelf):
    assert client.post("/api/providers/enable", json={"id": "echo", "enabled": False}).json() == {
        "id": "echo", "enabled": False}
    assert client.get("/api/providers").json()["providers"][0]["enabled"] is False
    assert client.post("/api/providers/enable", json={"id": "nobody", "enabled": True}).status_code == 404


def test_the_api_runs_the_selftest(shelf):
    body = client.post("/api/providers/test", json={"id": "echo"}).json()

    assert body["ok"] is True


def test_the_brain_mentions_providers_only_when_one_is_installed(shelf):
    from core.brain import editor_brain

    def row(footage):
        return next(x for x in editor_brain.assess({"bpm": 0, "shots": []}, footage, {})
                    if x["tool"] == "providers")

    assert row({"providers": [{"id": "echo", "capabilities": ["emotion.score"]}]})["use"] is True
    assert row({})["use"] is False
    assert "providers" in {t["id"] for t in editor_brain.TOOLS}


def test_denoise_is_offered_only_by_a_provider_that_has_it(shelf):
    from core.brain import editor_brain

    def denoise(capabilities):
        return next(x for x in editor_brain.assess(
            {"bpm": 0, "shots": []}, {"providers": [{"id": "x", "capabilities": capabilities}]}, {})
            if x["tool"] == "denoise")["use"]

    assert denoise(["audio.denoise"]) is True
    assert denoise(["emotion.score"]) is False
    # …and with nothing installed the old honest answer stands
    assert next(x for x in editor_brain.assess({"bpm": 0, "shots": []}, {}, {})
                if x["tool"] == "denoise")["use"] is False
