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

#: What `heavy` actually means at install time: the extra pip packages the user
#: opts into. torch from PyPI is the CPU build (~120 MB wheel); CUDA builds live
#: on the PyTorch index and stay out of the pip-free installer.
#: torch's own wheel deps, spelled out because the packaged (pip-free) installer
#: extracts exactly this list and does *not* resolve transitive requirements.
#: Omitting them is the bug that made "Download + torch" produce a torch that
#: could not import in the installed app (ModuleNotFoundError: filelock/sympy/…).
_TORCH_FULL = ["torch", "torchaudio", "filelock", "typing-extensions", "sympy",
               "mpmath", "networkx", "jinja2", "markupsafe", "fsspec"]

HEAVY_DEPS: dict[str, list[str]] = {
    "torch": _TORCH_FULL,
    "torch+HF-token": _TORCH_FULL,
    "ncnn": [],
    "own": [],
    "tensorflow": [],
}

#: Accepted on-demand engines. `module` is what we import to use it; `deps` is the
#: explicit fetch list (no transitive resolution). `sdist: "build"` marks a dep
#: whose PyPI artefact is source that must be compiled (no wheel for us).
ENGINES: list[dict] = [
    {"id": "rife", "name": "RIFE", "repo": "hzwer/Practical-RIFE", "licence": "MIT",
     "role": "optical-flow slow-mo & smooth transitions for sports",
     "module": "rife_ncnn_vulkan_python", "deps": ["rife-ncnn-vulkan-python"],
     "heavy": "ncnn", "sdist": "build"},
    {"id": "transnet", "name": "TransNetV2", "repo": "soCzech/TransNetV2", "licence": "MIT",
     "role": "shot boundaries + cut/dissolve/fade typing for Style Match templates",
     # the PyPI wheel's top-level package is transnetv2_pytorch (verified by
     # unpacking the wheel); `transnet` would make available() lie forever.
     "module": "transnetv2_pytorch", "deps": ["transnetv2-pytorch"], "heavy": "torch"},
    {"id": "demucs", "name": "Demucs", "repo": "facebookresearch/demucs", "licence": "MIT",
     "role": "drums/vocals separation for beat-accurate ducking",
     "module": "demucs", "deps": ["demucs"], "heavy": "torch"},
    {"id": "mediapipe", "name": "MediaPipe Pose + Face", "repo": "google-ai-edge/mediapipe", "licence": "Apache-2.0",
     "role": "BlazeFace face detection + 33-point pose for an accurate reframe",
     # 1.x publishes no win_amd64 cp311 wheel (verified against PyPI); 0.10.21
     # is the newest release that does.
     "module": "mediapipe", "deps": ["mediapipe==0.10.21"], "heavy": "own"},
    {"id": "clip", "name": "CLIP / SigLIP", "repo": "openai/CLIP", "licence": "MIT / Apache-2.0",
     "role": "visual-semantic signal for highlight scoring",
     "module": "clip", "deps": ["ftfy", "regex"], "heavy": "torch"},
    {"id": "esrgan", "name": "Real-ESRGAN", "repo": "xinntao/Real-ESRGAN", "licence": "BSD-3",
     "role": "AI upscale when footage is below the target resolution",
     "module": "realesrgan", "deps": ["realesrgan"], "heavy": "torch"},
    {"id": "film", "name": "FILM", "repo": "google-research/frame-interpolation", "licence": "Apache-2.0",
     "role": "large-motion frame interpolation (after RIFE)",
     "module": "frame_interpolation", "deps": [], "heavy": "tensorflow"},
    {"id": "hazm", "name": "Hazm", "repo": "roshan-research/hazm", "licence": "MIT",
     "role": "Persian normalization/tokenization for captions + meaning",
     "module": "hazm", "deps": ["hazm"], "heavy": None},
    {"id": "virastar", "name": "Virastar", "repo": "mannaedu/virastar", "licence": "MIT",
     "role": "Persian text cleaning before subtitle burn-in",
     # verified 2026-08: no PyPI project under virastar / python-virastar /
     # virastar-py / persian-virastar (all 404) — repo-only. The built-in
     # persian.py cleaner plus optional Hazm already cover the role in-process.
     "module": "virastar", "deps": [], "heavy": None},
    {"id": "whisperx", "name": "whisperX", "repo": "m-bain/whisperX", "licence": "BSD-3",
     "role": "word-level timestamps for karaoke captions",
     "module": "whisperx", "deps": ["whisperx"], "heavy": "torch"},
    {"id": "dadmatools", "name": "DadmaTools", "repo": "Dadmatech/DadmaTools", "licence": "MIT",
     "role": "neural Persian NLP for meaning.py",
     "module": "dadmatools", "deps": ["dadmatools"], "heavy": "torch"},
    {"id": "hezar", "name": "Hezar", "repo": "hezaraiai/hezar", "licence": "Apache-2.0",
     "role": "all-in-one Persian ASR/normalization/OCR",
     "module": "hezar", "deps": ["hezar"], "heavy": "torch"},
    {"id": "pyannote", "name": "pyannote.audio", "repo": "pyannote/pyannote-audio", "licence": "MIT",
     "role": "speaker diarization for the objective",
     "module": "pyannote.audio", "deps": ["pyannote.audio"], "heavy": "torch+HF-token"},
    {"id": "python-ass", "name": "python-ass", "repo": "chireiden/python-ass", "licence": "MIT",
     "role": "programmatic ASS karaoke generation",
     "module": "ass", "deps": ["ass"], "heavy": None},
    {"id": "otio", "name": "OpenTimelineIO", "repo": "AcademySoftwareFoundation/OpenTimelineIO",
     "licence": "Apache-2.0", "role": "professional .otio interchange (Resolve et al.)",
     "module": "opentimelineio", "deps": ["OpenTimelineIO"], "heavy": None},
    {"id": "sentence-transformers", "name": "SentenceTransformers",
     "repo": "UKPLab/sentence-transformers", "licence": "Apache-2.0",
     "role": "multilingual transcript embeddings — narrative-arc sharpening",
     "module": "sentence_transformers", "deps": ["sentence-transformers"], "heavy": "torch"},
    {"id": "librosa", "name": "librosa", "repo": "librosa/librosa", "licence": "ISC",
     # Was REJECTED for its ~94 MB closure while shipped to everyone. On demand
     # the weight objection dissolves: only the user who wants chroma/onset
     # intelligence pays, and our spectral-flux detector stays the default.
     "role": "onset peaks + chroma tension for music-aware cutting",
     "module": "librosa", "deps": ["librosa"], "heavy": "numba/scipy"},
    {"id": "open-unmix", "name": "open-unmix", "repo": "sigsep/open-unmix-pytorch",
     "licence": "MIT", "role": "light vocals/music split (Demucs alternative)",
     # PyPI publishes this project under the name `umx` — "open-unmix" 404s,
     # which is why the shelf wrongly said "unavailable" (0.9.33 bug).
     "module": "umx", "deps": ["umx"], "heavy": "torch"},
    {"id": "dover", "name": "DOVER", "repo": "VQAssessment/DOVER", "licence": "MIT",
     "role": "post-export technical/aesthetic quality score for the taste loop",
     "module": "dover", "deps": ["dover"], "heavy": "torch+HF"},
]

