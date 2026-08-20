# Cutting Edge (CE) — Master Prompt / Project Map

> Single source of truth for anyone (human or agent) picking this project up.
> **Everything below was verified against the code in `ce-app/`** — where an earlier
> hand-off note disagreed with the repository, the repository wins.

---

## 1. Product

An open-source **Windows desktop studio** that turns long-form video into short
vertical (9:16) clips with AI: ingest → transcribe → select highlights → reframe →
subtitle → export → upload.

Inspiration: `opensource-clipping` (smart B-roll, cinematic hooks, auto-uploader),
`artbyjazi/autoclip` (face tracking, ASS subtitle styling, job management),
`calesthio/OpenMontage` (agentic pipeline architecture).

---

## 2. Architecture

```
ce-app/
├── backend/                 FastAPI, SQLite, port 8742
│   ├── app/
│   │   ├── main.py          app factory, CORS, /api/health, /ws
│   │   ├── config.py        pydantic-settings; ~/CuttingEdge/{work,exports,data}
│   │   ├── database.py      SQLite schema: jobs / stages / clips / settings
│   │   ├── routers/         jobs.py · clips.py · system.py · uploads.py
│   │   ├── services/        pipeline.py — PipelineOrchestrator (stage runner)
│   │   └── websocket/       job_events.py — live progress broadcast
│   ├── core/engine/         ingest.py (yt-dlp + ffprobe) · transcribe.py
│   │                        (faster-whisper) · export.py (ffmpeg)
│   ├── uploaders/           YouTube / Facebook (scaffold)
│   └── run_backend.py       packaged entry point (uvicorn, no reload)
├── frontend/                React 18 + Vite 5 + Ant Design 5 + Electron 31
│   ├── src/pages/           Dashboard · NewJob · JobDetail · ClipReview ·
│   │                        Uploads · Settings · Doctor
│   ├── src/api/             axios client · jobs · websocket
│   ├── electron/            main.ts (spawns backend, wires FFmpeg) ·
│   │                        preload.ts (contextBridge `window.cuttingEdge`) ·
│   │                        updater.ts (electron-updater)
│   ├── build-assets/        icon.ico — "CE" monogram, violet→cyan on slate
│   └── scripts/             before-pack.js — makes the packaged backend portable
├── scripts/                 build-installer-local.ps1 · install-ffmpeg.bat
└── docs/BUILD_INSTALLER.md  how the installer is produced
```

**Runtime contract:** the renderer talks to `http://127.0.0.1:8742` (`/api/*`, `/ws`);
in dev, Vite proxies both. Electron starts the bundled backend unless
`CE_MANUAL_BACKEND=1`.

**Pipeline stages** (`PipelineOrchestrator`, each row written to `stages` with
progress broadcast over the WebSocket): `ingest → prepare → transcribe → select →
reframe → subtitle → export`. Failures mark both the stage and the job as `failed`
with the error text; transcription degrades gracefully when the model is absent.

**Design system:** dark theme, background Slate-900 `#0F172A`, accent Indigo→Violet,
RTL-ready (`<html lang="fa" dir="rtl">`).

---

## 3. Stack

| Concern | Choice |
|---|---|
| Video | FFmpeg + ffprobe (bundled), `scenedetect` 0.6.4 |
| Transcription | `faster-whisper` (CTranslate2) |
| Vision | MediaPipe 0.10.14, OpenCV 4.10 |
| LLM selection | Gemini · Claude · OpenAI · Ollama (pluggable, keys in Settings) |
| TTS / B-roll | `edge-tts`, Pexels API |
| Storage | SQLite at `~/CuttingEdge/data/cuttingedge.db` |
| Packaging | electron-builder NSIS + embeddable CPython 3.11.9 |

---

## 4. Where the project actually stands

**Done (Foundation + Core pipeline)**
- 54 source files, backend + frontend + Electron shell + auto-update code.
- E2E smoke test: a 20 s input produced three ~6.7 s standard-format clips.
- CI packaging path repaired end to end (see `docs/BUILD_INSTALLER.md`).

**Corrections to the earlier hand-off note** ⚠️
1. `ce-app/` is **not on `main`** — it was pushed only to session branches. It now
   lives on **`arena/01a0214a-chat2db`**, with PR **#5** open against `main`.
   Any workflow run on `main` fails immediately.
2. `PySceneDetect==0.6.4` is **not a PyPI project**; the correct requirement is
   `scenedetect==0.6.4`. `pexels-api==1.0.2` does not exist either (1.0.1 is the
   latest). Both bad pins made run #5 fail — pip aborted, the CI step still went
   green, and packaging died on `No module named 'fastapi'`.
3. `PUSH_TO_GITHUB.md` and the original `MASTER_PROMPT.md` were never committed to
   this repository; this file replaces them.
4. Face tracking is **not** wired to `MediaPipe FaceLandmarker` yet — reframing is
   centre-crop.

**Next: Phase 2 → 5**
- **Phase 2 — Advanced features:** Pexels B-roll insertion, voice-over
  (LLM script + `edge-tts`), kinetic-typography subtitles (ASS styling).
- **Phase 3 — AI polish:** MediaPipe FaceLandmarker-driven reframing with smoothing
  and speaker switching; better highlight scoring prompts.
- **Phase 4 — Uploaders:** live YouTube (OAuth device flow) and Facebook publishing,
  scheduling, per-clip metadata.
- **Phase 5 — Packaging:** signed installer, first-run doctor checks, Windows 10/11
  verification, delta auto-updates.
- **Phase 6 — Polish:** localisation, telemetry-free diagnostics, docs.

---

## 5. Working agreements

- Ship a **working** artifact: never let a build stay green while the packaged app
  is broken (that is exactly what `before-pack.js` guards against).
- Pin dependencies, and verify pins resolve on PyPI for `cp311 / win_amd64`.
- Keep the backend importable without heavy optional models (graceful degradation).
- Build outputs (`dist/`, `dist-electron/` sources aside, `release/`, `build/`) stay
  out of Git except the small prebuilt Electron bundles CI relies on.
