"""The professional-editor brain must know its tools and choose like a human."""
from __future__ import annotations

from core.brain import editor_brain


def _template(**over):
    base = {"bpm": 120.0, "speech_ratio": 0.6,
             "shots": [{"duration": 1.0}] * 8}
    base.update(over)
    return base


def test_the_brain_knows_every_tool():
    ids = {t["id"] for t in editor_brain.TOOLS}

    assert {"beat_cuts", "slowmo", "captions", "karaoke", "ducking", "reframe",
            "grade", "transitions", "hook_first", "denoise",
            # newly-built capabilities must be in the toolbelt too (standing convention)
            "fillers", "persian_norm", "motion_transition", "interchange"} <= ids


def test_each_tool_gets_its_own_decision_with_a_reason():
    assessment = editor_brain.assess(_template(), {"speech_ratio": 0.6, "action": 0.7, "presence": 0.6},
                                     {"kind": "sport"})

    assert len(assessment) == len(editor_brain.TOOLS)
    for decision in assessment:
        assert isinstance(decision["use"], bool)
        assert decision["reasonFa"] and decision["reasonEn"]


def test_newly_built_tools_decide_on_their_measured_signals():
    def use(a, tool):
        return next(x for x in a if x["tool"] == tool)["use"]

    # Persian talking-head with a handoff and high motion at junctions
    rich = editor_brain.assess(
        _template(),
        {"speech_ratio": 0.6, "action": 0.2, "presence": 0.2, "motion": 0.7},
        {"kind": "talking_head", "language": "fa", "finish_elsewhere": True})
    assert use(rich, "persian_norm")      # Persian captions -> normalise
    assert use(rich, "fillers")           # unscripted talk -> trim fillers
    assert use(rich, "motion_transition") # high motion at junctions -> RIFE dissolves
    assert use(rich, "interchange")       # handoff asked -> export OTIO

    # A sport with no speech, no handoff, calm junctions
    lean = editor_brain.assess(
        _template(),
        {"speech_ratio": 0.05, "action": 0.8, "presence": 0.6, "motion": 0.1},
        {"kind": "sport", "language": "en"})
    assert not use(lean, "persian_norm")
    assert not use(lean, "fillers")
    assert not use(lean, "motion_transition")
    assert not use(lean, "interchange")


def test_a_sport_with_peaks_gets_slowmo_and_hook_but_a_talk_does_not():
    sport = editor_brain.assess(_template(), {"speech_ratio": 0.3, "action": 0.8, "presence": 0.7},
                                {"kind": "sport"})
    talk = editor_brain.assess(_template(bpm=0), {"speech_ratio": 0.8, "action": 0.1, "presence": 0.2},
                               {"kind": "talking_head"})

    def use(a, tool):
        return next(x for x in a if x["tool"] == tool)["use"]

    assert use(sport, "slowmo") and use(sport, "hook_first") and use(sport, "beat_cuts")
    assert not use(talk, "slowmo") and not use(talk, "beat_cuts")
    assert use(talk, "captions") and use(talk, "ducking")


def test_grade_is_always_on_and_denoise_honest():
    assessment = editor_brain.assess(_template(), {"speech_ratio": 0.5}, {})

    assert next(x for x in assessment if x["tool"] == "grade")["use"] is True
    assert next(x for x in assessment if x["tool"] == "denoise")["use"] is False


def test_notes_only_list_tools_that_will_be_used():
    assessment = editor_brain.assess(_template(), {"speech_ratio": 0.6}, {})
    notes = editor_brain.notes(assessment, "fa")
    used = [a["fa"] for a in assessment if a["use"]]

    assert len(notes) == len(used)
