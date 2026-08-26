# BRAIN UPGRADE — triage of the two professors' blueprints (0.9.30-dev)

Both blueprints were read line by line. Every item lands in one of three buckets:
**in** (built this release), **door** (registered as an on-demand engine,
integration waits for a fetched runtime), or **no** (rejected/deferred, with the
reason — nothing is dropped silently). The project's invariants gate everything:
licence read from the wheel METADATA; GPL/AGPL never in-process; on-demand
engines degrade; every signal is a number; the rule plan is the race floor; the
LLM returns indices only; five keyframe channels; preview is the compositor's
twin.

## In — built and tested in 0.9.30-dev

| item (source) | where | the honest version |
|---|---|---|
| FeatureBus (both) | `core/engine/features.py` | stdlib dataclass; `extract_all_sensors` is the single door; `unknown` names the gaps |
| motion curve + `keep = speech OR motion` (auto-editor, Public Domain) | `analyze.motion_curve`, `motion_keep_ranges` | one FFmpeg decode, normalised 0..1, tested on a burst fixture |
| narrative arc / payoff / Q→A (SentenceTransformers idea, FunClip idea) | `brain/meaning.narrative_arc` | markers, offline; the objective term `narrative_arc` reads it; embeddings may sharpen later |
| new planners narrative/retention/variety (blueprint 2 §2.1) | `brain/planners.py` | emit only measured times; skip themselves without their signal; all on the scoreboard |
| critic loop (blueprint 2 §2.3; LangGraph's loop, bounded) | `brain/critic.py` + race | ≤2 iterations; replaces only bottom-quantile picks with unused highlights; floor = rule plan; scoreboard line `…+critic` |
| new objective terms (blueprint 2 §2.4) | `brain/objective.py` | `narrative_arc`, `platform_pacing`, `visual_variety`, weight 1 each, skip-if-unmeasured, renormalise |
| taste memory (ChromaDB idea, ReelBrain idea) | `brain/memory.py` + `/api/brain/feedback` | JSON file, stdlib; prior clamped 0.75–1.33; accepted-only for now, reject door waits for a thumbs UI (stated, not hidden) |
| frontend ↔ brain feedback (blueprint 1 layer 4) | StyleMatch `tellBrainAccepted` | opening/finishing an edit reports accepted with the winner's term breakdown |
| contact sheet, blend ≤0.3 (blueprint 2 §1.2) | `vision.contact_sheet_times/score_contact_sheet` | 4 frames per window; `MAX_WEIGHT` was already 0.3 |
| GLM-4V thinking-family transparency (blueprint 1) | `ai.py` CATALOGUE `glm-4v:9b` | the planner prompt already demands a "why" that rides the scoreboard |
| semantic embedding plumbing (CLIP/open_clip, blueprint 2 §1.1) | `core/engine/clip_embed.py` | thin bridge; concepts from intent; absent → None and the blend renormalises |

## Door — registered on-demand, integration when fetched

| engine | licence | note |
|---|---|---|
| sentence-transformers (paraphrase-multilingual-MiniLM) | Apache-2.0 | sharpens `narrative_arc` when torch is present |
| librosa | ISC | **moved out of REJECTED**: shipped-to-nobody was the objection; on demand the ~94 MB closure is the user's choice; our spectral-flux detector stays default |
| open-unmix | MIT | light Demucs alternative for stems |
| DOVER | MIT | post-export quality score for the taste loop |

## No — rejected or deferred, with the reason

| item | reason |
|---|---|
| **Essentia** | AGPL-3.0 binds even an on-demand in-process link; key/BPM/mood stay with our FFmpeg measurements |
| **LangGraph** (as a dependency) | the *architecture* is adopted natively (`critic.py`); the package would add the langchain tree for a loop we can write in 80 tested lines; unbounded agents also violate "race, not infinite agent" |
| **ChromaDB** (as a dependency) | a single-user desktop remembers a few hundred decisions in a JSON file; the closure (onnxruntime, tokenizers…) is tax without value |
| **InternVL 3.5 / VideoLLaMA 3 / Molmo** | HF-gated multi-GB weights; the *roles* are covered by catalogued Ollama vision models (contact sheet), MediaPipe pose, and CLIP embeddings; revisit when a one-file ONNX lands |
| **GLM-4.1V-9B-Thinking in-process** | served via Ollama only (catalogued as glm-4v:9b) — never a pip torch install |
| **RTMPose/MMPose, ByteTrack** | mmcv build closure; MediaPipe (on-demand) already owns the pose slot |
| **YOLO-World / YOLO-NAS** | weight licences/hosts unverified; ultralytics stays AGPL-rejected |
| **YuNet+FER emotion peaks** | model provenance/licence mixed; emotion proxies stay markers+energy |
| **pyAudioAnalysis** | scipy/sklearn closure for signals our VAD+envelope already measure; emotion-from-audio is low-confidence science — deferred, not banned |
| **CLIP aesthetic head / DOVER-in-loop** | aesthetic scoring deferred until DOVER fetch exists; crop selection stays subject-first |

## The golden rule, as enforced here

register in `runtime_packages`-backed registry ✓ · licence from wheel METADATA ✓
· absent → degrade to previous behaviour, never crash ✓ · every result measurable
by a fixture test ✓ · progress narrated over `/ws` by the existing task stages ✓.
