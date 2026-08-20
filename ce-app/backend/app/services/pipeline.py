from __future__ import annotations
from pathlib import Path
from app.config import settings

class PipelineOrchestrator:
    """Orchestrates the execution of a job through all pipeline stages."""

    def __init__(self, job_id: str):
        self.job_id = job_id
        self.job_dir = settings.work_dir / job_id
        self._config: dict = {}

    def _get_conn(self):
        from app.database import db; return db.connect()

    def _update_progress(self, stage: str, progress: float, message: str = ""):
        conn = self._get_conn()
        from datetime import datetime
        now = datetime.utcnow().isoformat()
        conn.execute("UPDATE jobs SET current_stage = ?, progress = ?, updated_at = ? WHERE id = ?",
                     (stage, progress, now, self.job_id))
        conn.commit()

    def _create_stage_record(self, stage_name: str) -> str:
        import uuid; stage_id = str(uuid.uuid4()); now = __import__('datetime').datetime.utcnow().isoformat()
        conn = self._get_conn()
        conn.execute("INSERT INTO stages (id, job_id, stage_name, status, started_at) VALUES (?, ?, ?, 'processing', ?)",
                     (stage_id, self.job_id, stage_name, now))
        conn.commit(); return stage_id

    def _complete_stage(self, stage_id: str, artifacts: dict | None = None):
        conn = self._get_conn(); now = __import__('datetime').datetime.utcnow().isoformat()
        conn.execute("UPDATE stages SET status = 'completed', artifacts = ?, completed_at = ? WHERE id = ?",
                     (__import__('json').dumps(artifacts or {}), now, stage_id))
        conn.commit()

    def _fail_stage(self, stage_id: str, error: str):
        conn = self._get_conn(); now = __import__('datetime').datetime.utcnow().isoformat()
        conn.execute("UPDATE stages SET status = 'failed', error = ?, completed_at = ? WHERE id = ?", (error, now, stage_id))
        conn.execute("UPDATE jobs SET status = 'failed', error = ?, updated_at = ? WHERE id = ?", (error, now, self.job_id))
        conn.commit()

    def _insert_clip(self, start: float, end: float, score: float, reason: str) -> str:
        import uuid; clip_id = str(uuid.uuid4()); now = __import__('datetime').datetime.utcnow().isoformat()
        conn = self._get_conn()
        conn.execute("INSERT INTO clips (id, job_id, start_time, end_time, score, ai_reasoning, status, created_at) VALUES (?,?,?,?,?,?,'pending',?)",
                     (clip_id, self.job_id, start, end, score, reason, now))
        conn.commit(); return clip_id

    def _stage_ingest(self) -> Path:
        from core.engine.ingest import IngestEngine
        conn = self._get_conn()
        row = conn.execute("SELECT source_url, source_type FROM jobs WHERE id = ?", (self.job_id,)).fetchone()
        if not row: raise RuntimeError("Job not found")
        source_url, source_type = row["source_url"], row["source_type"]
        ingest_dir = self.job_dir / "ingest"; ingest_dir.mkdir(parents=True, exist_ok=True)
        if source_type == "local" or (source_url and __import__('pathlib').Path(source_url).exists()):
            import shutil; src = __import__('pathlib').Path(source_url)
            dst = ingest_dir / f"original{src.suffix or '.mp4'}"
            if src.exists(): shutil.copy2(src, dst); return dst
        if not source_url: raise RuntimeError("No source URL provided")
        video = IngestEngine.download_youtube(source_url, ingest_dir)
        if not video or not video.exists(): raise RuntimeError(f"Download failed")
        return video

    def _stage_prepare(self, video_path: Path):
        from core.engine.ingest import IngestEngine
        prepare_dir = self.job_dir / "prepare"; prepare_dir.mkdir(parents=True, exist_ok=True)
        audio = IngestEngine.extract_audio(video_path, prepare_dir / "audio.wav")
        return audio

    def _stage_transcribe(self, audio_path: Path) -> dict:
        transcribe_dir = self.job_dir / "transcribe"; transcribe_dir.mkdir(parents=True, exist_ok=True)
        try:
            from core.engine.transcribe import TranscribeEngine
            tr = TranscribeEngine.transcribe(audio_path)
            TranscribeEngine.save_transcription(tr, transcribe_dir / "words.json")
            return tr
        except Exception as e:
            print(f"[pipeline] transcribe skipped: {e}")
            return {"language":"","duration":0.0,"segments":[]}

    def _stage_highlights(self, transcription: dict) -> list[dict]:
        count = int(self._config.get("clips_count", 5))
        # Auto-segment by duration
        import re, subprocess
        duration = transcription.get("duration") or 0
        if duration == 0.0:
            try:
                r = subprocess.run(["ffmpeg","-i",str(self.job_dir/"ingest"/"original.mp4")], capture_output=True, text=True, timeout=15)
                m = re.search(r"Duration:\s*(\d+):(\d+):(\d+\.?\d*)", r.stderr)
                if m: duration = float(m.group(1))*3600+float(m.group(2))*60+float(m.group(3))
            except: duration = 60.0
        if duration == 0.0: duration = 60.0
        seg_len = max(5.0, duration / max(count, 1))
        results = [{"start":round(i*seg_len,2),"end":round(min((i+1)*seg_len,duration),2),"score":5,"reason":"Auto-selected"} for i in range(count)]
        return results

    def _stage_export(self, video_path: Path, clips: list[dict]):
        from core.engine.export import ExportEngine
        export_dir = self.job_dir / "exports"; export_dir.mkdir(parents=True, exist_ok=True)
        ratio = self._config.get("ratio", "9:16")
        for i, c in enumerate(clips):
            out = export_dir / f"clip_{i+1:02d}_final.mp4"
            try:
                ExportEngine.export(video_path, output_path=out, start_time=c["start_time"], end_time=c["end_time"], ratio=ratio)
                conn = self._get_conn(); conn.execute("UPDATE clips SET output_path=?, status='selected' WHERE id=?", (str(out), c["id"])); conn.commit()
            except Exception as e:
                print(f"[pipeline] clip {i} export failed: {e}")

    async def start(self):
        import json, asyncio
        conn = self._get_conn()
        row = conn.execute("SELECT * FROM jobs WHERE id = ?",(self.job_id,)).fetchone()
        if not row: return
        self._config = json.loads(row["config"] or "{}")
        self.job_dir.mkdir(parents=True, exist_ok=True)
        try:
            sid = self._create_stage_record("ingest")
            self._update_progress("ingest",5,"Downloading video..."); await asyncio.sleep(0.1)
            video = await asyncio.to_thread(self._stage_ingest)
            self._complete_stage(sid,{"video":str(video)})
            self._update_progress("ingest",100,"Video ready")

            sid = self._create_stage_record("prepare")
            self._update_progress("prepare",5,"Extracting audio..."); await asyncio.sleep(0.1)
            audio = await asyncio.to_thread(self._stage_prepare, video)
            self._complete_stage(sid,{"audio":str(audio)})
            self._update_progress("prepare",100,"Audio ready")

            sid = self._create_stage_record("transcribe")
            self._update_progress("transcribe",5,"Transcribing..."); await asyncio.sleep(0.1)
            trans = await asyncio.to_thread(self._stage_transcribe, audio)
            self._complete_stage(sid,{})
            self._update_progress("transcribe",100,"Transcription done")

            sid = self._create_stage_record("highlights")
            self._update_progress("highlights",5,"Finding highlights..."); await asyncio.sleep(0.1)
            highlights = await asyncio.to_thread(self._stage_highlights, trans)
            clips = []
            for h in highlights:
                cid = self._insert_clip(h["start"],h["end"],h["score"],h["reason"])
                clips.append({"id":cid,"start_time":h["start"],"end_time":h["end"]})
            self._complete_stage(sid,{"clips":len(clips)})
            self._update_progress("highlights",100,f"{len(clips)} clips found")

            sid = self._create_stage_record("export")
            self._update_progress("export",10,"Exporting clips..."); await asyncio.sleep(0.1)
            await asyncio.to_thread(self._stage_export, video, clips)
            self._complete_stage(sid,{"exported":len(clips)})
            self._update_progress("export",100,"Export done")

            conn = self._get_conn()
            conn.execute("UPDATE jobs SET status='done',progress=100.0,current_stage=NULL,updated_at=? WHERE id=?",(__import__('datetime').datetime.utcnow().isoformat(),self.job_id))
            conn.commit()
        except Exception as e:
            conn = self._get_conn(); import traceback; traceback.print_exc()
            conn.execute("UPDATE jobs SET status='failed',error=?,updated_at=? WHERE id=?",(str(e),__import__('datetime').datetime.utcnow().isoformat(),self.job_id))
            conn.commit()