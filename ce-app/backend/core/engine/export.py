import subprocess
from pathlib import Path

class ExportEngine:
    @staticmethod
    def find_ffmpeg() -> str:
        import os, shutil
        if os.environ.get("CE_FFMPEG_DIR"):
            p = Path(os.environ["CE_FFMPEG_DIR"]) / "ffmpeg"
            for c in [p, Path(os.environ["CE_FFMPEG_DIR"])]:
                if c.exists(): return str(c)
        f = shutil.which("ffmpeg")
        if f: return f
        raise RuntimeError("FFmpeg not found. Install ffmpeg and add to PATH or set CE_FFMPEG_DIR")
    @staticmethod
    def probe_encoder(encoder="h264_nvenc") -> bool:
        try:
            r = subprocess.run([ExportEngine.find_ffmpeg(),"-hide_banner","-f","lavfi","-i","color=c=black:s=64x64:d=1","-c:v",encoder,"-frames:v","1","-f","null","-"], capture_output=True, text=True, timeout=10)
            return r.returncode == 0
        except: return False
    @classmethod
    def select_encoder(cls) -> str:
        return "h264_nvenc" if cls.probe_encoder() else "libx264"
    @classmethod
    def export(cls, video_path: Path, output_path: Path | None = None, start_time: float | None = None, end_time: float | None = None, ratio: str = "9:16", quality: str = "high") -> Path:
        output_path = output_path or video_path.parent / "export.mp4"
        output_path.parent.mkdir(parents=True, exist_ok=True)
        ffmpeg = cls.find_ffmpeg(); encoder = cls.select_encoder()
        cmd = [ffmpeg]
        if start_time and start_time > 0: cmd += ["-ss", f"{start_time:.3f}"]
        cmd += ["-i", str(video_path)]
        if end_time and start_time is not None: cmd += ["-t", f"{max(0.0, end_time - start_time):.3f}"]
        cmd += ["-c:v", encoder, "-preset", "p7" if encoder == "h264_nvenc" else "medium", "-crf", "17" if quality == "high" else "23", "-c:a", "aac", "-b:a", "128k", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-y", str(output_path)]
        if ratio == "9:16":
            cmd += ["-vf", "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=black"]
        subprocess.run(cmd, capture_output=True, check=True)
        return output_path