#: Rejected, with the reason — the licence gate as a readable list.
REJECTED: list[dict] = [
    {"name": "YOLOv8", "licence": "AGPL-3.0", "why": "AGPL never enters the process"},
    {"name": "madmom", "licence": "NOASSERTION", "why": "models are CC-BY-NC (non-commercial)"},
    {"name": "gl-transitions", "licence": "NOASSERTION", "why": "no single licence; needs a custom FFmpeg build"},
    {"name": "Remotion", "licence": "NOASSERTION", "why": "commercial licensing for companies"},
    {"name": "DeepFilterNet", "licence": "NOASSERTION", "why": "licence unclear until upstream clarifies"},
    # librosa left this list in 0.9.30: on-demand it ships to nobody by default,
    # so the closure-weight objection no longer applies (see ENGINES).
    {"name": "Essentia", "licence": "AGPL-3.0",
     "why": "AGPL binds even an on-demand in-process link — key/BPM/mood stay "
            "with our FFmpeg measurements instead"},
    {"name": "CineTrans / ffmpeg-gl-transition", "licence": "NO-LICENSE", "why": "no licence"},
]


def _pip_available() -> bool:
    from core import runtime_packages  # noqa: PLC0415

    return runtime_packages._pip_available()


_PROBE_CACHE: dict[str, dict] = {}


