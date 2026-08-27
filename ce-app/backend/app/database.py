from __future__ import annotations
import json, sqlite3, threading
from pathlib import Path
from app.config import settings

class Database:
    """One connection per thread (A3, advisors): WAL allows many readers, but a
    single shared connection with check_same_thread=False let two threads
    interleave writes. Thread-local connections + a busy timeout keep SQLITE_BUSY
    rare and recoverable."""

    def __init__(self, db_path: Path | str):
        self.db_path = Path(db_path)
        self._tls = threading.local()
    def connect(self) -> sqlite3.Connection:
        conn = getattr(self._tls, "conn", None)
        if conn is None:
            self.db_path.parent.mkdir(parents=True, exist_ok=True)
            conn = sqlite3.connect(str(self.db_path), timeout=10)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA foreign_keys=ON")
            conn.execute("PRAGMA busy_timeout=5000")
            self._tls.conn = conn
        return conn
    def initialize(self):
        conn = self.connect()
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, source_url TEXT,
                source_type TEXT DEFAULT 'youtube', status TEXT DEFAULT 'pending',
                current_stage TEXT, progress REAL DEFAULT 0.0, config TEXT, error TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
            CREATE TABLE IF NOT EXISTS clips (
                id TEXT PRIMARY KEY, job_id TEXT REFERENCES jobs(id) ON DELETE CASCADE,
                start_time REAL, end_time REAL, score REAL DEFAULT 0.0, ai_reasoning TEXT,
                status TEXT DEFAULT 'pending', output_path TEXT, thumbnail_path TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
            CREATE TABLE IF NOT EXISTS stages (
                id TEXT PRIMARY KEY, job_id TEXT REFERENCES jobs(id) ON DELETE CASCADE,
                stage_name TEXT, status TEXT DEFAULT 'pending', artifacts TEXT, error TEXT,
                started_at TIMESTAMP, completed_at TIMESTAMP);
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY, value TEXT, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
            CREATE TABLE IF NOT EXISTS uploads (
                id TEXT PRIMARY KEY, clip_id TEXT REFERENCES clips(id) ON DELETE SET NULL,
                platform TEXT, status TEXT DEFAULT 'pending', platform_url TEXT, error TEXT,
                scheduled_at TIMESTAMP, published_at TIMESTAMP);
            CREATE INDEX IF NOT EXISTS idx_clips_job_id ON clips(job_id);
            CREATE INDEX IF NOT EXISTS idx_stages_job_id ON stages(job_id);
        """)
        conn.commit()
        self._insert_defaults(conn)
    def _insert_defaults(self, conn):
        defaults = [
            ("providers", json.dumps({"gemini":{"enabled":False,"api_key":""},"anthropic":{"enabled":False,"api_key":""},"openai":{"enabled":False,"api_key":"","base_url":"https://api.openai.com/v1"},"ollama":{"enabled":False,"model":"llama3"}})),
            ("export", json.dumps({"default_ratio":"9:16","default_clips":5,"quality":"high","format":"mp4"})),
            ("paths", json.dumps({"work_dir":str(settings.work_dir),"export_dir":str(settings.export_dir)})),
            ("ffmpeg_path", ""),
        ]
        for key, value in defaults:
            conn.execute("INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)", (key, value))
        conn.commit()
    def close(self):
        conn = getattr(self._tls, "conn", None)
        if conn: conn.close(); self._tls.conn = None

db = Database(settings.db_path)