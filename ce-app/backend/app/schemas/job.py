from __future__ import annotations
from typing import Any
from pydantic import BaseModel, Field

class JobCreate(BaseModel):
    name: str = Field(..., min_length=1, max_length=200)
    source_url: str = Field(default="")
    source_type: str = Field(default="youtube")
    config: dict[str, Any] | None = None

class JobResponse(BaseModel):
    id: str; name: str; source_url: str | None = None; source_type: str
    status: str; current_stage: str | None = None; progress: float; error: str | None = None
    created_at: str; updated_at: str

class JobListResponse(BaseModel):
    jobs: list[JobResponse]; total: int

class ClipResponse(BaseModel):
    id: str; job_id: str; start_time: float; end_time: float; score: float
    ai_reasoning: str | None = None; status: str; output_path: str | None = None
    thumbnail_path: str | None = None; created_at: str

class ClipUpdate(BaseModel):
    status: str | None = Field(default=None)

class SystemInfo(BaseModel):
    version: str; python_version: str; platform: str
    ffmpeg_found: bool; ffmpeg_path: str | None = None
    cuda_available: bool; cuda_version: str | None = None
    disk_free_gb: float; memory_gb: float