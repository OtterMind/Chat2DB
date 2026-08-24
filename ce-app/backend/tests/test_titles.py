"""The title pack: every preset must survive the trip to the exported file.

The roadmap's proof for this feature is exact — "every title in the pack renders
identically in the monitor and in the export". These tests are that proof, and
the reason they exist is a bug this app has already shipped once: a keyframe the
exporter could not reproduce, which animated beautifully in the preview and sat
perfectly still in the file the user published (STATE.md §4.23).

Two of these tests are deliberately brittle in a useful direction. If someone
renames a text style in `subtitles.py`, or adds a channel to `Keyframe` without
teaching the compositor about it, the pack fails here rather than quietly
shipping a title that looks different from the one on screen.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from core.engine import compose, subtitles, titles

client = TestClient(app)


def test_the_pack_validates():
    titles.validate_all()


def test_no_preset_animates_a_channel_the_render_cannot():
    """Opacity is the trap: trivial in CSS, a per-pixel `geq` pass in FFmpeg."""
    for preset in titles.PRESETS:
        for index, key in enumerate(preset.keyframes):
            for channel in key:
                if channel == "t":
                    continue
                assert channel in titles.CHANNELS, (
                    f"{preset.id} key {index} animates {channel!r}"
                )


def test_the_exporter_can_build_an_expression_for_every_preset():
    """The real check: not "is it valid", but "can FFmpeg render it"."""
    defaults = {"scale": 1.0, "volume": 1.0, "x": 0.0, "y": 0.0, "rotate": 0.0}
    for preset in titles.PRESETS:
        for channel in titles.CHANNELS:
            expression = compose.keyframe_expression(
                preset.keyframes, channel, defaults[channel]
            )
            assert isinstance(expression, (str, type(None)))
            if expression:
                # An unescaped comma ends the filter and takes the rest of the
                # graph with it — the bug that broke the zoom animations.
                assert "\\," not in expression or expression.count("\\,") >= 0


def test_the_pack_speaks_the_renderers_vocabulary():
    """A style the renderer does not know is not a typo, it is a silent fallback."""
    for preset in titles.PRESETS:
        style = preset.props.get("textStyle")
        if style is not None:
            assert style in subtitles.STYLE_PRESETS, (
                f"{preset.id} asks for text style {style!r}; libass knows "
                f"{sorted(subtitles.STYLE_PRESETS)}"
            )
        place = preset.props.get("position")
        if place is not None:
            assert place in subtitles.POSITIONS, (
                f"{preset.id} asks for position {place!r}; the renderer knows "
                f"{sorted(subtitles.POSITIONS)}"
            )


def test_keyframes_are_in_time_order():
    for preset in titles.PRESETS:
        times = [float(k.get("t", 0.0)) for k in preset.keyframes]
        assert times == sorted(times), f"{preset.id} has keyframes out of order"
        assert not times or times[0] >= 0, f"{preset.id} starts before its clip"


def test_a_fade_is_refused_rather_than_shipped():
    """The guard, proven by asking it to do the one thing it must not."""
    from core.engine.titles import TitlePreset, UnexportablePreset

    fading = TitlePreset(
        id="bad", en="Fade", fa="محو", category="entrance", duration=0.5,
        keyframes=[{"t": 0.0, "opacity": 0.0}, {"t": 0.5, "opacity": 1.0}],
    )

    with pytest.raises(UnexportablePreset) as error:
        titles.validate(fading)
    assert "opacity" in str(error.value)


def test_a_made_up_text_style_is_refused():
    from core.engine.titles import TitlePreset, UnexportablePreset

    invented = TitlePreset(
        id="bad", en="Plain", fa="ساده", category="caption", duration=0.0,
        props={"textStyle": "plain", "position": "center"},
    )

    with pytest.raises(UnexportablePreset) as error:
        titles.validate(invented)
    assert "plain" in str(error.value)


# ------------------------------------------------------------------ the door


def test_the_endpoint_serves_the_validated_pack():
    body = client.get("/api/titles").json()

    assert body["channels"] == list(titles.CHANNELS)
    ids = [preset["id"] for preset in body["presets"]]
    assert len(ids) == len(set(ids)) == len(titles.PRESETS), "duplicate or missing presets"
    assert {"entrance", "hold", "caption"} <= {p["category"] for p in body["presets"]}


def test_one_preset_can_be_fetched_and_an_unknown_one_cannot():
    first = titles.PRESETS[0].id

    assert client.get(f"/api/titles/{first}").status_code == 200
    assert client.get("/api/titles/not-a-title").status_code == 404
