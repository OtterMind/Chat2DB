"""The sound shelf: is it configured, and what does a search return."""
from __future__ import annotations

import asyncio

from fastapi import APIRouter
from pydantic import BaseModel, Field

from core.engine import sounds

router = APIRouter(prefix="/api/sounds", tags=["sounds"])


@router.get("/status")
def status() -> dict:
    return sounds.status()


class SearchRequest(BaseModel):
    query: str = Field(min_length=1)


@router.get("/search")
async def search(query: str) -> dict:
    loop = asyncio.get_running_loop()
    return {"results": await loop.run_in_executor(None, sounds.search, query)}
