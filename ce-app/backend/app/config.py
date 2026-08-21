from __future__ import annotations
import os, json
from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")
    app_name: str = "Cutting Edge"
    app_version: str = "0.3.2"
    backend_host: str = "0.0.0.0"
    backend_port: int = 8742
    log_level: str = "info"
    cuttingedge_home: str = str(Path.home() / "CuttingEdge")
    ffmpeg_path: str = ""
    gemini_api_key: str = ""
    anthropic_api_key: str = ""
    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    ollama_enabled: bool = False
    ollama_model: str = "llama3"
    pexels_api_key: str = ""
    hf_token: str = ""
    youtube_client_id: str = ""
    youtube_client_secret: str = ""
    facebook_access_token: str = ""
    facebook_page_id: str = ""

    @property
    def work_dir(self) -> Path: return Path(self.cuttingedge_home) / "work"
    @property
    def export_dir(self) -> Path: return Path(self.cuttingedge_home) / "exports"
    @property
    def data_dir(self) -> Path: return Path(self.cuttingedge_home) / "data"
    @property
    def db_path(self) -> Path: return self.data_dir / "cuttingedge.db"
    def ensure_dirs(self):
        for d in [self.work_dir, self.export_dir, self.data_dir]: d.mkdir(parents=True, exist_ok=True)

settings = Settings()

CONFIG_PATH = Path(settings.cuttingedge_home) / "config.json"
if CONFIG_PATH.exists():
    try:
        with open(CONFIG_PATH) as f: overrides = json.load(f)
        for k, v in overrides.items():
            if hasattr(settings, k): setattr(settings, k, v)
    except: pass