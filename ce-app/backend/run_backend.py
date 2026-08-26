"""Cutting Edge (CE) — Packaged backend entry point (no reload)."""
from __future__ import annotations
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import os
import uvicorn
from app.config import settings

if __name__ == "__main__":
    port = int(os.environ.get("CE_PORT") or settings.backend_port)
    uvicorn.run("app.main:app", host=settings.backend_host, port=port, reload=False, log_level=settings.log_level)