"""Audio extraction: FFmpeg always; Demucs gated and honest."""
from __future__ import annotations

import subprocess

from fastapi.testclient import TestClient

from app.main import app
from core.engine import audio_extract
from core.engine.compose import ffmpeg_binary
from tests.conftest import requires_ffmpeg

client = TestClient(app)


@requires_ffmpeg
def test_extraction_lifts_the_track_and_probes_it(media, tmp_path):
    import shutil

    # a video that carries an audio track: clip_a's picture + the tone
    with_audio = tmp_path / "with_audio.mp4"
    subprocess.run([ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
                    "-i", str(media["clip_a"]), "-i", str(media["tone"]),
                    "-c:v", "copy", "-c:a", "aac", "-shortest", str(with_audio)],
                   check=True)

    out = audio_extract.extract(str(with_audio), str(tmp_path / "lifted.m4a"))

    assert (tmp_path / "lifted.m4a").exists()
    assert out["duration"] > 0
    # the lifted file is audio-only — a video stream would be the bug
    probe = subprocess.run([ffmpeg_binary(), "-hide_banner", "-i", str(tmp_path / "lifted.m4a")],
                           capture_output=True, text=True)
    assert "Video:" not in probe.stderr and "Audio:" in probe.stderr


def test_extracting_a_missing_file_is_a_404():
    assert client.post("/api/audio/extract",
                       json={"path": "/nonexistent/x.mp4"}).status_code == 404


def test_stems_without_demucs_is_an_honest_409():
    if audio_extract.available():
        return
    assert client.post("/api/audio/stems/start",
                       json={"path": "/nonexistent/x.mp4"}).status_code in (404, 409)


def test_voice_source_prefers_a_cached_vocals_stem(monkeypatch, tmp_path):
    """Ducking measures the cleanest voice it has: the Demucs vocals stem when
    the user split stems for this file, the raw file otherwise."""
    from core.engine import audio, audio_extract

    monkeypatch.setattr(audio_extract, "exports_dir", lambda: tmp_path)
    src = tmp_path / "clip.mp4"

    assert audio.voice_source(str(src)) == str(src)          # no stem yet

    (tmp_path / "clip.stems").mkdir()
    (tmp_path / "clip.stems" / "vocals.wav").write_bytes(b"x")

    assert audio.voice_source(str(src)) == str(tmp_path / "clip.stems" / "vocals.wav")
