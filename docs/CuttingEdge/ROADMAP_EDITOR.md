# Cutting Edge — Roadmap to CapCut / Filmora parity (100% open source)

Goal: a free desktop editor that a CapCut user can switch to without losing
capability. Everything below is achievable with permissively-licensed components
we can bundle; no paid API is required for any core feature.

Legend: ✅ done · 🔨 in progress · ⏳ planned

---

## 0. What exists today (0.2.1)

✅ FastAPI backend + SQLite job store · ✅ automatic clipping pipeline
(ingest → transcribe → select → reframe → export) · ✅ React/Electron shell with
super-app launcher UI · ✅ background work that survives navigation ·
✅ bundled FFmpeg + portable Python · ✅ auto-update channel.

---

## 1. Editing core — the part CapCut users judge first

| Capability | Approach | Status |
|---|---|---|
| Multi-track timeline (video/audio/text/sticker) | Edit model in `src/editor/model.ts`: `tracks → clips`, every mutation through a commit with undo/redo | 🔨 UI done in 0.2.3 (drag, trim, split, snap, zoom, shortcuts); persistence + SQLite next |
| Smooth scrubbing on long media | Generate **720p H.264 proxies** on import (`ffmpeg -vf scale`), edit against proxies, render from originals | ⏳ P1 |
| Frame-accurate preview | `<video>` + canvas compositor in the renderer; effects previewed with CSS/WebGL shaders, then baked by FFmpeg on export | ⏳ P1 |
| Razor / ripple / roll / slip trim, magnetic snapping | Pure model operations — no media touched until export | 🔨 razor + trim + magnetic snapping shipped; ripple/roll/slip next |
| Keyframes (position, scale, rotation, opacity, volume) | Bezier interpolation in the model → `sendcmd`/`zoompan`/`overlay` expressions at render | ⏳ P2 |
| Transitions (100+) | FFmpeg `xfade` (50+ built-ins) + custom GLSL pack | ⏳ P2 |
| Speed ramping, freeze frame, reverse | `setpts` / `atempo` curves | ⏳ P2 |
| Green screen + AI background removal | `chromakey` for green screen; **RobustVideoMatting** (MIT) or `rembg`/BiRefNet for matting | ⏳ P2 |
| Masks & blend modes | FFmpeg `alphamerge`, `blend` | ⏳ P3 |
| Motion tracking (attach text/sticker to object) | OpenCV trackers (CSRT/KCF) + MediaPipe landmarks | ⏳ P3 |
| Export presets (TikTok/Reels/Shorts/YouTube 4K) | Encoder profiles incl. **NVENC/QSV/AMF** hardware acceleration | ⏳ P1 |

## 2. AI features (all local-first, no subscription)

| Capability | Open-source stack | Status |
|---|---|---|
| Transcription + word-level captions | `faster-whisper` (CTranslate2) — already bundled | ✅ |
| Animated / kinetic captions | ASS renderer with per-word karaoke timing + template pack | 🔨 P1 |
| Auto-highlight ("find viral moments") | Local LLM via **Ollama** (Llama/Qwen) with cloud LLMs optional | 🔨 P1 |
| Silence & filler-word removal | Loudness/VAD (`silero-vad`) + transcript-driven cuts | ⏳ P1 |
| Auto-reframe with face/speaker tracking | MediaPipe FaceLandmarker + active-speaker heuristic, smoothed camera path | 🔨 P1 |
| Text-to-speech voice-over | `edge-tts` (free) and **Coqui XTTS v2** for local cloning | ⏳ P2 |
| Translation & dubbing | Whisper → **Argos Translate**/NLLB → XTTS, with lip-sync-free timing fit | ⏳ P3 |
| Music/vocal separation, auto-ducking | **Demucs** for stems, `sidechaincompress` for ducking, `pyloudnorm` for -14 LUFS | ⏳ P2 |
| Video upscaling / denoise | **Real-ESRGAN** / Real-CUGAN (NCNN builds, run on any GPU) | ⏳ P3 |
| Auto colour correction + LUTs | `colorbalance`/`curves` + free LUT pack, one-click "auto" via histogram analysis | ⏳ P2 |
| B-roll suggestion | Pexels/Pixabay APIs keyed by transcript topics | ⏳ P2 |
| Text-to-image assets | Optional local Stable Diffusion (ComfyUI) if the user has it | ⏳ P4 |

## 3. Content library (what makes CapCut sticky)

⏳ Template gallery (importable/exportable JSON project templates) · ⏳ title &
lower-third animation pack · ⏳ transition and sound-effect packs (CC0 sources:
Pixabay, Freesound) · ⏳ Google Fonts + Persian font pack · ⏳ user presets and
brand kits (logo, colours, caption style).

## 4. Workflow & publishing

⏳ Batch processing queue with priorities (backend already stage-based) ·
⏳ project files (`.ceproj`) with relative media paths · ⏳ auto-publish to
YouTube/Instagram/Facebook/TikTok with per-platform metadata · ⏳ scheduled
uploads · ⏳ analytics pull-back for published clips.

## 5. Performance targets

- Import 1080p/60 → proxy generation faster than realtime on 4 cores.
- Timeline scrub < 100 ms seek on proxies.
- Export 1 min 1080p in < 30 s with NVENC (< 90 s CPU-only).
- Idle RAM < 400 MB with the backend running.

---

## Suggested delivery order

1. **P1 — "usable editor"**: timeline model + proxies + preview + trim + export
   presets, animated captions, silence removal, real face-tracked reframe.
2. **P2 — "creator kit"**: keyframes, transitions, background removal, voice-over,
   audio mastering, B-roll, colour.
3. **P3 — "pro"**: motion tracking, masks, dubbing, upscaling, templates market.
4. **P4 — "ecosystem"**: plugins, local generative assets, mobile companion.

Each phase must keep the installer self-contained: models are downloaded on first
use into `~/CuttingEdge/models` so the base installer stays around 500 MB.
