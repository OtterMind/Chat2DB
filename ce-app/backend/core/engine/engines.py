"""The on-demand engine registry.

Every optional AI engine the project has accepted lives here as a declaration:
its name, upstream repo, **verified licence**, what it adds, and the packages the
on-demand installer must fetch. Nothing in this list is shipped in the installer;
each engine is fetched to `~/CuttingEdge/runtime/py` when the user asks, and every
consumer degrades gracefully when an engine is absent — so a machine without a
GPU, a torch, or a Hugging-Face token still runs the whole app.

The REJECTED set is kept here on purpose, with the reason, so the licence gate is
a list a reviewer can read — not a memory. GPL/AGPL/no-licence engines never
enter the install path.
"""
from __future__ import annotations

import importlib.util

#: Accepted on-demand engines. `module` is what we import to use it; `deps` is the
#: explicit fetch list (no transitive resolution).
ENGINES: list[dict] = [
    {"id": "rife", "name": "RIFE", "repo": "hzwer/Practical-RIFE", "licence": "MIT",
     "role": "optical-flow slow-mo & smooth transitions for sports",
     "module": "rife_ncnn_vulkan_python", "deps": ["rife-ncnn-vulkan-python"], "heavy": "ncnn"},
    {"id": "whisperx", "name": "whisperX", "repo": "m-bain/whisperX", "licence": "BSD-3",
     "role": "word-level timestamps for karaoke + speech integrity",
     "module": "whisperx", "deps": ["whisperx"], "heavy": "torch"},
    {"id": "transnet", "name": "TransNetV2", "repo": "soCzech/TransNetV2", "licence": "MIT",
     "role": "cut/dissolve/fade typing for accurate Style Match templates",
     "module": "transnet", "deps": ["transnetv2-pytorch"], "heavy": "torch"},
    {"id": "demucs", "name": "Demucs", "repo": "facebookresearch/demucs", "licence": "MIT",
     "role": "drums/vocals separation for beat-accurate ducking",
     "module": "demucs", "deps": ["demucs"], "heavy": "torch"},
    {"id": "mediapipe", "name": "MediaPipe Pose", "repo": "google-ai-edge/mediapipe", "licence": "Apache-2.0",
     "role": "33-point pose landmarks for sports reframe",
     "module": "mediapipe", "deps": ["mediapipe"], "heavy": "own"},
    {"id": "clip", "name": "CLIP / SigLIP", "repo": "openai/CLIP", "licence": "MIT / Apache-2.0",
     "role": "visual-semantic signal for highlight scoring",
     "module": "clip", "deps": ["ftfy", "regex"], "heavy": "torch"},
    {"id": "esrgan", "name": "Real-ESRGAN", "repo": "xinntao/Real-ESRGAN", "licence": "BSD-3",
     "role": "AI upscale when footage is below the target resolution",
     "module": "realesrgan", "deps": ["realesrgan"], "heavy": "torch"},
    {"id": "pyannote", "name": "pyannote.audio", "repo": "pyannote/pyannote-audio", "licence": "MIT",
     "role": "speaker diarization signal for the objective",
     "module": "pyannote.audio", "deps": ["pyannote.audio"], "heavy": "torch+HF-token"},
    {"id": "film", "name": "FILM", "repo": "google-research/frame-interpolation", "licence": "Apache-2.0",
     "role": "large-motion frame interpolation (after RIFE)",
     "module": "frame_interpolation", "deps": [], "heavy": "tensorflow"},
    {"id": "otio", "name": "OpenTimelineIO", "repo": "AcademySoftwareFoundation/OpenTimelineIO",
     "licence": "Apache-2.0", "role": "professional .otio interchange (Resolve et al.)",
     "module": "opentimelineio", "deps": ["OpenTimelineIO"], "heavy": None},
]

#: Rejected, with the reason — the licence gate as a readable list.
REJECTED: list[dict] = [
    {"name": "YOLOv8", "licence": "AGPL-3.0", "why": "AGPL never enters the process"},
    {"name": "madmom", "licence": "NOASSERTION", "why": "models are CC-BY-NC (non-commercial)"},
    {"name": "gl-transitions", "licence": "NOASSERTION", "why": "no single licence; needs a custom FFmpeg build"},
    {"name": "Remotion", "licence": "NOASSERTION", "why": "commercial licensing for companies"},
    {"name": "DeepFilterNet", "licence": "NOASSERTION", "why": "licence unclear until upstream clarifies"},
    {"name": "librosa", "licence": "ISC", "why": "~94 MB closure; our beat detection already works"},
    {"name": "CineTrans / ffmpeg-gl-transition", "licence": "NO-LICENSE", "why": "no licence"},
]


def status() -> dict:
    """Which engines are present on this machine, and their licence."""
    out = []
    for engine in ENGINES:
        try:
            installed = importlib.util.find_spec(engine["module"]) is not None
        except (ModuleNotFoundError, ValueError):
            # A dotted module whose parent is absent raises instead of returning
            # None; absent is absent.
            installed = False
        out.append({**engine, "installed": installed})
    return {"engines": out, "rejected": REJECTED}
