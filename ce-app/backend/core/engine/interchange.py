"""OpenTimelineIO interchange — open the edit in Resolve, and read one back.

OTIO is the industry's editorial interchange format (Apache-2.0), so a Cutting
Edge timeline can leave for DaVinci/Premiere and a foreign timeline can come
home. It is an on-demand engine: `opentimelineio` is fetched when the user asks,
and every entry point raises a clean "not installed" otherwise — a machine that
never touches a pro NLE never pays for the dependency.

Only the video lane round-trips; transitions/keyframes/effects are Cutting-Edge
concepts with no lossless OTIO home yet, so they are dropped on export rather
than faked.
"""
from __future__ import annotations

from pathlib import Path


class OtioNotInstalled(RuntimeError):
    pass


def available() -> bool:
    import importlib.util  # noqa: PLC0415

    return importlib.util.find_spec("opentimelineio") is not None


def _otio():
    if not available():
        raise OtioNotInstalled("OpenTimelineIO is not fetched — fetch it in Settings")
    import opentimelineio as otio  # noqa: PLC0415

    return otio


def export_otio(timeline: dict, path: str, name: str = "Cutting Edge") -> str:
    """Write the video lane of our edit model to a .otio file."""
    otio = _otio()
    tl = otio.schema.Timeline(name=name)
    track = otio.schema.Track(name="Video 1", kind=otio.schema.TrackKind.Video)
    for clip in [c for c in timeline.get("clips", []) if c.get("trackId") == "v1"]:
        item = otio.schema.Clip(
            name=str(clip.get("label") or clip.get("id")),
            media_reference=otio.schema.ExternalReference(
                target_url=str(clip.get("src") or "")
            ),
            source_range=otio.opentime.TimeRange(
                start_time=otio.opentime.RationalTime(float(clip.get("offset", 0)) * 30, 30),
                duration=otio.opentime.RationalTime(float(clip.get("duration", 0)) * 30, 30),
            ),
        )
        track.append(item)
    tl.tracks.append(track)
    otio.adapters.write_to_file(tl, str(path))
    return str(path)


def import_otio(path: str) -> dict:
    """Read a foreign .otio back into our edit model (video lane)."""
    otio = _otio()
    if not Path(path).exists():
        raise FileNotFoundError(path)
    tl = otio.adapters.read_from_file(str(path))

    clips: list[dict] = []
    cursor = 0.0
    for track in tl.tracks:
        if track.kind != otio.schema.TrackKind.Video:
            continue
        for item in track:
            if not isinstance(item, otio.schema.Clip):
                continue
            duration = item.source_range.duration.value / max(1, item.source_range.duration.rate)
            offset = item.source_range.start_time.value / max(1, item.source_range.start_time.rate)
            src = ""
            ref = getattr(item, "media_reference", None)
            if ref is not None:
                src = str(getattr(ref, "target_url", "") or "")
            clips.append({
                "id": f"i{len(clips)}", "trackId": "v1",
                "start": round(cursor, 3), "duration": round(duration, 3),
                "offset": round(offset, 3), "src": src,
                "label": item.name or f"clip {len(clips) + 1}",
                "color": "#6366F1", "props": {"adjust": {}},
            })
            cursor += duration
        break  # first video track only

    return {
        "tracks": [{"id": "v1", "kind": "video", "name": "Video 1",
                    "muted": False, "locked": False}],
        "clips": clips,
        "transitions": [],
    }
