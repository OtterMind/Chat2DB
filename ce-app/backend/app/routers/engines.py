"""On-demand engines + OTIO interchange, surfaced to the UI."""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core import runtime_packages
from core.engine import engines, interchange
from core.tasks import tasks

router = APIRouter(prefix="/api/engines", tags=["engines"])


@router.get("/status")
def status() -> dict:
    """Every accepted engine with its licence and whether it is fetched, plus the
    rejected set with reasons — the licence gate as a readable list."""
    return engines.status()


class InstallRequest(BaseModel):
    engine: str = Field(description="an engine id from /api/engines/status")


@router.post("/install/start")
def install_start(payload: InstallRequest) -> dict:
    engine = next((e for e in engines.ENGINES if e["id"] == payload.engine), None)
    if engine is None:
        raise HTTPException(status_code=404, detail=f"unknown engine {payload.engine}")
    if any(payload.engine == r["name"].lower() for r in engines.REJECTED):
        raise HTTPException(status_code=403, detail="this engine is rejected by the licence gate")
    if not engine["deps"]:
        raise HTTPException(status_code=409, detail=f"{engine['name']} has no pip package; see its repo")

    def work(reporter) -> dict:
        return runtime_packages.install(
            engine["deps"],
            on_progress=lambda stage, fraction, label="": reporter.stage(stage, fraction, label),
        )

    return tasks.start(f"engine:{engine['id']}", work).as_dict()


# ---------------------------------------------------------------- OTIO


class OtioExportRequest(BaseModel):
    timeline: dict
    path: str
    name: str = "Cutting Edge"


class OtioImportRequest(BaseModel):
    path: str


@router.post("/otio/export")
async def otio_export(payload: OtioExportRequest) -> dict:
    loop = asyncio.get_running_loop()
    try:
        written = await loop.run_in_executor(
            None, interchange.export_otio, payload.timeline, payload.path, payload.name
        )
        return {"path": written}
    except interchange.OtioNotInstalled as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@router.post("/otio/import")
async def otio_import(payload: OtioImportRequest) -> dict:
    loop = asyncio.get_running_loop()
    try:
        return await loop.run_in_executor(None, interchange.import_otio, payload.path)
    except interchange.OtioNotInstalled as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
