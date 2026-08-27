from __future__ import annotations
import json
from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")
    app_name: str = "Cutting Edge"
    app_version: str = "0.3.3"
    backend_host: str = "0.0.0.0"
    backend_port: int = 8742
    log_level: str = "info"
    cuttingedge_home: str = str(Path.home() / "CuttingEdge")
    ffmpeg_path: str = ""
    gemini_api_key: str = ""
    anthropic_api_key: str = ""
    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    #: Which model answers the assistant: auto | off | ollama | openai | gemini
    #: | anthropic. Stored here rather than in the panel so one choice covers the
    #: chat, Settings and any future door to the same brain.
    assistant_provider: str = "auto"
    #: Which speech map the edit is built on: `energy` (FFmpeg silencedetect, the
    #: default and what every release so far used) or `silero` (the on-demand
    #: model). Opt-in until it has been measured on real speech — see core/engine/vad.py.
    speech_engine: str = "energy"
    #: Let a vision model (in the user's own Ollama) add one vote to the highlight
    #: scorer. Off by default: a boost that is absent is not a regression, and the
    #: judgement on whether it helps belongs to the user's own footage (§4.57).
    vision_enabled: bool = False
    #: Let the measured reaction of the room (crowd / laughter cues, B2) add one
    #: light vote to the highlight scorer. Unlike the vision vote this is a
    #: measurement the backend can always make — spectrum shape, level and
    #: rhythm — so it is on, capped at `emotion.MAX_WEIGHT`, and its numbers are
    #: shown in Settings → *Cut on emotion* → *Measure it*.
    emotion_enabled: bool = True
    ollama_enabled: bool = False
    ollama_model: str = "llama3"
    pexels_api_key: str = ""
    #: Freesound needs a key (free account). Without it the sound pack reports
    #: "not configured" rather than guessing — a pack that cannot be searched is
    #: not a pack.
    freesound_api_key: str = ""
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
    except Exception: pass