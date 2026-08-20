from pathlib import Path

class TranscribeEngine:
    model = None; model_name = "base"

    @classmethod
    def _load_model(cls):
        if cls.model is None:
            from faster_whisper import WhisperModel
            import torch
            device = "cuda" if torch.cuda.is_available() else "cpu"
            compute_type = "float16" if device == "cuda" else "int8"
            cls.model = WhisperModel(cls.model_name, device=device, compute_type=compute_type)

    @classmethod
    def transcribe(cls, audio_path: Path, language: str | None = None) -> dict:
        try:
            cls._load_model()
            segments, info = cls.model.transcribe(str(audio_path), language=language, word_timestamps=True, vad_filter=True)
        except Exception:
            return {"language":"","duration":0.0,"segments":[]}
        result = {"language":info.language,"duration":info.duration,"segments":[]}
        for seg in segments:
            s = {"start":seg.start,"end":seg.end,"text":seg.text.strip(),"words":[]}
            if seg.words:
                for w in seg.words: s["words"].append({"word":w.word.strip(),"start":w.start,"end":w.end,"probability":w.probability})
            result["segments"].append(s)
        return result

    @classmethod
    def save_transcription(cls, transcription: dict, output_path: Path):
        import json
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f: json.dump(transcription, f, ensure_ascii=False, indent=2)