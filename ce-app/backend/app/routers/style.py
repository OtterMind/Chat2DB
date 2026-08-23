"""Style templates: analyse a reference video, then rebuild footage in its shape.

Two long operations, both in worker threads: decoding and measuring a whole video
is seconds of CPU and the event loop has a WebSocket to keep answering.
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core.engine import style

router = APIRouter(prefix="/api/style", tags=["style"])


class AnalyseRequest(BaseModel):
    path: str
    name: str | None = None
    save: bool = True


class ApplyRequest(BaseModel):
    path: str = Field(description="The user's own footage")
    template: str | None = Field(default=None, description="Saved template name")
    inline: dict | None = Field(default=None, description="A template document, instead of a saved one")
    name: str = "Styled edit"


@router.post("/analyze")
async def analyse(payload: AnalyseRequest) -> dict:
    loop = asyncio.get_running_loop()
    try:
        template = await loop.run_in_executor(None, style.analyse, payload.path, payload.name)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
    except Exception as error:  # noqa: BLE001 - surfaced to the user, not swallowed
        raise HTTPException(status_code=422, detail=str(error)) from error

    if payload.save:
        style.save_template(template)
    return template.as_dict()


@router.get("/templates")
def templates() -> dict:
    return {"templates": style.list_templates()}


@router.get("/templates/{name}")
def read_template(name: str) -> dict:
    try:
        return style.load_template(name)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"No template called {name}") from error


@router.delete("/templates/{name}")
def remove_template(name: str) -> dict:
    style.delete_template(name)
    return {"deleted": name}


@router.post("/apply")
async def apply(payload: ApplyRequest) -> dict:
    """Cut the user's footage into the template and return an editor document."""
    document = payload.inline
    if document is None:
        if not payload.template:
            raise HTTPException(status_code=422, detail="Give a template name or an inline template")
        try:
            document = style.load_template(payload.template)
        except FileNotFoundError as error:
            raise HTTPException(status_code=404, detail=f"No template called {payload.template}") from error

    loop = asyncio.get_running_loop()
    try:
        return await loop.run_in_executor(
            None, style.build_timeline, document, payload.path, payload.name
        )
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
    except Exception as error:  # noqa: BLE001
        raise HTTPException(status_code=422, detail=str(error)) from error
