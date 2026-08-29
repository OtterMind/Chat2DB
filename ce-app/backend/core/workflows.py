"""A local, n8n-flavoured automation layer (inspired by zie619/n8n-workflows).

n8n's transferable idea is not its 4 000 integrations but its *shape*: a trigger
feeds an ordered chain of nodes, each node does one job, the run is observable,
and the same chain can be fired manually, by a webhook, or on a schedule. This
module brings that shape to Cutting Edge **local-first**: the nodes are our own
engine steps (measure → template → build → captions → save/export), the trigger
is manual or a watched folder, and every run reports per-node progress through
the same task reporter the rest of the app uses.

Nothing here phones home; a "workflow" is plain JSON the user can read, save and
share — the same philosophy as `.cetemplate` and recipes.
"""
from __future__ import annotations

import threading
import time
from pathlib import Path

from app.config import settings

#: The node vocabulary. Each id maps to a single, testable engine step; the
#: frontend renders them as an n8n-style chain.
NODES = {
    "ingest": "probe the file (duration, aspect, audio)",
    "measure": "footage signals (speech/action/presence/emotion)",
    "template": "pick a rhythm template (starter or measured reference)",
    "build": "rebuild the footage in the template's shape",
    "captions": "transcribe + lay captions (optional, skippable)",
    "save": "store the result as a .ceproj",
    "export": "render the finished clip",
}

#: Preset chains, the way n8n ships templates. Each is a different edit over the
#: same nodes, chosen by intent.
PRESETS = [
    {"id": "volleyball", "en": "Volleyball highlight", "fa": "هایلایت والیبال",
     "nodes": ["ingest", "measure", "template", "build", "save"],
     "intent": {"kind": "sport", "energy": "high", "goal": "hook"},
     "desc_en": "sport pacing, cut on the beat, open on the crowd",
     "desc_fa": "ریتم ورزشی، برش روی ضرب، شروع با واکنش جمعیت"},
    {"id": "talking", "en": "Talking-head clean-up", "fa": "تمیزکاری گوینده",
     "nodes": ["ingest", "measure", "template", "build", "captions", "save"],
     "intent": {"kind": "talking_head", "goal": "story"},
     "desc_en": "jump-cut the pauses, captions on, speech-first",
     "desc_fa": "حذف مکث‌ها، زیرنویس روشن، اولویت گفتار"},
    {"id": "shorts", "en": "Shorts factory", "fa": "کارخانه‌ی شورت",
     "nodes": ["ingest", "measure", "template", "build", "captions", "save"],
     "intent": {"seconds": 30, "energy": "high", "platform": "tiktok"},
     "desc_en": "30 s vertical with an instant hook",
     "desc_fa": "۳۰ ثانیه عمودی با قلاب آنی"},
]

_VIDEO_EXT = {".mp4", ".mov", ".webm", ".mkv", ".avi"}


def list_presets() -> list[dict]:
    return [{**p, "nodes_meta": [{ "id": n, "label": NODES[n] } for n in p["nodes"]]}
            for p in PRESETS]


def _run_chain(preset: dict, path: str, reporter) -> dict:
    """Execute the node chain over one file, reporting each node."""
    from core.brain import intake  # noqa: PLC0415
    from core.engine import compose, style  # noqa: PLC0415

    out: dict = {"path": str(path), "nodes": []}

    def step(node: str, fraction: float, **extra) -> None:
        reporter.stage(node, fraction, node)
        out["nodes"].append({"id": node, **extra})

    if "ingest" in preset["nodes"]:
        info = compose.probe_media(str(path))
        step("ingest", 0.1, duration=info.get("duration"), aspect=info.get("aspect"))

    signals = None
    if "measure" in preset["nodes"]:
        signals = intake.measure_footage(str(path))
        step("measure", 0.25, speech=signals.get("speech_ratio"), action=signals.get("action"))

    template = None
    if "template" in preset["nodes"]:
        starters = style.starters()
        template = starters[0] if starters else None
        step("template", 0.4, template=(template or {}).get("name"))

    if "build" in preset["nodes"] and template is not None:
        built = style.build_timeline(
            template, str(path), name=f"{preset['id']}-{Path(path).stem}",
            intent=preset.get("intent"), captions=("captions" in preset["nodes"]),
        )
        out["timeline"] = built.get("timeline")
        out["summary"] = built.get("summary")
        step("build", 0.7, shots=(built.get("summary") or {}).get("shots"))

        if "save" in preset["nodes"]:
            from app.routers import projects  # noqa: PLC0415

            name = f"wf-{preset['id']}-{int(time.time())}"
            projects.save_project(projects.ProjectPayload(
                name=name, timeline=built.get("timeline"), view={}))
            out["project"] = name
            step("save", 0.9, project=name)
    return out


def run(preset_id: str, path: str, reporter) -> dict:
    preset = next((p for p in PRESETS if p["id"] == preset_id), None)
    if preset is None:
        raise ValueError(f"unknown workflow {preset_id}")
    if not Path(path).exists():
        raise FileNotFoundError(path)
    result = _run_chain(preset, path, reporter)
    from core import extensions  # noqa: PLC0415

    extensions.send_webhook("workflow_done", {"preset": preset_id, "path": str(path),
                                             "project": result.get("project")})
    return result


# ------------------------------------------------------------------ the watcher

_WATCH_STATE = {"thread": None, "stop": threading.Event(), "seen": set(), "dir": None}


def watch_status() -> dict:
    return {"active": bool(_WATCH_STATE["thread"] and _WATCH_STATE["thread"].is_alive()),
            "dir": _WATCH_STATE["dir"]}


def run_batch(directory: str, preset_id: str = "shorts") -> dict:
    """The scheduled-batch node: process every video in a folder right now."""
    from core.tasks import tasks  # noqa: PLC0415

    folder = Path(directory)
    processed = 0
    for video in sorted(p for p in folder.iterdir() if p.suffix.lower() in _VIDEO_EXT):
        def work(reporter, _v=video) -> dict:
            return run(preset_id, str(_v), reporter)

        tasks.start(f"workflow:{preset_id}", work)
        processed += 1
    return {"processed": processed, "dir": str(folder)}


def start_watch(directory: str, preset_id: str = "shorts", interval: float = 3.0) -> dict:
    """A scheduled/trigger node: new video drops in the folder → run the chain."""
    if watch_status()["active"]:
        return watch_status()
    folder = Path(directory)
    folder.mkdir(parents=True, exist_ok=True)
    _WATCH_STATE["stop"].clear()
    _WATCH_STATE["dir"] = str(folder)
    _WATCH_STATE["seen"] = {p for p in folder.iterdir() if p.suffix.lower() in _VIDEO_EXT}

    def loop() -> None:
        from core.tasks import tasks  # noqa: PLC0415

        while not _WATCH_STATE["stop"].wait(max(1.0, interval)):
            try:
                current = {p for p in folder.iterdir() if p.suffix.lower() in _VIDEO_EXT}
            except OSError:
                continue
            for new in sorted(current - _WATCH_STATE["seen"]):
                _WATCH_STATE["seen"].add(new)

                def work(reporter, _new=new) -> dict:
                    return run(preset_id, str(_new), reporter)

                tasks.start(f"workflow:{preset_id}", work)

    thread = threading.Thread(target=loop, daemon=True)
    _WATCH_STATE["thread"] = thread
    thread.start()
    return watch_status()


def stop_watch() -> dict:
    _WATCH_STATE["stop"].set()
    thread = _WATCH_STATE["thread"]
    if thread is not None:
        thread.join(timeout=2)
    _WATCH_STATE["thread"] = None
    return watch_status()
