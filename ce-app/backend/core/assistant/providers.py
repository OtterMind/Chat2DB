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
CHOICES = ("auto", "off", "ollama", "openai", "gemini", "anthropic")

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
    """`(provider, key, model)` — an explicit choice, or the first one set up."""
    if choice == "off":
        return None
    order = ("ollama", "openai", "gemini", "anthropic") if choice == "auto" else (choice,)
    for name in order:
        if name == "ollama" and settings.ollama_enabled:
            return ("ollama", "", settings.ollama_model or "llama3")
        if name == "openai" and settings.openai_api_key:
            return ("openai", settings.openai_api_key, "gpt-4o-mini")
        if name == "gemini" and settings.gemini_api_key:
            return ("gemini", settings.gemini_api_key, "gemini-1.5-flash")
        if name == "anthropic" and settings.anthropic_api_key:
            return ("anthropic", settings.anthropic_api_key, "claude-3-5-haiku-latest")
    return None


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
    return out


def chat(
    messages: list[dict],
    *,
    choice: str = "auto",
    json_mode: bool = False,
    timeout: float = 90.0,
) -> Answer | None:
    """Send a conversation, get one reply. `None` when no provider can answer.

    `messages` is `[{role, content}, ...]` in the OpenAI shape, which all four
    providers accept with a little translation. History is the caller's — this
    module holds no state, so a restart cannot lose half a conversation.
    """
    config = configured(choice)
    if config is None:
        return None
    requests = _requests()
    if requests is None:
        return None

    provider, key, model = config
    began = time.perf_counter()
    try:
        if provider == "ollama":
            response = requests.post(
                f"{OLLAMA_URL}/api/chat",
                json={"model": model, "messages": messages, "stream": False,
                      **({"format": "json"} if json_mode else {})},
                timeout=timeout,
            )
            text = response.json().get("message", {}).get("content")
        elif provider == "openai":
            response = requests.post(
                f"{settings.openai_base_url.rstrip('/')}/chat/completions",
                headers={"Authorization": f"Bearer {key}"},
                json={
                    "model": model, "messages": messages, "temperature": 0.2,
                    **({"response_format": {"type": "json_object"}} if json_mode else {}),
                },
                timeout=timeout,
            )
            text = response.json()["choices"][0]["message"]["content"]
        elif provider == "gemini":
            # Gemini separates the system instruction from the turns.
            system = " ".join(m["content"] for m in messages if m.get("role") == "system")
            turns = [m for m in messages if m.get("role") != "system"]
            contents = [
                {
                    "role": "model" if m["role"] == "assistant" else "user",
                    "parts": [{"text": m["content"]}],
                }
                for m in turns
            ]
            response = requests.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/{model}"
                f":generateContent?key={key}",
                json={
                    **({"systemInstruction": {"parts": [{"text": system}]}} if system else {}),
                    "contents": contents,
                    "generationConfig": {
                        "temperature": 0.2,
                        **({"responseMimeType": "application/json"} if json_mode else {}),
                    },
                },
                timeout=timeout,
            )
            text = response.json()["candidates"][0]["content"]["parts"][0]["text"]
        else:
            system = " ".join(m["content"] for m in messages if m.get("role") == "system")
            turns = [
                {"role": "assistant" if m["role"] == "assistant" else "user", "content": m["content"]}
                for m in messages
                if m.get("role") != "system"
            ]
            response = requests.post(
                "https://api.anthropic.com/v1/messages",
                headers={
                    "x-api-key": key,
                    "anthropic-version": "2023-06-01",
                    "content-type": "application/json",
                },
                json={
                    "model": model, "max_tokens": 1024, "temperature": 0.2,
                    **({"system": system} if system else {}),
                    "messages": turns,
                },
                timeout=timeout,
            )
            text = response.json()["content"][0]["text"]
    except Exception:  # noqa: BLE001 — no model is an answer, not a crash
        return None

    if not text:
        return None
    return Answer(
        text=str(text),
        provider=provider,
        model=model,
        seconds=round(time.perf_counter() - began, 2),
    )


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
