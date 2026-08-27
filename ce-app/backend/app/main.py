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
from app.routers import jobs, clips, system, uploads, render, analyze, media, assistant, captions, audio, brain, projects, style, ai, reframe, gpu, tasks, titles, vad, ocr, vision, sounds, engines
from app.websocket.job_events import ws_manager

@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.ensure_dirs(); db.initialize()
    # Anything the user downloaded on demand lives outside the installation
    # folder, so an update cannot delete it. Make it importable before anything
    # asks whether CUDA is available.
    from core import runtime_packages
    runtime_packages.ensure_on_path()
    print(f"  {__app_name__} v{__version__} starting on 0.0.0.0:{settings.backend_port}")
    yield
    db.close()

app = FastAPI(title=__app_name__, version=__version__, lifespan=lifespan, docs_url="/docs", redoc_url="/redoc")
# CORS locked down, not wide open. The renderer reaches this API from exactly two
# places: the Vite dev server (same-origin via its proxy, so CORS rarely fires) and
# the packaged app over file:// (which the browser reports as the opaque origin
# "null"). A wildcard with credentials would let any website drive the local API;
# an explicit allowlist blocks that while keeping both real clients working.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173",
                   # vite preview — the official suites audit the production
                   # bundle too (0.9.31), and its proxy-less origin is local-only.
                   "http://localhost:4173", "http://127.0.0.1:4173", "null"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(jobs.router)
app.include_router(clips.router)
app.include_router(system.router)
app.include_router(uploads.router)
app.include_router(render.router)
app.include_router(analyze.router)
app.include_router(media.router)
app.include_router(assistant.router)
app.include_router(captions.router)
app.include_router(audio.router)
app.include_router(brain.router)
app.include_router(projects.router)
app.include_router(style.router)
app.include_router(titles.router)
app.include_router(vad.router)
app.include_router(ocr.router)
app.include_router(vision.router)
app.include_router(sounds.router)
app.include_router(engines.router)
app.include_router(ai.router)
app.include_router(reframe.router)
app.include_router(gpu.router)
app.include_router(tasks.router)

@app.get("/api/health")
def health_check():
    return {"status": "ok", "app": __app_name__, "version": __version__}

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await ws_manager.connect(websocket)
    try:
        while True: await websocket.receive_text()
    except WebSocketDisconnect:
        await ws_manager.disconnect(websocket)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host=settings.backend_host, port=settings.backend_port, reload=True, log_level=settings.log_level)