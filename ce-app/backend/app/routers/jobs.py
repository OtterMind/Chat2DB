from __future__ import annotations
import asyncio, json, uuid
from datetime import datetime
from fastapi import APIRouter, HTTPException
from app.database import db
from app.schemas.job import JobCreate, JobListResponse, JobResponse, ClipResponse
from app.services.pipeline import PipelineOrchestrator

router = APIRouter(prefix="/api/jobs", tags=["jobs"])

@router.post("", response_model=JobResponse, status_code=201)
def create_job(data: JobCreate):
    job_id = str(uuid.uuid4()); now = datetime.utcnow().isoformat()
    config_data = data.config or {}
    config_data.setdefault("clips_count", 5)
    config_data.setdefault("ratio", "9:16")
    conn = db.connect()
    conn.execute("INSERT INTO jobs (id, name, source_url, source_type, status, config, created_at, updated_at) VALUES (?,?,?,?,'pending',?,?,?)",
                 (job_id, data.name, data.source_url, data.source_type, json.dumps(config_data), now, now))
    conn.commit()
    row = conn.execute("SELECT * FROM jobs WHERE id = ?",(job_id,)).fetchone()
    return JobResponse(**dict(row))

@router.get("", response_model=JobListResponse)
def list_jobs(page: int = 1, per_page: int = 20):
    conn = db.connect(); offset = (page-1)*per_page
    rows = conn.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT ? OFFSET ?",(per_page, offset)).fetchall()
    total = conn.execute("SELECT COUNT(*) FROM jobs").fetchone()[0]
    return JobListResponse(jobs=[JobResponse(**dict(r)) for r in rows], total=total)

@router.get("/{job_id}", response_model=JobResponse)
def get_job(job_id: str):
    conn = db.connect()
    row = conn.execute("SELECT * FROM jobs WHERE id = ?",(job_id,)).fetchone()
    if not row: raise HTTPException(404, "Job not found")
    return JobResponse(**dict(row))

@router.delete("/{job_id}")
def delete_job(job_id: str):
    conn = db.connect()
    row = conn.execute("SELECT id FROM jobs WHERE id = ?",(job_id,)).fetchone()
    if not row: raise HTTPException(404, "Job not found")
    conn.execute("DELETE FROM jobs WHERE id = ?",(job_id,)); conn.commit()
    return {"status":"deleted"}

@router.post("/{job_id}/start", response_model=JobResponse)
async def start_job(job_id: str):
    conn = db.connect()
    row = conn.execute("SELECT * FROM jobs WHERE id = ?",(job_id,)).fetchone()
    if not row: raise HTTPException(404, "Job not found")
    if row["status"] in ("processing","done"): raise HTTPException(400, f"Job already {row['status']}")
    conn.execute("UPDATE jobs SET status='processing',error=NULL,updated_at=? WHERE id=?",(datetime.utcnow().isoformat(),job_id))
    conn.commit()
    orchestrator = PipelineOrchestrator(job_id)
    asyncio.create_task(orchestrator.start())
    row = conn.execute("SELECT * FROM jobs WHERE id = ?",(job_id,)).fetchone()
    return JobResponse(**dict(row))

@router.post("/{job_id}/cancel", response_model=JobResponse)
def cancel_job(job_id: str):
    conn = db.connect()
    row = conn.execute("SELECT * FROM jobs WHERE id = ?",(job_id,)).fetchone()
    if not row: raise HTTPException(404, "Job not found")
    conn.execute("UPDATE jobs SET status='cancelled',updated_at=? WHERE id=?",(datetime.utcnow().isoformat(),job_id))
    conn.commit()
    row = conn.execute("SELECT * FROM jobs WHERE id = ?",(job_id,)).fetchone()
    return JobResponse(**dict(row))

@router.post("/{job_id}/retry", response_model=JobResponse)
def retry_job(job_id: str):
    conn = db.connect()
    row = conn.execute("SELECT * FROM jobs WHERE id = ?",(job_id,)).fetchone()
    if not row: raise HTTPException(404, "Job not found")
    conn.execute("UPDATE jobs SET status='pending',error=NULL,updated_at=? WHERE id=?",(datetime.utcnow().isoformat(),job_id))
    conn.commit()
    row = conn.execute("SELECT * FROM jobs WHERE id = ?",(job_id,)).fetchone()
    return JobResponse(**dict(row))

@router.get("/{job_id}/clips", response_model=list[ClipResponse])
def get_job_clips(job_id: str):
    conn = db.connect()
    rows = conn.execute("SELECT * FROM clips WHERE job_id = ? ORDER BY score DESC",(job_id,)).fetchall()
    return [ClipResponse(**dict(r)) for r in rows]