"""Title presets — animation built only from what the exporter can reproduce.

A title pack is where an editor usually cheats: the presets fade, because fading
is easy in a preview. This app cannot fade a keyframe, and a preset the export
cannot reproduce is a lie told twice — once in the monitor and once in the file
the user publishes (STATE.md §4.23). So every preset here is built from exactly
the five channels `compose.keyframe_expression()` can animate:

    x · y · scale · rotate · volume

`validate()` enforces that, and `tests/test_titles.py` runs every preset in the
catalogue through the real expression builder, so a preset that could not be
rendered fails the suite instead of reaching a user.

What is deliberately **not** in this pack:

* **opacity / fade** — needs a per-pixel `geq` pass; the in/out animations on a
  clip already cover that case, and they do it in the compositor.
* **per-character typing** — needs a text layout pass the renderer does not have.
  `animateWords` (the karaoke highlight) *is* supported, so presets may set it.
* **blur, glow, shadows** — measurable in CSS, not in the FFmpeg chain.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field

#: The only channels that survive the trip from the monitor to the exported file.
CHANNELS = ("x", "y", "scale", "rotate", "volume")

#: The text properties, with the *exact* names the renderer knows:
#: `subtitles.STYLE_PRESETS` and `subtitles.POSITIONS`. A preset that says
#: "plain" or "center" is not a small typo — libass falls back to its default
#: and the title silently looks different from the one on screen.
TEXT_STYLES = ("clean", "boxed", "outline", "shadow")
POSITIONS = ("top", "middle", "bottom")


class UnexportablePreset(ValueError):
    """A preset asked for a channel the render cannot animate."""


@dataclass
class TitlePreset:
    """One title animation, as keyframes plus the text properties it wants."""

    id: str
    en: str
    fa: str
    category: str
    #: How long the movement takes, in seconds. The clip may be longer.
    duration: float
    keyframes: list[dict] = field(default_factory=list)
    #: Text properties: position, textStyle, animateWords.
    props: dict = field(default_factory=dict)

    def as_dict(self) -> dict:
        return asdict(self)


# ---------------------------------------------------------------- the catalogue
#
# Keyframe times are relative to the start of the clip, which is what
# `keyframe_expression()` and the monitor's `sampleChannel()` both expect. A
# three-key preset is not showing off: "pop" needs the overshoot or it is just a
# zoom, and the difference is the whole reason it feels like a title.

PRESETS: tuple[TitlePreset, ...] = (
    # --- entrances ----------------------------------------------------------
    TitlePreset(
        id="rise", en="Rise into place", fa="از پایین بالا می‌آید", category="entrance",
        duration=0.5,
        keyframes=[{"t": 0.0, "y": 0.10, "scale": 0.96}, {"t": 0.5, "y": 0.0, "scale": 1.0}],
        props={"position": "bottom", "textStyle": "outline"},
    ),
    TitlePreset(
        id="drop", en="Drop in", fa="از بالا می‌افتد", category="entrance",
        duration=0.45,
        keyframes=[
            {"t": 0.0, "y": -0.28, "scale": 1.0},
            {"t": 0.30, "y": 0.03, "scale": 1.0},   # the overshoot that makes it land
            {"t": 0.45, "y": 0.0, "scale": 1.0},
        ],
        props={"position": "top", "textStyle": "boxed"},
    ),
    TitlePreset(
        id="pop", en="Pop", fa="پاپ", category="entrance",
        duration=0.4,
        keyframes=[
            {"t": 0.0, "scale": 0.4},
            {"t": 0.25, "scale": 1.15},            # overshoot, or it is only a zoom
            {"t": 0.40, "scale": 1.0},
        ],
        props={"position": "middle", "textStyle": "outline"},
    ),
    TitlePreset(
        id="zoom_in", en="Zoom in", fa="بزرگ‌نمایی به داخل", category="entrance",
        duration=0.6,
        keyframes=[{"t": 0.0, "scale": 0.70}, {"t": 0.6, "scale": 1.0}],
        props={"position": "middle", "textStyle": "clean"},
    ),
    TitlePreset(
        id="zoom_out", en="Zoom out and settle", fa="کوچک می‌شود و می‌نشیند", category="entrance",
        duration=0.6,
        keyframes=[{"t": 0.0, "scale": 1.30}, {"t": 0.6, "scale": 1.0}],
        props={"position": "middle", "textStyle": "clean"},
    ),
    TitlePreset(
        id="slide_left", en="Slide in from the side", fa="از کنار وارد می‌شود", category="entrance",
        duration=0.5,
        keyframes=[{"t": 0.0, "x": 0.35}, {"t": 0.5, "x": 0.0}],
        props={"position": "bottom", "textStyle": "boxed"},
    ),
    TitlePreset(
        id="spin_in", en="Spin in", fa="چرخان وارد می‌شود", category="entrance",
        duration=0.7,
        keyframes=[
            {"t": 0.0, "rotate": -14.0, "scale": 0.80},
            {"t": 0.7, "rotate": 0.0, "scale": 1.0},
        ],
        props={"position": "middle", "textStyle": "outline"},
    ),
    TitlePreset(
        id="nudge", en="Nudge and hold", fa="یک تکان و می‌ماند", category="entrance",
        duration=0.35,
        keyframes=[
            {"t": 0.0, "x": -0.04},
            {"t": 0.18, "x": 0.02},
            {"t": 0.35, "x": 0.0},
        ],
        props={"position": "top", "textStyle": "outline"},
    ),
    # --- holds: movement across the whole title ------------------------------
    TitlePreset(
        id="ken_burns", en="Slow push", fa="حرکت آرام به جلو", category="hold",
        duration=3.0,
        keyframes=[{"t": 0.0, "scale": 1.0}, {"t": 3.0, "scale": 1.12}],
        props={"position": "middle", "textStyle": "clean"},
    ),
    TitlePreset(
        id="drift", en="Drift across", fa="آرام جابه‌جا می‌شود", category="hold",
        duration=3.0,
        keyframes=[{"t": 0.0, "x": -0.06}, {"t": 3.0, "x": 0.06}],
        props={"position": "middle", "textStyle": "clean"},
    ),
    TitlePreset(
        id="breathe", en="Breathe", fa="نفس می‌کشد", category="hold",
        duration=2.4,
        keyframes=[
            {"t": 0.0, "scale": 1.0},
            {"t": 1.2, "scale": 1.05},
            {"t": 2.4, "scale": 1.0},
        ],
        props={"position": "middle", "textStyle": "clean"},
    ),
    # --- caption styles: the same words, read word by word -------------------
    TitlePreset(
        id="karaoke", en="Karaoke (word by word)", fa="کارائوکه (کلمه به کلمه)", category="caption",
        duration=0.0,
        keyframes=[],
        props={"position": "bottom", "textStyle": "outline", "animateWords": True},
    ),
    TitlePreset(
        id="karaoke_box", en="Karaoke in a box", fa="کارائوکه در کادر", category="caption",
        duration=0.0,
        keyframes=[],
        props={"position": "bottom", "textStyle": "boxed", "animateWords": True},
    ),
    TitlePreset(
        id="plain_bottom", en="Plain, bottom", fa="ساده، پایین", category="caption",
        duration=0.0,
        keyframes=[],
        props={"position": "bottom", "textStyle": "clean", "animateWords": False},
    ),
    TitlePreset(
        id="plain_top", en="Plain, top", fa="ساده، بالا", category="caption",
        duration=0.0,
        keyframes=[],
        props={"position": "top", "textStyle": "clean", "animateWords": False},
    ),
)

BY_ID: dict[str, TitlePreset] = {preset.id: preset for preset in PRESETS}


def validate(preset: TitlePreset) -> TitlePreset:
    """Refuse anything the render cannot reproduce.

    This is the guard that keeps the pack honest as it grows: a new preset that
    reaches for `opacity` — the one channel that looks trivial and is not — fails
    here rather than shipping a title that animates in the monitor and sits still
    in the exported file.
    """
    for index, key in enumerate(preset.keyframes):
        for channel in key:
            if channel == "t":
                continue
            if channel not in CHANNELS:
                raise UnexportablePreset(
                    f"preset {preset.id!r} key {index} animates {channel!r}, "
                    f"which the exporter cannot reproduce (allowed: {', '.join(CHANNELS)})"
                )
    style = preset.props.get("textStyle")
    if style is not None and style not in TEXT_STYLES:
        raise UnexportablePreset(
            f"preset {preset.id!r} asks for text style {style!r}; "
            f"the renderer knows {', '.join(TEXT_STYLES)}"
        )
    place = preset.props.get("position")
    if place is not None and place not in POSITIONS:
        raise UnexportablePreset(
            f"preset {preset.id!r} asks for position {place!r}; "
            f"the renderer knows {', '.join(POSITIONS)}"
        )
    times = [float(key.get("t", 0.0)) for key in preset.keyframes]
    if times != sorted(times):
        raise UnexportablePreset(f"preset {preset.id!r} has keyframes out of order")
    if times and times[0] < 0:
        raise UnexportablePreset(f"preset {preset_id(preset)} starts before the clip does")
    return preset


def preset_id(preset: TitlePreset) -> str:
    return preset.id


def catalogue() -> dict:
    """The whole pack, for the screen to render from one source.

    Served rather than hard-coded in the renderer because `validate()` lives
    here: a list the backend checks and the frontend copies is a list that
    drifts.
    """
    validate_all()
    return {
        "channels": list(CHANNELS),
        "presets": [preset.as_dict() for preset in PRESETS],
    }


def validate_all() -> None:
    """Every preset in the pack, checked. Raises on the first that would lie."""
    for preset in PRESETS:
        validate(preset)


def get(preset_id_: str) -> TitlePreset | None:
    return BY_ID.get(preset_id_)
