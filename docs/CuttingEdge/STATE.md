# Cutting Edge — current state

**Read this first.** Working sessions are wiped every few hours; this file, the
code and the docs next to it are the only things that survive. Everything below is
verified, not planned.

Branch: `arena/01a0214a-chat2db` · App version: `0.2.3` · Last released: `v0.2.2`

---

## 1. What is actually in the product

| Area | State |
|---|---|
| Backend | FastAPI + SQLite on port **8742**; job pipeline (ingest → prepare → transcribe → select → reframe → subtitle → export) |
| **Render engine** | `core/engine/compose.py` — the edit model becomes one FFmpeg `filter_complex`; NVENC when present, libx264 otherwise; progress streamed over the WebSocket |
| **Auto-edit** | `core/engine/analyze.py` — silence detection (FFmpeg `silencedetect`) and scene detection (PySceneDetect, FFmpeg fallback) |
| Frontend | React 18 + Vite + Electron 31, super-app launcher home, 8 screens, one shared `Page` shell |
| **Preview** | Real video in the program monitor, streamed through `/api/media/file` with Range support so scrubbing works from a `file://` page |
| **Export** | Format (9:16 / 1:1 / 4:5 / 16:9 / 4K), quality preset, frame rate, and a native save dialog |
| **Editor** | Multi-track timeline: drag between lanes, trim, split (S), duplicate, snap, zoom, undo/redo, import, export, remove silence, split scenes |
| **Tool rail** | Context-sensitive bottom toolbar (global set / clip set) with nested panels: speed, volume + fades, crop, transform, opacity, rotate, freeze, reverse, mute, duplicate, replace, delete |
| **Transitions** | 28 real `xfade` types with adjustable duration, created from the clip rail or the junction marker between two clips; audio crossfades with them |
| Window | Fullscreen via the header button, **F11** to toggle and **Escape** to leave; works in the browser preview too |
| **Languages** | English default + Persian, switch in Settings, flips LTR/RTL instantly, persisted |
| Packaging | NSIS installer, embeddable CPython 3.11, bundled FFmpeg + ffprobe |
| Auto-update | One button: check → differential download → install; silent check at startup |
| Diagnostics | electron-log to `%APPDATA%\Cutting Edge\logs`, "open log folder" in the app |

### Third-party libraries actually shipping

`faster-whisper` (transcription) · `scenedetect` (shot detection) · `opencv-python`
and `mediapipe` (vision) · `Pillow` · `yt-dlp` · `edge-tts` · `@fontsource/vazirmatn`
(offline font) · `electron-log` · `electron-updater`.

### Evaluated but **not** installed

MovieLite, MoviePy, whisperX, FunASR/FunClip, ffsubsync, ffmpeg-python, pydub, MLT,
demucs, Real-ESRGAN, Helsinki-NLP models. Reasons per project — including licence
traps such as GPL-3 in `openshot-qt` and RobustVideoMatting — are in
`docs/CuttingEdge/OSS_EVALUATION.md`.

---

## 2. Rebuilding a working environment after a wipe

```
bash ce-app/scripts/dev-setup.sh
```

Then, in two terminals:

```
export CE_FFMPEG_DIR=<repo>/ce-app/.ffmpeg
<repo>/ce-app/.venv/bin/python ce-app/backend/run_backend.py
```

```
cd ce-app/frontend && npm run dev
```

No Windows machine is needed for anything except packaging.

## 3. The checks that protect the product

| Command | What it guards |
|---|---|
| `python -m pytest` (in `ce-app/backend`) | render engine geometry/duration/audio, the silent-source regression, silence and scene detection against known ground truth — 7 tests |
| `npm run verify` (in `ce-app/frontend`) | TypeScript plus the renderer↔preload bridge contract |
| `npm run test:ui` (in `ce-app/frontend`) | every route renders, no overlapping boxes, no horizontal overflow, one screen mounted after rapid tab switching, language switch flips direction and persists |
| `ce-app/scripts/smoke-test.ps1` | the **packaged** app: asar entry, relative asset paths, ffmpeg+ffprobe, embeddable Python, live `/api/health` |

The first two run anywhere. The third runs on the Windows runner in CI and is the
gate that stops a broken installer from being published.

## 4. Bugs already fixed — do not reintroduce

1. **Absolute asset paths.** Vite must keep `base: './'`; under `file://` an
   absolute `/assets/...` resolves to the drive root and the window turns black.
2. **API base URL.** In the packaged app there is no page origin: `src/api/runtime.ts`
   must target `http://127.0.0.1:8742` explicitly.
3. **Update events.** The main process emits on the `update:event` IPC channel and
   `preload.ts` bridges it; listening for `window` messages silently does nothing.
4. **A venv is not portable.** `before-pack.js` must convert it to the embeddable
   distribution or the backend never starts on a user machine.
5. **Timeline direction.** The timeline is explicitly LTR; inheriting RTL puts
   second 0 outside the viewport.
6. **Audio branch for silent sources.** Only add `[n:a]` filters for inputs that
   actually contain audio, or FFmpeg aborts the whole graph.
7. **Event loop in worker threads.** Endpoints that hand work to a thread must be
   `async def` and capture `asyncio.get_running_loop()`.
8. **Never let a dead backend look like an empty app.** Every screen degraded to
   "no data" when the bundled Python process was not running, and only a POST ever
   produced an error. `RuntimeBridge` polls `/api/health` and `BackendBanner` says
   so out loud, with restart and diagnostics.
9. **Bridge contract.** Anything the renderer calls on `window.cuttingEdge` must be
   exposed in `electron/preload.ts` *and* handled in `electron/main.ts`. A missing
   entry fails silently — `npm run check:bridge` now catches it.
10. **Bad dependency pins.** The PyPI project is `scenedetect`, not `PySceneDetect`;
   `pexels-api` stops at 1.0.1.

## 5. Release procedure

Bump `version` in `ce-app/frontend/package.json`, commit, push. The workflow in
`ce-app/ci/ce-workflow.yml` (paste once into `.github/workflows/ce.yml`) builds,
smoke-tests and publishes a GitHub Release only when that version is new. Installed
apps then see it through the update button.

**Never delete old releases** — the updater needs the previous installer's blockmap
to build a differential patch.

## 6. Next, in order

1. Project save/load — the timeline is lost when the app closes.
2. Animated captions from the transcript (libass is already inside our FFmpeg).
3. MediaPipe face tracking for reframing (currently centre-crop).
4. YouTube/Instagram publishing.
5. Slim the installer: fetch runtime and models on first launch (~479 MB → ~120 MB).
