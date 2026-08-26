"""Assistant endpoints — a conversation, and the plans that come out of it."""
from __future__ import annotations

import asyncio
import json

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.config import settings
from core.assistant import chat, planner, providers

router = APIRouter(prefix="/api/assistant", tags=["assistant"])


class PlanRequest(BaseModel):
    prompt: str
    timeline: dict = Field(default_factory=dict)
    selected_clip_id: str | None = None
    prefer_llm: bool = True


@router.post("/plan")
def plan(payload: PlanRequest) -> dict:
    result = planner.make_plan(payload.prompt, payload.timeline, prefer_llm=payload.prefer_llm)
    # The editor decides what "the selected clip" means; we only pass it through.
    for op in result.ops:
        op.setdefault("clipId", payload.selected_clip_id)
    # A free-form prompt cannot be scored, so it gets the other kind of safety:
    # the plan is described in the user's own language and applied only after
    # they say yes (BRAIN_DESIGN.md §7).
    payload_out = result.as_dict()
    payload_out["preview"] = planner.describe_ops(result.ops)
    return payload_out


class ChatRequest(BaseModel):
    """One turn. The client keeps the history; the server keeps no state."""

    messages: list[dict] = Field(default_factory=list, description="[{role, content}, ...], oldest first")
    timeline: dict = Field(default_factory=dict)
    selected_clip_id: str | None = None
    language: str = Field(default="en", description="'en' or 'fa' — the reply and the steps follow it")
    provider: str = Field(default="auto", description="auto | off | ollama | openai | gemini | anthropic")
    intent: dict | None = Field(
        default=None,
        description="What the video is for, as answered on the Style Match card",
    )


@router.post("/chat")
async def converse(payload: ChatRequest) -> dict:
    """Answer one turn, and say where the answer came from.

    `async def` plus an executor, not a plain `def`: a model on a CPU can think
    for a minute, and this is the same event loop that delivers task progress and
    answers `/api/health`. Four endpoints once ran work on the loop and the whole
    app went quiet while they did (STATE.md §4.38).
    """
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        None, chat.reply, payload.messages, payload.timeline,
        payload.selected_clip_id, payload.language, payload.provider, payload.intent,
    )


@router.post("/chat/stream")
async def converse_stream(payload: ChatRequest) -> StreamingResponse:
    """The same turn, delivered as it happens — newline-delimited JSON.

    A model on a CPU takes seconds, and three bouncing dots are not evidence
    that anything is happening. So the steps go out as they happen and the words
    go out as they are written. The generator runs in a worker thread and hands
    its events to a queue: a blocked event loop would freeze the very progress
    this endpoint exists to show, which is the failure STATE.md §4.38 documents.

    NDJSON rather than SSE on purpose — one `fetch` and a line split is all the
    client needs, and it survives the dev proxy without a special content type.
    """
    loop = asyncio.get_running_loop()
    queue: asyncio.Queue = asyncio.Queue()

    def pump() -> None:
        try:
            for event in chat.reply_stream(
                payload.messages, payload.timeline,
                payload.selected_clip_id, payload.language, payload.provider, payload.intent,
            ):
                asyncio.run_coroutine_threadsafe(queue.put(event), loop)
        except Exception as error:  # noqa: BLE001 — the stream must end, not hang
            asyncio.run_coroutine_threadsafe(
                queue.put({"kind": "error", "message": str(error)}), loop
            )
        finally:
            asyncio.run_coroutine_threadsafe(queue.put(None), loop)

    loop.run_in_executor(None, pump)

    async def body():
        while True:
            event = await queue.get()
            if event is None:
                break
            yield json.dumps(event, ensure_ascii=False) + "\n"

    return StreamingResponse(
        body(),
        media_type="application/x-ndjson",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.get("/providers")
def provider_choices() -> dict:
    """Which models this machine can actually use, checked rather than assumed."""
    return {
        "choices": list(providers.CHOICES),
        "available": providers.available(),
        "selected": settings.assistant_provider,
    }


class ProviderChoice(BaseModel):
    provider: str = Field(description="auto | off | ollama | openai | gemini | anthropic")


@router.post("/provider")
def choose_provider(payload: ProviderChoice) -> dict:
    """Remember which model answers — in `~/CuttingEdge/config.json`.

    Written the same way `/api/ai/ollama/select` writes its choice, because an
    assistant setting that vanishes on restart is a setting the user will not
    trust twice. An unknown name is refused rather than stored: a typo in a
    select box must not quietly turn the assistant off.
    """
    choice = payload.provider.strip().lower()
    if choice not in providers.CHOICES:
        raise HTTPException(status_code=422, detail=f"Not a provider I know: {payload.provider}")

    settings.assistant_provider = choice
    try:
        import json

        from app.config import CONFIG_PATH

        existing = {}
        if CONFIG_PATH.exists():
            existing = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        existing["assistant_provider"] = choice
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(existing, indent=2), encoding="utf-8")
    except Exception:  # noqa: BLE001 — the choice still applies to this session
        pass
    return {"provider": choice}


@router.get("/capabilities")
def capabilities() -> dict:
    provider = planner._provider_config()  # noqa: SLF001 — deliberate, single source
    return {
        "operations": planner.OPERATIONS,
        "provider": provider[0] if provider else None,
        "offlineRules": True,
    }
