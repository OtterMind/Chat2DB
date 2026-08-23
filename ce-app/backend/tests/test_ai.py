"""The AI runtime panel must tell the truth about the machine it is on.

The interesting case is a box with neither Ollama nor faster-whisper: the answer
has to be a clear "not installed", never a crash and never an optimistic tick.
But the *shipped* configuration has faster-whisper present, and two assertions
here used to hard-code its absence — so the suite passed in the sandbox and
would have failed on the machine we actually build. Anything that depends on an
engine being there now asks first.
"""
from __future__ import annotations

import importlib.util

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

WHISPER_PRESENT = importlib.util.find_spec("faster_whisper") is not None


def test_status_reports_both_engines():
    body = client.get("/api/ai/status").json()
    assert set(body) == {"ollama", "whisper"}
    for engine in body.values():
        assert {"name", "installed", "running", "models", "enabled"} <= set(engine)
        assert isinstance(engine["installed"], bool)


def test_status_is_honest_about_what_is_installed():
    body = client.get("/api/ai/status").json()
    # Measured, not guessed from a config flag — in either direction.
    assert body["ollama"]["running"] is False
    assert body["ollama"]["download"].startswith("https://")
    assert body["whisper"]["installed"] is WHISPER_PRESENT


def test_the_self_test_never_crashes_without_the_engines():
    """Regression: a missing `requests` module took the whole endpoint down."""
    body = client.post("/api/ai/test").json()
    assert body["ollama"]["ok"] is False and body["ollama"]["detail"]
    # Whisper may be installed but have no model on disk: either way it answers
    # with a reason, and never with a bare exception.
    assert isinstance(body["whisper"]["ok"], bool) and body["whisper"]["detail"]


def test_pulling_a_model_without_ollama_explains_itself():
    response = client.post("/api/ai/ollama/pull", json={"model": "llama3"})
    assert response.status_code in (409, 501)
    assert "ollama" in response.json()["detail"].lower() or "http client" in response.json()["detail"].lower()


@pytest.mark.skipif(WHISPER_PRESENT, reason="faster-whisper is installed here; this is the missing-engine case")
def test_downloading_whisper_without_the_package_explains_itself():
    response = client.post("/api/ai/whisper/download", json={"size": "base"})
    assert response.status_code == 409
    assert "faster-whisper" in response.json()["detail"]


def test_the_self_test_picks_a_model_that_is_actually_pulled(monkeypatch):
    """Regression: the default was llama3, the machine had qwen2.5 — a 404 that
    read like a broken URL and really meant 'that model is not here'."""
    from app.routers import ai

    monkeypatch.setattr(
        ai,
        "_ollama_state",
        lambda: {
            "name": "Ollama", "installed": True, "running": True,
            "models": ["qwen2.5:7b-instruct-q4_0"], "path": "/usr/bin/ollama",
            "download": ai.OLLAMA_SITE, "selected": "llama3", "enabled": True,
        },
    )

    asked: dict = {}

    class FakeResponse:
        ok = True

        def raise_for_status(self):
            return None

        def json(self):
            return {"response": "ready"}

    class FakeRequests:
        @staticmethod
        def post(url, json=None, timeout=None):
            asked["model"] = (json or {}).get("model")
            return FakeResponse()

    import sys
    import types

    module = types.ModuleType("requests")
    module.post = FakeRequests.post          # type: ignore[attr-defined]
    module.get = lambda *a, **k: FakeResponse()  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "requests", module)

    body = client.post("/api/ai/test").json()
    assert asked["model"] == "qwen2.5:7b-instruct-q4_0", "it asked for a model the machine does not have"
    assert body["ollama"]["ok"] is True


def test_choosing_a_model_is_remembered():
    response = client.post("/api/ai/ollama/select", json={"model": "qwen2.5:7b-instruct-q4_0"})
    assert response.status_code == 200
    from app.config import settings

    assert settings.ollama_model == "qwen2.5:7b-instruct-q4_0"
    assert settings.ollama_enabled is True
