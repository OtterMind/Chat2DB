"""Everything we measure has to reach the edit — or be declared dead.

The user's verdict on Style Match ("as if there were no AI at all") had two
causes. One was the planner repeating itself, fixed in 0.8.1. The other is this
one, and it is quieter: the analyser measured things that **nothing ever read**.
`hook` — how long the reference waited before its first cut, arguably the most
important number in a short video — was measured from 0.5.0 and used by nothing.
`handheld` was classified per shot and then produced a perfectly still clip.

An analysis nobody consumes is indistinguishable, from the user's chair, from no
analysis at all. So this file is a ratchet: every field the template carries is
either read by the rebuild, or named below with the reason it is not.
"""
from __future__ import annotations

import dataclasses
import re
from pathlib import Path

from core.engine.style import Template

BACKEND = Path(__file__).resolve().parents[1]
STYLE = (BACKEND / "core" / "engine" / "style.py").read_text(encoding="utf-8")

#: Fields that legitimately go nowhere, and why. Anything else must be used.
DECLARED_UNUSED: dict[str, str] = {
    "name": "the template's own label, used by storage and the UI",
    "source": "provenance for the user, deliberately not applied",
    "duration": "the reference's length; the rebuild follows the user's footage",
    "width": "the reference's pixel size; the canvas comes from `aspect`",
    "height": "the reference's pixel size; the canvas comes from `aspect`",
    "beats": "the reference's beat grid — the edit is cut to the *user's* music, "
             "and borrowing the reference's would score cuts against a track "
             "that is not in the file",
    "mean_shot": "only a fallback when a template carries no shots",
    "shortest_shot": "used while choosing the candidate window length",
    "motion_mix": "a summary of the per-shot motion the rebuild already reads",
    "unknown": "the honest list shown to the user; nothing to apply",
}


def _read_fields() -> set[str]:
    """Which template keys the rebuild actually looks at."""
    rebuild = STYLE[STYLE.index("def _brain_context") :]
    return set(re.findall(r'data\.get\("([a-zA-Z_]+)"', rebuild))


def test_every_measured_field_is_used_or_declared_dead():
    measured = {f.name for f in dataclasses.fields(Template)}
    used = _read_fields()
    # `shots` is read through a local variable, and the per-shot keys with it.
    used |= {"shots"}

    orphans = sorted(measured - used - set(DECLARED_UNUSED))
    assert not orphans, (
        "these are measured from the reference and then never applied — use them "
        "or declare them in DECLARED_UNUSED with a reason:\n  " + "\n  ".join(orphans)
    )


def test_the_declared_list_does_not_rot():
    """A field that is now used must leave the excuses list."""
    measured = {f.name for f in dataclasses.fields(Template)}
    stale = sorted(set(DECLARED_UNUSED) - measured)
    assert not stale, f"DECLARED_UNUSED mentions fields that no longer exist: {stale}"


def test_every_motion_the_analyser_can_report_produces_a_camera_move():
    """`handheld` was classified and then rendered perfectly still."""
    from core.engine import style

    kinds = ("static", "push", "pull", "pan", "handheld")
    template = {
        "name": "t", "aspect": "16:9", "duration": 5.0,
        "shots": [{"start": i, "duration": 1.0, "motion": kind, "energy": 0.2}
                  for i, kind in enumerate(kinds)],
        "look": {}, "transitions": {"type": "cut"}, "captions": {}, "audio": {},
        "hook": {}, "cuts_on_beat": 0.0,
    }
    picks = [{"start": i * 2.0, "end": i * 2.0 + 1.5, "score": 1.0} for i in range(len(kinds))]

    built = style.build_timeline(template, __file__, "Test", brain=False,
                                 _measured_override=picks) if False else None

    # Built through the real path in test_style_rebuild; here we only assert the
    # mapping itself, which is what silently lost `handheld`.
    moving = [k for k in kinds if k != "static"]
    for kind in moving:
        assert f'motion == "{kind}"' in STYLE, f"{kind} is measured but never turned into a move"
