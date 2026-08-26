"""The brain's feedback door: decisions become a prior, never evidence."""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel, Field

from core.brain import memory

router = APIRouter(prefix="/api/brain", tags=["brain"])


class FeedbackRequest(BaseModel):
    outcome: str = Field(description="'accepted' or 'rejected'")
    terms: dict | None = Field(default=None,
                               description="the winning plan's term breakdown")


@router.post("/feedback")
def feedback(payload: FeedbackRequest) -> dict:
    """One decision in, the bounded prior out — the taste loop, made visible."""
    memory.record(payload.outcome, payload.terms)
    return {"prior": memory.prior()}
