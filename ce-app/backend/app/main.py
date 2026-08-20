"""Cutting Edge (CE) — FastAPI Backend Entry Point."""
from __future__ import annotations
import sys
from contextlib import asynccontextmanager
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from app import __version__, __app_name__
from app.config import settings
from app.database import db
from app.routers import jobs, clips, system, uploads
from app.websocket.job_events import ws_manager

@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.ensure_dirs(); db.initialize()
    print(f"  {__app_name__} v{__version__} starting on 0.0.0.0:{settings.backend_port}")
    yield
    db.close()

app = FastAPI(title=__app_name__, version=__version__, lifespan=lifespan, docs_url="/docs", redoc_url="/redoc")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

app.include_router(jobs.router)
app.include_router(clips.router)
app.include_router(system.router)
app.include_router(uploads.router)

@app.get("/api/health")
def health_check():
    return {"status": "ok", "app": __app_name__, "version": __version__}

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await ws_manager.connect(websocket)
    try:
        while True: await websocket.receive_text()
    except WebSocketDisconnect:
        ws_manager.disconnect(websocket)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host=settings.backend_host, port=settings.backend_port, reload=True, log_level=settings.log_level)