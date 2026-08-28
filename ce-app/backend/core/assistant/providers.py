"""One way to reach a language model, whichever one this machine has.

Every provider is called with plain `requests`. The four official SDKs were
measured, found to be imported nowhere, and deleted in 0.6.1 (STATE.md §4.34) —
a chat call is one POST, and 1.5 MB of wheels that nothing imports is 1.5 MB
every user downloads for nothing.

Two rules this file exists to keep:

1. **A provider is checked, never assumed.** `available()` answers from the
   machine — is Ollama actually answering on 11434, is there a key in Settings —
   because both "the import worked" and "the setting says enabled" have lied
   before, on a user's machine, in a release (STATE.md §4.14).
2. **A missing provider degrades; it does not fail.** Every path here returns
   `None` rather than raising, so a conversation with no model connected still
   answers from what was measured. A lazy `import requests` inside the LLM path
   once turned every prompt into a 500 on a machine without it (§4.51).
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass

from app.config import settings

#: What the user may pick in Settings. `auto` is the first one that is set up.
CHOICES = ("auto", "off", "gateway", "ollama", "openai", "gemini", "anthropic")

#: The OmniRoute-style fallback ladder: a local gateway first (one endpoint in
#: front of many models), then local Ollama, then hosted keys. `chat` walks this
#: ladder on failure, so a dead provider degrades to the next instead of silence.
FALLBACK_ORDER = ("gateway", "ollama", "openai", "gemini", "anthropic")

OLLAMA_URL = "http://127.0.0.1:11434"


@dataclass
class Answer:
    """One reply, and where it came from — the user is told both."""

    text: str
    provider: str
    model: str
    seconds: float

    @property
    def label(self) -> str:
        return f"{self.provider}:{self.model}"


def _requests():
    """Imported lazily and defensively: optional dependencies degrade."""
    try:
        import requests  # noqa: PLC0415

        return requests
    except Exception:  # noqa: BLE001
        return None


def configured(choice: str = "auto") -> tuple[str, str, str] | None:
    """`(provider, key, model)` — an explicit choice, or the first one set up.

    `auto` asks the stored setting first, so the choice made in Settings is the
    choice the chat uses; only when that is also `auto` does it fall back to
    "whichever provider happens to be configured".
    """
    if choice == "off":
        return None
    if choice == "auto":
        choice = (settings.assistant_provider or "auto").strip().lower()
        if choice not in CHOICES:
            choice = "auto"
    if choice == "off":
        return None
    order = FALLBACK_ORDER if choice == "auto" else (choice,)
    for name in order:
        if name == "gateway" and settings.gateway_base_url:
            return ("gateway", settings.gateway_api_key,
                    settings.gateway_model or "default")
        if name == "ollama" and settings.ollama_enabled:
            return ("ollama", "", settings.ollama_model or "llama3")
        if name == "openai" and settings.openai_api_key:
            return ("openai", settings.openai_api_key, "gpt-4o-mini")
        if name == "gemini" and settings.gemini_api_key:
            return ("gemini", settings.gemini_api_key, "gemini-1.5-flash")
        if name == "anthropic" and settings.anthropic_api_key:
            return ("anthropic", settings.anthropic_api_key, "claude-3-5-haiku-latest")
    return None


def candidates(choice: str = "auto") -> list[tuple[str, str, str]]:
    """Every provider that could answer, in fallback order — the resilience ladder.

    An explicit choice still allows the ladder to catch it if it fails at runtime
    (a chosen but dead Ollama should not silence the assistant).
    """
    if choice == "off":
        return []
    if choice == "auto":
        choice = (settings.assistant_provider or "auto").strip().lower()
        if choice not in CHOICES:
            choice = "auto"
    if choice == "off":
        return []
    order = FALLBACK_ORDER if choice == "auto" else (choice,) + tuple(
        p for p in FALLBACK_ORDER if p != choice)
    out: list[tuple[str, str, str]] = []
    for name in order:
        one = configured(name) if name != "auto" else None
        if one:
            out.append(one)
    return out


def available() -> dict[str, dict]:
    """What this machine can use right now, checked rather than read.

    Ollama is asked, with a short budget: a settings flag that says "enabled"
    next to an Ollama that is not running is exactly the confusion §4.14 fixed,
    and the assistant must not promise a conversation it cannot have.
    """
    requests = _requests()
    out: dict[str, dict] = {}

    running = False
    models: list[str] = []
    if requests is not None:
        try:
            found = requests.get(f"{OLLAMA_URL}/api/tags", timeout=0.8).json()
            models = [str(m.get("name", "")) for m in found.get("models", [])]
            running = True
        except Exception:  # noqa: BLE001 — not installed, not running, both fine
            running = False
    out["ollama"] = {
        "ready": running and settings.ollama_enabled,
        "installed": running,
        "enabled": settings.ollama_enabled,
        "models": models,
        "model": settings.ollama_model or "llama3",
    }
    out["openai"] = {"ready": bool(settings.openai_api_key), "model": "gpt-4o-mini"}
    out["gemini"] = {"ready": bool(settings.gemini_api_key), "model": "gemini-1.5-flash"}
    out["anthropic"] = {"ready": bool(settings.anthropic_api_key), "model": "claude-3-5-haiku-latest"}
    out["gateway"] = {
        "ready": bool(settings.gateway_base_url),
        "model": settings.gateway_model or "default",
        "url": settings.gateway_base_url,
    }
    return out


def _dispatch(provider, key, model, messages, json_mode, timeout, requests) -> str | None:
    """One provider call; raise on any failure so the ladder can move on."""
    if provider in ("gateway", "openai"):
        base = (settings.gateway_base_url if provider == "gateway"
                else settings.openai_base_url).rstrip("/")
        headers = {"Authorization": f"Bearer {key}"} if key else {}
        response = requests.post(
            f"{base}/chat/completions",
            headers=headers,
            json={"model": model, "messages": messages, "temperature": 0.2,
                  **({"response_format": {"type": "json_object"}} if json_mode else {})},
            timeout=timeout,
        )
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"]
    if provider == "ollama":
        response = requests.post(
            f"{OLLAMA_URL}/api/chat",
            json={"model": model, "messages": messages, "stream": False,
                  **({"format": "json"} if json_mode else {})},
            timeout=timeout,
        )
        response.raise_for_status()
        return response.json().get("message", {}).get("content")
    if provider == "gemini":
        system = " ".join(m["content"] for m in messages if m.get("role") == "system")
        turns = [m for m in messages if m.get("role") != "system"]
        contents = [{"role": "model" if m["role"] == "assistant" else "user",
                     "parts": [{"text": m["content"]}]} for m in turns]
        response = requests.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}",
            json={**({"systemInstruction": {"parts": [{"text": system}]}} if system else {}),
                  "contents": contents,
                  "generationConfig": {"temperature": 0.2,
                                       **({"responseMimeType": "application/json"} if json_mode else {})}},
            timeout=timeout,
        )
        response.raise_for_status()
        return response.json()["candidates"][0]["content"]["parts"][0]["text"]
    # anthropic
    system = " ".join(m["content"] for m in messages if m.get("role") == "system")
    turns = [{"role": "assistant" if m["role"] == "assistant" else "user", "content": m["content"]}
             for m in messages if m.get("role") != "system"]
    response = requests.post(
        "https://api.anthropic.com/v1/messages",
        headers={"x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json"},
        json={"model": model, "max_tokens": 1024, "temperature": 0.2,
              **({"system": system} if system else {}), "messages": turns},
        timeout=timeout,
    )
    response.raise_for_status()
    return response.json()["content"][0]["text"]


def chat(messages, *, choice="auto", json_mode=False, timeout=90.0):
    """Send a conversation, get one reply; `None` only when *no* provider can answer.

    OmniRoute-style resilience: walk the fallback ladder and let a dead or slow
    provider degrade to the next instead of silencing the assistant.
    """
    requests = _requests()
    if requests is None:
        return None
    began = time.perf_counter()
    for provider, key, model in candidates(choice):
        try:
            text = _dispatch(provider, key, model, messages, json_mode, timeout, requests)
        except Exception:  # noqa: BLE001 — this provider failed; try the next
            continue
        if not text:
            continue
        return Answer(text=str(text), provider=provider, model=model,
                      seconds=round(time.perf_counter() - began, 2))
    return None


def chat_stream(
    messages: list[dict],
    *,
    choice: str = "auto",
    timeout: float = 180.0,
):
    """The same conversation, yielded as it is written.

    Returns a generator of text chunks, or `None` when no provider can answer.
    Streaming is not decoration here: a 7B model on a CPU takes seconds, and
    without it the user stares at three bouncing dots with no evidence that
    anything is happening — which is the same complaint the task stages fixed
    for renders (STATE.md §4.36).

    Every provider speaks a slightly different dialect of "more text, please":
    Ollama answers newline-delimited JSON, the other three answer SSE. A line
    that will not parse is skipped rather than raised, because half a chunk is
    not worth losing the rest of a sentence.
    """
    config = configured(choice)
    if config is None:
        return None
    requests = _requests()
    if requests is None:
        return None

    provider, key, model = config

    def chunks():
        try:
            if provider == "ollama":
                response = requests.post(
                    f"{OLLAMA_URL}/api/chat",
                    json={"model": model, "messages": messages, "stream": True},
                    timeout=timeout, stream=True,
                )
                for line in response.iter_lines():
                    if not line:
                        continue
                    try:
                        payload = json.loads(line)
                    except ValueError:
                        continue
                    piece = (payload.get("message") or {}).get("content")
                    if piece:
                        yield piece
                    if payload.get("done"):
                        return
                return

            if provider == "openai":
                response = requests.post(
                    f"{settings.openai_base_url.rstrip('/')}/chat/completions",
                    headers={"Authorization": f"Bearer {key}"},
                    json={"model": model, "messages": messages, "temperature": 0.2, "stream": True},
                    timeout=timeout, stream=True,
                )
                for line in response.iter_lines():
                    text = line.decode("utf-8", "replace") if isinstance(line, bytes) else str(line or "")
                    if not text.startswith("data:"):
                        continue
                    body = text[5:].strip()
                    if body == "[DONE]":
                        return
                    try:
                        delta = json.loads(body)["choices"][0].get("delta", {})
                    except (ValueError, KeyError, IndexError):
                        continue
                    if delta.get("content"):
                        yield delta["content"]
                return

            if provider == "gemini":
                system = " ".join(m["content"] for m in messages if m.get("role") == "system")
                contents = [
                    {"role": "model" if m["role"] == "assistant" else "user",
                     "parts": [{"text": m["content"]}]}
                    for m in messages if m.get("role") != "system"
                ]
                response = requests.post(
                    f"https://generativelanguage.googleapis.com/v1beta/models/{model}"
                    f":streamGenerateContent?alt=sse&key={key}",
                    json={
                        **({"systemInstruction": {"parts": [{"text": system}]}} if system else {}),
                        "contents": contents,
                        "generationConfig": {"temperature": 0.2},
                    },
                    timeout=timeout, stream=True,
                )
                for line in response.iter_lines():
                    text = line.decode("utf-8", "replace") if isinstance(line, bytes) else str(line or "")
                    if not text.startswith("data:"):
                        continue
                    try:
                        parts = json.loads(text[5:].strip())["candidates"][0]["content"]["parts"]
                    except (ValueError, KeyError, IndexError):
                        continue
                    for part in parts:
                        if part.get("text"):
                            yield part["text"]
                return

            system = " ".join(m["content"] for m in messages if m.get("role") == "system")
            turns = [
                {"role": "assistant" if m["role"] == "assistant" else "user", "content": m["content"]}
                for m in messages if m.get("role") != "system"
            ]
            response = requests.post(
                "https://api.anthropic.com/v1/messages",
                headers={"x-api-key": key, "anthropic-version": "2023-06-01",
                         "content-type": "application/json"},
                json={"model": model, "max_tokens": 1024, "temperature": 0.2, "stream": True,
                      **({"system": system} if system else {}), "messages": turns},
                timeout=timeout, stream=True,
            )
            for line in response.iter_lines():
                text = line.decode("utf-8", "replace") if isinstance(line, bytes) else str(line or "")
                if not text.startswith("data:"):
                    continue
                try:
                    event = json.loads(text[5:].strip())
                except ValueError:
                    continue
                if event.get("type") == "content_block_delta":
                    piece = (event.get("delta") or {}).get("text")
                    if piece:
                        yield piece
                elif event.get("type") in ("message_stop", "error"):
                    return
        except Exception:  # noqa: BLE001 — a dropped stream is an answer that ended
            return

    return chunks()
