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
    heavy: bool = Field(default=False,
                        description="also fetch the heavy runtime (torch) when the engine needs it")


@router.post("/install/start")
def install_start(payload: InstallRequest) -> dict:
    engine = next((e for e in engines.ENGINES if e["id"] == payload.engine), None)
    if engine is None:
        raise HTTPException(status_code=404, detail=f"unknown engine {payload.engine}")
    if any(payload.engine == r["name"].lower() for r in engines.REJECTED):
        raise HTTPException(status_code=403, detail="this engine is rejected by the licence gate")
    if not engine["deps"]:
        raise HTTPException(status_code=409, detail=f"{engine['name']} has no pip package; see its repo")

    probe = engines.probe(engine)
    if not probe["fetchable"]:
        raise HTTPException(status_code=409, detail=probe["why"])

    deps = list(engine["deps"])
    if engine.get("heavy") in ("torch", "torch+HF-token"):
        if not payload.heavy:
            raise HTTPException(
                status_code=409,
                detail=f"{engine['name']} needs torch to run. Re-send with heavy=true "
                       "to fetch it too (~120 MB CPU wheels).")
        deps += engines.HEAVY_DEPS[engine["heavy"]]

    def work(reporter) -> dict:
        return runtime_packages.install(
            deps,
            on_progress=lambda stage, fraction, label="": reporter.stage(stage, fraction, label),
        )

    return tasks.start(f"engine:{engine['id']}", work).as_dict()


@router.get("/install-all/plan")
def install_all_plan() -> dict:
    """What the one-click button would fetch, before the user commits to it."""
    plan = engines.bulk_install_plan()
    return {"engines": plan["ids"], "deps": plan["deps"], "count": len(plan["ids"])}


@router.post("/install-all/start")
def install_all_start() -> dict:
    """One button: torch once, then every fetchable engine — no one-by-one clicks."""
    plan = engines.bulk_install_plan()
    if not plan["deps"]:
        raise HTTPException(status_code=409, detail="nothing to install")

    def work(reporter) -> dict:
        result = runtime_packages.install(
            plan["deps"],
            on_progress=lambda stage, fraction, label="": reporter.stage(stage, fraction, label),
        )
        # Reflect reality: which engines are importable now that the dust settles?
        runtime_packages.ensure_on_path()
        result["now_available"] = [
            eid for eid in plan["ids"]
            if next((e for e in engines.ENGINES if e["id"] == eid), None)
            and runtime_packages.is_installed(
                next(e for e in engines.ENGINES if e["id"] == eid)["module"])
        ]
        return result

    return tasks.start("engine:all", work).as_dict()


# ------------------------------------------------------- TransNetV2 / junctions


class TransnetDetectRequest(BaseModel):
    path: str


@router.post("/transnet/detect")
async def transnet_detect(payload: TransnetDetectRequest) -> dict:
    """Shot segments (TransNetV2 when fetched) + cut/dissolve/fade typing of each
    junction — the typing is our own pixel measurement and works without it."""
    from pathlib import Path as _Path

    from core.engine import transnet

    if not _Path(payload.path).exists():
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}")
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, transnet.detect, payload.path)


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
