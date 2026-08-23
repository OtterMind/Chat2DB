"""Assistant endpoint — turns a sentence into timeline operations."""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel, Field

from core.assistant import planner

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


@router.get("/capabilities")
def capabilities() -> dict:
    provider = planner._provider_config()  # noqa: SLF001 — deliberate, single source
    return {
        "operations": planner.OPERATIONS,
        "provider": provider[0] if provider else None,
        "offlineRules": True,
    }
