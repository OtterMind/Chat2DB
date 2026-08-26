"""ASS subtitle round-trip — built-in parser, python-ass when fetched.

The exporter already burns karaoke captions with libass; this closes the other
direction: a `.ass` file someone edited in a typesetter's editor (Aegisub) comes
back as caption cues **with the word timings reconstructed from the `\\kf` tags**,
and our cues can be written out as a standalone `.ass` for that same editor.

The built-in parser is the tested floor and needs nothing. When **python-ass**
(MIT) is fetched it becomes the reader for files with unusual section orders;
either way the return shape is the same, and the result says which reader ran.
"""
from __future__ import annotations

import importlib.util
import re
from pathlib import Path


def available() -> bool:
    return importlib.util.find_spec("ass") is not None


_TS = re.compile(r"(\d+):(\d{1,2}):(\d{1,2})[.,](\d{1,3})")
_KF = re.compile(r"\{\\kf(\d+)\}")


def parse_timestamp(text: str) -> float:
    """`0:01:23.45` → 83.45 seconds."""
    match = _TS.search(text or "")
    if not match:
        raise ValueError(f"not an ASS timestamp: {text!r}")
    hours, minutes, seconds, cent = (int(g) for g in match.groups())
    return hours * 3600 + minutes * 60 + seconds + cent / 100


def format_timestamp(value: float) -> str:
    """83.45 → `0:01:23.45` (ASS centiseconds)."""
    value = max(0.0, float(value))
    cent = round(value * 100)
    hours, rem = divmod(cent, 360000)
    minutes, rem = divmod(rem, 6000)
    seconds, cent = divmod(rem, 100)
    return f"{hours}:{minutes:02d}:{seconds:02d}.{cent:02d}"


def _words_from_text(text: str, start: float) -> tuple[str, list[dict]]:
    """Split a dialogue text on `\\kf` tags into timed words.

    `\\kf` durations are centiseconds and apply to the text that follows the
    tag, starting at the cue's start. Without tags the whole line is one
    untimed piece of text.
    """
    plain = _KF.sub("", text)
    plain = re.sub(r"\{\\[^}]*\}", "", plain).strip()
    words: list[dict] = []
    clock = start
    for match in re.finditer(r"\{\\kf(\d+)\}([^{}]*)", text):
        centis = int(match.group(1))
        piece = re.sub(r"\{\\[^}]*\}", "", match.group(2)).strip()
        if not piece:
            continue
        end = clock + centis / 100
        words.append({"start": round(clock, 3), "end": round(end, 3), "text": piece})
        clock = end
    return plain, words


def _parse_builtin(text: str) -> list[dict]:
    lines = text.splitlines()
    try:
        events_at = next(i for i, line in enumerate(lines)
                         if line.strip().lower() == "[events]")
    except StopIteration:
        return []
    fmt: list[str] | None = None
    cues: list[dict] = []
    for line in lines[events_at + 1:]:
        stripped = line.strip()
        if stripped.lower().startswith("["):
            break
        if stripped.lower().startswith("format:"):
            fmt = [part.strip().lower() for part in stripped.split(":", 1)[1].split(",")]
        elif stripped.lower().startswith("dialogue:"):
            cols = [part.strip() for part in stripped.split(":", 1)[1].split(",", 9)]
            names = fmt or ["layer", "start", "end", "style", "name",
                            "marginl", "marginr", "marginv", "effect", "text"]
            row = dict(zip(names, cols))
            try:
                start = parse_timestamp(row.get("start", ""))
                end = parse_timestamp(row.get("end", ""))
            except ValueError:
                continue
            raw_text = row.get("text", "")
            plain, words = _words_from_text(raw_text, start)
            cues.append({"start": round(start, 3), "end": round(end, 3),
                         "text": plain, "words": words,
                         "style": row.get("style", "Default"),
                         "animate": bool(words)})
    return cues


def import_cues(path: str) -> dict:
    """Read a `.ass` file into caption cues.

    Never raises for a missing engine: the built-in parser is always there;
    python-ass is only the fancier reader, and if it stumbles on a file we fall
    back rather than lose the captions.
    """
    file = Path(path)
    if not file.exists():
        raise FileNotFoundError(path)
    text = file.read_text(encoding="utf-8-sig", errors="replace")
    if available():
        try:
            import ass  # noqa: PLC0415

            doc = ass.Document().parse_file(str(file))
            cues = []
            for event in doc.events:
                start = float(getattr(event, "Start", 0)) / 100
                end = float(getattr(event, "End", 0)) / 100
                plain, words = _words_from_text(str(event.Text), start)
                cues.append({"start": round(start, 3), "end": round(end, 3),
                             "text": plain, "words": words,
                             "style": str(getattr(event, "Style", "Default")),
                             "animate": bool(words)})
            return {"cues": cues, "reader": "python-ass"}
        except Exception:  # noqa: BLE001 — fall through to the built-in reader
            pass
    return {"cues": _parse_builtin(text), "reader": "builtin"}


def export(cues: list[dict], path: str, width: int = 1080, height: int = 1920) -> dict:
    """Write cues as a standalone `.ass` — the same writer the compositor feeds
    on, so what the typesetter sees is what the export burns."""
    from core.engine import subtitles  # noqa: PLC0415

    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    text_cues = []
    for cue in cues:
        text_cues.append(subtitles.TextCue(
            start=float(cue["start"]), end=float(cue["end"]), text=cue.get("text", ""),
            words=[subtitles.Word(start=w["start"], end=w["end"], text=w["text"])
                   for w in cue.get("words") or []],
            animate=bool(cue.get("words")),
        ))
    destination.write_text(subtitles.build_ass(text_cues, width, height),
                           encoding="utf-8")
    return {"path": str(destination), "writer": "builtin", "cues": len(text_cues)}