def probe(engine: dict) -> dict:
    """Can this engine actually be downloaded *on this machine*, and why (not)?

    Verified against PyPI rather than assumed; cached per process so the
    Settings card pays for the lookups once. A dead registry must show a reason,
    never a button that dies.
    """
    if engine["id"] in _PROBE_CACHE:
        return _PROBE_CACHE[engine["id"]]
    from core.engine import _pypi  # noqa: PLC0415

    if not engine["deps"]:
        out = {"fetchable": False,
               "why": "repo-only: no pip package published (see the repository)"}
    elif engine.get("sdist") == "build" and not _pip_available():
        out = {"fetchable": False,
               "why": "source-only on PyPI (needs a C++ toolchain); no prebuilt "
                      "wheel for this Python — the packaged runtime cannot build it"}
    else:
        kinds = {_pypi.classify(_pypi.parse_name(spec)) for spec in engine["deps"]}
        if "none" in kinds:
            out = {"fetchable": False, "why": "a dependency is missing from PyPI"}
        elif "sdist" in kinds and engine.get("sdist") != "build":
            out = {"fetchable": True,
                   "why": "source-only on PyPI; installed by the pip-free extractor "
                          "when pure Python"}
        else:
            out = {"fetchable": True, "why": ""}
    if engine.get("heavy") in ("torch", "torch+HF-token"):
        out["heavy_note"] = ("needs torch (~120 MB CPU wheels from PyPI, opt-in)")
    _PROBE_CACHE[engine["id"]] = out
    return out


def bulk_install_plan() -> dict:
    """The one-click list, as ordered stages: torch once, then each engine alone.

    Engines that need an HF token (pyannote) are excluded — a bulk download must
    not stall on a licence the user has to accept by hand; FILM (tensorflow) and
    source-only-C++ engines (rife) are excluded because the packaged runtime can't
    build them. Each engine is its **own stage** so one failure reports itself and
    the rest still install — a batch must not die because of one wheel.
    """
    groups: list[dict] = []
    ids: list[str] = []
    all_deps: list[str] = []
    for engine in ENGINES:
        if not engine.get("deps"):
            continue
        if engine.get("heavy") in ("torch+HF-token", "tensorflow"):
            continue
        if engine.get("sdist") == "build":
            continue  # needs a C++ toolchain; not buildable in the packaged app
        needs_torch = engine.get("heavy") == "torch"
        ids.append(engine["id"])
        # Torch rides along ONLY for engines that actually run on it; a light
        # engine (mediapipe, hazm, otio…) downloads without dragging 120 MB in.
        own = (list(HEAVY_DEPS["torch"]) if needs_torch else []) + list(engine["deps"])
        groups.append({"id": engine["id"], "deps": own, "needs_torch": needs_torch})
        for dep in own:
            if dep not in all_deps:
                all_deps.append(dep)
    return {"torch_deps": list(HEAVY_DEPS["torch"]), "groups": groups,
            "ids": ids, "deps": all_deps}


def status() -> dict:
    """Which engines are present on this machine, their licence, and whether they
    can be fetched here — the download button is only offered when it can win."""
    out = []
    for engine in ENGINES:
        try:
            installed = importlib.util.find_spec(engine["module"]) is not None
        except (ModuleNotFoundError, ValueError):
            # A dotted module whose parent is absent raises instead of returning
            # None; absent is absent.
            installed = False
        out.append({**engine, "installed": installed, **probe(engine)})
    return {"engines": out, "rejected": REJECTED}
