"""Assistant endpoints — a conversation, and the plans that come out of it."""
from __future__ import annotations

import asyncio

from fastapi import APIRouter
from pydantic import BaseModel, Field

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
        payload.selected_clip_id, payload.language, payload.provider,
    )


@router.get("/providers")
def provider_choices() -> dict:
    """Which models this machine can actually use, checked rather than assumed."""
    return {"choices": list(providers.CHOICES), "available": providers.available()}


@router.get("/capabilities")
def capabilities() -> dict:
    provider = planner._provider_config()  # noqa: SLF001 — deliberate, single source
    return {
        "operations": planner.OPERATIONS,
        "provider": provider[0] if provider else None,
        "offlineRules": True,
    }
