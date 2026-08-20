from fastapi import APIRouter, HTTPException
from app.database import db

router = APIRouter(prefix="/api/upload", tags=["upload"])

@router.post("/youtube")
def upload_to_youtube(job_id: str, clip_ids: list[str]):
    conn = db.connect()
    rows = conn.execute("SELECT * FROM clips WHERE id IN ({}) AND job_id = ?".format(",".join("?"*len(clip_ids))), (*clip_ids, job_id)).fetchall()
    if not rows: raise HTTPException(404, "Clips not found")
    return {"status":"uploading","clips":[dict(r) for r in rows]}

@router.post("/facebook")
def upload_to_facebook(job_id: str, clip_ids: list[str]):
    conn = db.connect()
    rows = conn.execute("SELECT * FROM clips WHERE id IN ({}) AND job_id = ?".format(",".join("?"*len(clip_ids))), (*clip_ids, job_id)).fetchall()
    if not rows: raise HTTPException(404, "Clips not found")
    return {"status":"uploading","clips":[dict(r) for r in rows]}