"""Project persistence.

A project is a single JSON document (`.ceproj`) holding the edit model. Media is
referenced by absolute path and never copied, so saving is instant and a project
file stays a few kilobytes no matter how much footage it describes.
"""
from __future__ import annotations

import json
import re
import time
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.config import settings

router = APIRouter(prefix="/api/projects", tags=["projects"])

FORMAT_VERSION = 1
AUTOSAVE_NAME = "__autosave__"


def projects_dir() -> Path:
    path = Path(settings.cuttingedge_home) / "projects"
    path.mkdir(parents=True, exist_ok=True)
    return path


def safe_name(name: str) -> str:
    cleaned = re.sub(r"[^\w\s\-()\u0600-\u06FF]", "", name, flags=re.UNICODE).strip()
    return (cleaned or "untitled")[:80]


def project_path(name: str) -> Path:
    return projects_dir() / f"{safe_name(name)}.ceproj"


class ProjectPayload(BaseModel):
    name: str = Field(default="Untitled")
    timeline: dict
    #: Free-form editor state (zoom, selection) that should survive a reload.
    view: dict = Field(default_factory=dict)


def _summary(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"name": path.stem, "path": str(path), "broken": True}

    timeline = data.get("timeline", {})
    clips = timeline.get("clips", [])
    duration = max((c.get("start", 0) + c.get("duration", 0) for c in clips), default=0.0)
    missing = [c.get("src") for c in clips if c.get("src") and not Path(c["src"]).exists()]
    return {
        "name": data.get("name", path.stem),
        "path": str(path),
        "updatedAt": data.get("updatedAt", path.stat().st_mtime),
        "clips": len(clips),
        "duration": round(duration, 2),
        "missingMedia": missing[:5],
        "broken": False,
    }


@router.get("")
def list_projects() -> dict:
    # st_mtime alone ties when two projects are saved in the same second (which a
    # test, or a fast user, does hit); the document carries a finer timestamp.
    def saved_at(path: Path) -> float:
        try:
            with path.open("r", encoding="utf-8") as handle:
                return float(json.load(handle).get("updatedAt") or path.stat().st_mtime)
        except (OSError, ValueError, json.JSONDecodeError):
            return path.stat().st_mtime

    files = sorted(projects_dir().glob("*.ceproj"), key=saved_at, reverse=True)
    return {
        "projects": [_summary(f) for f in files if f.stem != AUTOSAVE_NAME],
        "hasAutosave": project_path(AUTOSAVE_NAME).exists(),
    }


@router.post("")
def save_project(payload: ProjectPayload) -> dict:
    path = project_path(payload.name)
    document = {
        "format": FORMAT_VERSION,
        "name": payload.name,
        "updatedAt": time.time(),
        "timeline": payload.timeline,
        "view": payload.view,
    }
    path.write_text(json.dumps(document, ensure_ascii=False, indent=1), encoding="utf-8")
    return _summary(path)


@router.get("/autosave")
def load_autosave() -> dict:
    path = project_path(AUTOSAVE_NAME)
    if not path.exists():
        raise HTTPException(status_code=404, detail="No autosave")
    return json.loads(path.read_text(encoding="utf-8"))


@router.post("/autosave")
def write_autosave(payload: ProjectPayload) -> dict:
    path = project_path(AUTOSAVE_NAME)
    path.write_text(
        json.dumps(
            {
                "format": FORMAT_VERSION,
                "name": payload.name,
                "updatedAt": time.time(),
                "timeline": payload.timeline,
                "view": payload.view,
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    return {"savedAt": time.time()}


@router.get("/{name}")
def load_project(name: str) -> dict:
    path = project_path(name)
    if not path.exists():
        raise HTTPException(status_code=404, detail=f"No project called {name}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=422, detail=f"Project file is damaged: {exc}") from exc

    # Report media that has moved rather than failing to open the project.
    clips = data.get("timeline", {}).get("clips", [])
    data["missingMedia"] = [c.get("src") for c in clips if c.get("src") and not Path(c["src"]).exists()]
    return data


@router.delete("/{name}")
def delete_project(name: str) -> dict:
    path = project_path(name)
    if path.exists():
        path.unlink()
    return {"deleted": safe_name(name)}
