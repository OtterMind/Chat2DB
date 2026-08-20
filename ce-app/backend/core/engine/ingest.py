import subprocess, json, shutil
from pathlib import Path

class IngestEngine:
    @staticmethod
    def validate_video(path: Path) -> dict:
        r = subprocess.run(["ffprobe","-v","quiet","-print_format","json","-show_format","-show_streams",str(path)], capture_output=True, text=True, check=True)
        return json.loads(r.stdout)
    @staticmethod
    def extract_audio(video_path: Path, output_path: Path) -> Path:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["ffmpeg","-i",str(video_path),"-vn","-acodec","pcm_s16le","-ar","16000","-ac","1","-y",str(output_path)], capture_output=True, check=True)
        return output_path
    @staticmethod
    def download_youtube(url: str, output_dir: Path) -> Path:
        import yt_dlp
        output_dir.mkdir(parents=True, exist_ok=True)
        ydl_opts = {"format":"bestvideo[height<=1080]+bestaudio/best[height<=1080]","outtmpl":str(output_dir/"%(id)s.%(ext)s"),"merge_output_format":"mp4","quiet":True,"no_warnings":True}
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            path = output_dir / f"{info['id']}.mp4"
            if not path.exists():
                mp4s = list(output_dir.glob("*.mp4"))
                if mp4s: path = mp4s[0]
            return path