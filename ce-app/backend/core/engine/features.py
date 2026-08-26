"""The FeatureBus: every sensor writes here, the brain only reads from here.

The advisors' blueprints (docs/CuttingEdge/BRAIN_UPGRADE.md) agree on one
architecture: modules stop talking through ad-hoc calls and meet on a shared,
honest feature store. This is the minimal, tested version of that idea — a
dataclass with one field per sensor and an `unknown` list for the gaps, because
a bus that hides what it did not measure is worse than no bus.

`extract_all_sensors` is the single door: ingest → sensors → bus → race →
build_timeline. A sensor that fails or is absent leaves its field empty and
names itself in `unknown`; downstream terms renormalise rather than fake.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from core.brain.objective import Pick


@dataclass
class FeatureBus:
    path: str = ""
    duration: float = 0.0
    scenes: list[float] = field(default_factory=list)
    silences: list[tuple[float, float]] = field(default_factory=list)
    speech: list[tuple[float, float]] = field(default_factory=list)
    speech_ratio: float = 0.0
    beats: list[float] = field(default_factory=list)
    bpm: float = 0.0
    words: list[dict] = field(default_factory=list)
    #: Motion activity, 0..1 over time (auto-editor-inspired, §1.6).
    motion_curve: list[float] = field(default_factory=list)
    action: float = 0.0
    presence: float = 0.0
    highlights: list[Pick] = field(default_factory=list)
    #: Per-window semantic/visual extras, present only when an engine is fetched.
    embeddings: dict = field(default_factory=dict)
    vision_scores: dict = field(default_factory=dict)
    speakers: list[dict] = field(default_factory=list)
    narrative: dict = field(default_factory=dict)
    #: Honest gaps — sensors that could not run on this machine.
    unknown: list[str] = field(default_factory=list)


def extract_all_sensors(path: str, *, with_transcript: bool = False) -> FeatureBus:
    """Run every offline sensor once; on-demand senses fill in later, elsewhere.

    Everything here is bundled and fast (FFmpeg + NumPy + OpenCV). Engines that
    need torch/HF are deliberately NOT in this door — they refine the bus after
    it exists, and their absence is a named gap, not an error.
    """
    from core.engine import analyze, audio, compose, style  # noqa: PLC0415

    bus = FeatureBus(path=path)
    try:
        info = compose.probe_media(path)
        bus.duration = float(info.get("duration", 0) or 0)
    except Exception:  # noqa: BLE001
        bus.unknown.append("probe")
        return bus

    try:
        bus.scenes = analyze.detect_scenes(path)
    except Exception:  # noqa: BLE001
        bus.unknown.append("scenes")
    try:
        silences = analyze.detect_silence(path)
        bus.silences = [(r.start, r.end) for r in silences]
        bus.speech = _complement(bus.silences, bus.duration)
        spoken = sum(b - a for a, b in bus.speech)
        bus.speech_ratio = round(spoken / bus.duration, 3) if bus.duration else 0.0
    except Exception:  # noqa: BLE001
        bus.unknown.append("speech")
    try:
        result = audio.beats(path)
        bus.beats = result.beats
        bus.bpm = result.bpm
    except Exception:  # noqa: BLE001
        bus.unknown.append("beats")
    try:
        bus.motion_curve = analyze.motion_curve(path)
    except Exception:  # noqa: BLE001
        bus.unknown.append("motion")
    try:
        peak, presence = style._coarse_action(path, bus.duration)
        bus.action, bus.presence = float(peak), float(presence)
    except Exception:  # noqa: BLE001
        bus.unknown.append("action")
    if with_transcript:
        try:
            from core.engine import transcribe  # noqa: PLC0415

            done = transcribe.transcribe_to_cues(path)
            bus.words = done.get("words", [])
        except Exception:  # noqa: BLE001
            bus.unknown.append("transcript")
    return bus


def _complement(ranges: list[tuple[float, float]], total: float) -> list[tuple[float, float]]:
    out: list[tuple[float, float]] = []
    clock = 0.0
    for start, end in sorted(ranges):
        if start > clock:
            out.append((round(clock, 3), round(start, 3)))
        clock = max(clock, end)
    if total > clock:
        out.append((round(clock, 3), round(total, 3)))
    return out
