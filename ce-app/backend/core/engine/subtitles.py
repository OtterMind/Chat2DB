"""Cutting Edge (CE) — subtitle and text rendering.

Text is drawn with **libass** through FFmpeg's `subtitles` filter rather than
`drawtext`. Three reasons:

* `drawtext` is missing from many FFmpeg builds — including the static Linux
  binary the headless tests run against — while `libass` is always there. (The
  Windows build we ship is `ffmpeg-release-full`, which does have `drawtext`;
  the point stands for portability, not for that build.)
* Persian and Arabic need bidi handling and glyph shaping, which libass does and
  drawtext does not;
* karaoke-style word highlighting is a native ASS feature, so animated captions
  cost nothing extra at render time.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from core.engine import persian

# Windows ships Persian-capable fonts; nothing has to be bundled or licensed.
DEFAULT_FONT_LATIN = "Arial"
DEFAULT_FONT_RTL = "Tahoma"

#: Ready-made caption looks. Values are ASS style fields.
STYLE_PRESETS: dict[str, dict] = {
    "clean": {"outline": 2.0, "shadow": 0.0, "back": "&H00000000", "bold": -1},
    "boxed": {"outline": 0.0, "shadow": 0.0, "back": "&H80000000", "bold": -1, "border_style": 3},
    "outline": {"outline": 3.5, "shadow": 0.0, "back": "&H00000000", "bold": -1},
    "shadow": {"outline": 1.0, "shadow": 2.5, "back": "&H00000000", "bold": -1},
}

POSITIONS = {"bottom": 2, "middle": 5, "top": 8}


def _ass_colour(hex_colour: str, alpha: str = "00") -> str:
    """#RRGGBB (CSS) to &HAABBGGRR (ASS)."""
    value = hex_colour.lstrip("#")
    if len(value) != 6:
        return "&H00FFFFFF"
    r, g, b = value[0:2], value[2:4], value[4:6]
    return f"&H{alpha}{b}{g}{r}".upper()


def _timestamp(seconds: float) -> str:
    seconds = max(0.0, seconds)
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    secs = seconds % 60
    return f"{hours:d}:{minutes:02d}:{secs:05.2f}"


def _escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("\n", "\\N")


def _is_rtl(text: str) -> bool:
    return any("\u0600" <= ch <= "\u06FF" or "\u0750" <= ch <= "\u077F" for ch in text)


@dataclass
class Word:
    start: float
    end: float
    text: str


@dataclass
class TextCue:
    """One piece of text on screen: a title, a caption line, anything."""

    start: float
    end: float
    text: str
    words: list[Word] = field(default_factory=list)
    font_size: int = 54
    colour: str = "#FFFFFF"
    highlight: str = "#6366F1"
    position: str = "bottom"
    style: str = "clean"
    font: str | None = None
    #: Horizontal/vertical nudge as a fraction of the frame.
    x: float = 0.0
    y: float = 0.0
    animate: bool = False


def build_ass(cues: list[TextCue], width: int, height: int) -> str:
    """A complete ASS document for the whole timeline."""
    styles: list[str] = []
    events: list[str] = []
    seen: set[str] = set()

    for index, cue in enumerate(cues):
        preset = STYLE_PRESETS.get(cue.style, STYLE_PRESETS["clean"])
        font = cue.font or (DEFAULT_FONT_RTL if _is_rtl(cue.text) else DEFAULT_FONT_LATIN)
        name = f"ce{index}"
        if name not in seen:
            seen.add(name)
            margin_v = int(height * 0.08 + abs(cue.y) * height)
            styles.append(
                "Style: {name},{font},{size},{primary},{secondary},{outline_colour},{back},"
                "{bold},0,0,0,100,100,0,0,{border_style},{outline},{shadow},{align},"
                "{margin_l},{margin_r},{margin_v},1".format(
                    name=name,
                    font=font,
                    size=cue.font_size,
                    primary=_ass_colour(cue.colour),
                    secondary=_ass_colour(cue.highlight),
                    outline_colour="&H00000000",
                    back=preset.get("back", "&H00000000"),
                    bold=preset.get("bold", -1),
                    border_style=preset.get("border_style", 1),
                    outline=preset.get("outline", 2.0),
                    shadow=preset.get("shadow", 0.0),
                    align=POSITIONS.get(cue.position, 2),
                    margin_l=int(width * 0.06 + max(0.0, cue.x) * width),
                    margin_r=int(width * 0.06 + max(0.0, -cue.x) * width),
                    margin_v=margin_v,
                )
            )

        if cue.words and cue.animate:
            # Karaoke: \k durations are in centiseconds and highlight word by word.
            parts = []
            for word in cue.words:
                centis = max(1, int(round((word.end - word.start) * 100)))
                parts.append(f"{{\\kf{centis}}}{_escape(word.text)} ")
            body = "".join(parts).strip()
        else:
            body = _escape(cue.text)

        events.append(
            f"Dialogue: 0,{_timestamp(cue.start)},{_timestamp(cue.end)},{name},,0,0,0,,{body}"
        )

    return "\n".join([
        "[Script Info]",
        "ScriptType: v4.00+",
        f"PlayResX: {width}",
        f"PlayResY: {height}",
        "WrapStyle: 0",
        "ScaledBorderAndShadow: yes",
        "YCbCr Matrix: TV.709",
        "",
        "[V4+ Styles]",
        "Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,"
        "Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,"
        "Alignment,MarginL,MarginR,MarginV,Encoding",
        *styles,
        "",
        "[Events]",
        "Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text",
        *events,
        "",
    ])


def write_ass(cues: list[TextCue], width: int, height: int, destination: Path) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(build_ass(cues, width, height), encoding="utf-8")
    return destination


def filter_path(path: Path) -> str:
    """Escape a path for use inside a filter argument (Windows drive letters)."""
    text = str(path).replace("\\", "/")
    text = text.replace(":", "\\:")
    return text


def cues_from_clips(clips: list[dict], default_size: int = 54) -> list[TextCue]:
    """Turn text clips of the edit model into cues."""
    cues: list[TextCue] = []
    for clip in clips:
        text = persian.normalize((clip.get("text") or clip.get("label") or "").strip())
        if not text:
            continue
        props = clip.get("props") or {}
        words = [
            Word(float(w["start"]), float(w["end"]), str(w["text"]))
            for w in (clip.get("words") or [])
            if w.get("text")
        ]
        cues.append(
            TextCue(
                start=float(clip.get("start", 0.0)),
                end=float(clip.get("start", 0.0)) + float(clip.get("duration", 2.0)),
                text=text,
                words=words,
                font_size=int(props.get("fontSize", default_size)),
                colour=str(props.get("color", "#FFFFFF")),
                highlight=str(props.get("highlight", "#6366F1")),
                position=str(props.get("position", "bottom")),
                style=str(props.get("textStyle", "clean")),
                font=props.get("font") or None,
                x=float((props.get("transform") or {}).get("x", 0.0)),
                y=float((props.get("transform") or {}).get("y", 0.0)),
                animate=bool(props.get("animateWords", False)) and bool(words),
            )
        )
    return cues


def fonts_dir() -> str | None:
    """Where libass should look for fonts, when we know better than fontconfig."""
    bundled = os.environ.get("CE_FONTS_DIR")
    if bundled and Path(bundled).exists():
        return bundled
    return None
