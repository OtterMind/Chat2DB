from fastapi import APIRouter, HTTPException
from app.database import db
from app.schemas.job import ClipResponse, ClipUpdate

router = APIRouter(prefix="/api/clips", tags=["clips"])

@router.patch("/{clip_id}", response_model=ClipResponse)
def update_clip(clip_id: str, data: ClipUpdate):
    conn = db.connect()
    row = conn.execute("SELECT * FROM clips WHERE id = ?",(clip_id,)).fetchone()
    if not row: raise HTTPException(404, "Clip not found")
    if data.status:
        conn.execute("UPDATE clips SET status=? WHERE id=?",(data.status,clip_id)); conn.commit()
    row = conn.execute("SELECT * FROM clips WHERE id = ?",(clip_id,)).fetchone()
    return ClipResponse(**dict(row))