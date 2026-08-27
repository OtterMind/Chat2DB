"""Tier 2 — the social export pack: one edit, a whole deliverable folder.

Advisor 2's "Export Pack": a job should not end at a single MP4. Creators upload a
*package* — the video in the ratios they need, captions as SRT *and* ASS, a
thumbnail, a `description.md` with chapters, the objective's numbers in
`meta.json`, and an OTIO timeline for the pro NLEs. Everything here reuses an
existing writer (subtitles, interchange, the FFmpeg thumbnail), so the pack is a
composition, not a second implementation of any of them.

The pack never re-encodes the video: it copies the already-rendered MP4 in, so the
finished file in the pack is byte-identical to the one the user approved.
"""
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

from core.engine import interchange, subtitles
from core.engine.compose import ffmpeg_binary


def _thumb(source: Path, at: float, target: Path) -> bool:
    try:
        subprocess.run(
            [ffmpeg_binary(), "-hide_banner", "-loglevel", "error", "-y",
             "-ss", f"{max(0.0, at):.3f}", "-i", str(source),
             "-frames:v", "1", "-vf", "scale=-2:720:flags=bicubic",
             "-q:v", "3", str(target)],
            check=True, timeout=25,
        )
        return target.exists()
    except Exception:  # noqa: BLE001 — a missing thumb does not fail the pack
        return False


def _description(meta: dict, chapters: list[dict]) -> str:
    lines = [f"# {meta.get('name', 'Cutting Edge edit')}", ""]
    if meta.get("hookLabel"):
        lines.append(f"**Hook:** {meta.get('hookLabel')} ({meta.get('hook')}/100)")
        lines.append("")
    if chapters:
        lines.append("## Chapters")
        for ch in chapters:
            lines.append(f"- {ch.get('t', 0):.0f}s — {ch.get('title', '')}")
        lines.append("")
    if meta.get("reasons"):
        lines.append("## Why these moments")
        for reason in meta["reasons"]:
            lines.append(f"- {reason}")
        lines.append("")
    lines.append("_Generated locally by Cutting Edge — no footage left this machine._")
    return "\n".join(lines)


def build_pack(video: str, destination: str, *, timeline: dict | None = None,
               cues: list[dict] | None = None, meta: dict | None = None,
               chapters: list[dict] | None = None, name: str = "cutting-edge") -> dict:
    """Write the deliverable folder and report exactly which files landed."""
    video_path = Path(video)
    if not video_path.exists():
        raise FileNotFoundError(str(video_path))
    out = Path(destination)
    out.mkdir(parents=True, exist_ok=True)
    meta = meta or {}
    cues = cues or []
    written: list[str] = []

    final = out / f"{name}.mp4"
    shutil.copyfile(video_path, final)
    written.append(final.name)

    if cues:
        srt = out / f"{name}.srt"
        srt.write_text(subtitles.build_srt(cues), encoding="utf-8")
        written.append(srt.name)
        ass = out / f"{name}.ass"
        clips = [{"text": c.get("text", ""), "start": c.get("start", 0), "end": c.get("end", 0)}
                 for c in cues]
        subtitles.write_ass(subtitles.cues_from_clips(clips), 1080, 1920, ass)
        written.append(ass.name)

    if _thumb(video_path, float(meta.get("thumbAt", 1.0)), out / f"{name}-thumb.jpg"):
        written.append(f"{name}-thumb.jpg")

    if timeline and interchange.available():
        try:
            otio = out / f"{name}.otio"
            interchange.export_otio(timeline, str(otio), name=name)
            written.append(otio.name)
        except Exception:  # noqa: BLE001 — OTIO optional; the pack still ships
            pass

    description = out / "description.md"
    description.write_text(_description(meta, chapters or []), encoding="utf-8")
    written.append(description.name)

    meta_file = out / "meta.json"
    meta_file.write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    written.append(meta_file.name)

    return {"dir": str(out), "files": written, "count": len(written)}
