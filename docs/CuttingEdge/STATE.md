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
| **Preview** | Real video **with sound** in the program monitor (video lane plus the audio lane under it, per-clip volume/mute honoured, master volume on the monitor), streamed through `/api/media/file` with Range support so scrubbing works from a `file://` page |
| **Export** | Format (9:16 / 1:1 / 4:5 / 16:9 / 4K), quality preset, frame rate, and a native save dialog |
| **Editor** | Multi-track timeline: drag between lanes, trim, split (S), duplicate, snap, zoom, undo/redo, import, export, remove silence, split scenes |
| **Projects** | Save/open `.ceproj` documents in `~/CuttingEdge/projects` (a few KB — media is referenced, never copied), Ctrl+S, unsaved-changes indicator, autosave every 20 s with a restore prompt at launch, and a clear report when media has moved |
| **Timeline** | Starts empty; clips can never overlap on a lane (a drop lands in the nearest free gap, trims stop at neighbours); dedicated scale bar with zoom out/in and Fit |
| **Tool rail** | Undo/Redo always visible, then the context-sensitive toolbar (global set / 18-tool clip set) with nested panels: speed, volume + fades, crop, transform, opacity, rotate, freeze, reverse, mute, duplicate, replace, delete |
| **Colour** | 10 looks (warm, cool, cinematic, vivid, b&w, sepia, vintage, matte, night) plus manual brightness, contrast, saturation, temperature, sharpen and vignette |
| **Animation** | Per-clip in/out: fade, zoom in, zoom out, with adjustable length |
| **Text & captions** | Text clips rendered with libass (correct Persian shaping and bidi), four styles, three positions, colour and highlight, word-by-word karaoke; automatic captions from `faster-whisper` with pause-aware line breaking |
| **Audio cleanup** | Spectral noise reduction and a voice-enhance chain (high-pass, presence, compression, -16 LUFS) |
| **Assistant** | Floating button: a sentence in English or Persian becomes whitelisted timeline operations, validated and applied as one undoable step. Works offline with rules; uses Ollama/OpenAI/Gemini/Claude when configured |
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
| `python -m pytest` (in `ce-app/backend`) | render engine geometry/duration/audio, the silent-source regression, silence and scene detection against known ground truth, and `test_effects.py` — which measures the exported pixels to prove looks, opacity, grade, transform, rotate, crop, animations, freeze, speed and xfade really land — 61 tests |
| `npm run verify` (in `ce-app/frontend`) | TypeScript plus the renderer↔preload bridge contract |
| `npm run test:ui` (in `ce-app/frontend`) | every route renders, no overlapping boxes, no horizontal overflow, one screen mounted after rapid tab switching, language switch flips direction and persists |
| `npm run test:playback -- --a a.webm --b b.webm` (in `ce-app/frontend`) | the transport and the monitor: the playhead advances, the red marker moves, playback crosses a cut, stops at the end, pause pauses, a seek is followed, the junction diamond opens the transition chooser, and opacity/transform/rotate/look/grade/crop/animation/transition are actually visible in the preview, plus the Delete key and Ctrl+Z |
| the same test also guards the layout the user asked for: no scale bar above the timeline, no magnifiers in the transport, the scale control inside the timeline, Ctrl+wheel zoom, the canvas shape, and the home screen's starting cards |
| the same test also checks the film strip renders decoded frames, the moved tools are in the rail, and no clip tools remain on the home screen |
| `bash ce-app/scripts/sandbox-test-env.sh` | rebuilds the whole headless test environment (venv, ffmpeg, Chromium, test clips) after the sandbox wipes `/tmp` |
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
11. **A preview needs a clock.** Until 0.3.4 nothing advanced `playhead`: the video
   element played, the red marker stood still and playback died at the first cut.
   `PreviewMonitor` now runs a `requestAnimationFrame` transport that prefers the
   video element's own `currentTime`, falls back to the wall clock over gaps, steps
   over each cut so the next clip loads, and stops at the end of the timeline.
   Guarded by `npm run test:playback`.
12. **Effects must be visible in the monitor.** Every per-clip effect reached the
   exported file (measured, see `tests/test_effects.py`) but the preview showed a
   raw `<video>`, so opacity, transform, rotate, crop, looks, grade, animations,
   freeze and transitions all looked broken. `editor/preview.ts` is the CSS twin
   of `compose.py` and `PreviewMonitor` stacks two layers so an xfade can be
   cross-faded. Anything CSS cannot do (unsharp, reverse) is named in a badge.
13. **A timeline needs frames.** Clips were flat colour rectangles; they now draw a
   film strip from `GET /api/media/thumb?path&t&h` (one JPEG per frame, cached in
   `~/CuttingEdge/data/thumbs`, times quantised to 0.1 s so zooming reuses the
   cache). Scale is by Ctrl+wheel or a two-finger pinch, anchored under the
   pointer — no slider anywhere, like the phone editors we are compared with.
14. **Home starts sessions, the rail edits clips.** Catalogue entries carry
   `place: 'editor'`; those tiles are gone from the home screen and appear in the
   editor's global tool rail instead (captions and silence removal run in place,
   the rest open their own screen).
15. **The monitor is the canvas, not a 16:9 box.** A phone video used to appear as
   a thin strip between black walls; the stage now takes the project ratio
   (`aspect`, default `auto` = the first video clip's real pixel size) and the
   export dialog opens on the matching format. Clips carry `width`/`height` from
   the probe for this.
16. **Advertised shortcuts must exist.** The buttons said "Delete", "S", "Ctrl+Z"
   while nothing listened for a key; Studio now owns one `keydown` handler and
   skips inputs, textareas and modals.
17. **Panels the timeline can open.** The tool rail's open panel lives in the store
   (`panel` / `setPanel`), because the junction diamond between two clips must open
   the transition chooser. Local `useState` inside the toolbar made that impossible.

## 5. Release procedure

Bump `version` in `ce-app/frontend/package.json`, commit, push. The workflow in
`ce-app/ci/ce-workflow.yml` (paste once into `.github/workflows/ce.yml`) builds,
smoke-tests and publishes a GitHub Release only when that version is new. Installed
apps then see it through the update button.

**Never delete old releases** — the updater needs the previous installer's blockmap
to build a differential patch.

**Confirmed on a real machine (0.3.7, user report):** every in-app update so far has
downloaded **under 50 MB** against a ~479 MB installer, so the differential channel
is genuinely working end to end — deterministic payload, blockmap, and the installer
seeded in `%LOCALAPPDATA%` all do their job. This is the baseline any future change
to packaging must not break: if an update ever downloads the full installer again,
suspect a non-deterministic payload (timestamps, `__pycache__`, compression change)
before anything else.

## 6. Next, in order

1. Playhead pinned to the centre with the timeline scrolling under it (asked for,
   awaiting the user's go-ahead — it changes drag, trim and scroll semantics).
2. 720p proxies on import, so scrubbing 4K footage is not painful.
3. Keyframes for position, scale, rotation, opacity and volume.
4. Ripple / roll / slip trimming.
5. Slim the installer: fetch the Python runtime and models on first launch
   (~479 MB → ~120 MB). Delta updates are verified working, so the risk here is
   measurable — re-check the download size right after this lands.
6. Real MediaPipe face tracking for auto-reframe (currently centre-crop).
7. Beat detection + automatic ducking; then YouTube/Instagram publishing.
3. Slim the installer: fetch runtime and models on first launch (~479 MB → ~120 MB).
