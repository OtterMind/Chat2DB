# Cutting Edge — current state

**Read this first.** Working sessions are wiped every few hours; this file, the
code and the docs next to it are the only things that survive. Everything below is
verified, not planned.

Branch: `arena/01a032fb-chat2db` · App version: `0.9.41` (the number that
publishes is `ce-app/frontend/package.json`; the backend reads it, with
`CE_VERSION` in packaged builds) · Last released: `v0.9.5` (installer 323 MB) (installer **323 MB**; 458 → 305 by dropping ballast, +18 for shipping bytecode again)

*Session handoff:* the previous session ended on `arena/01a0214a-chat2db`, which
still points at `763a0de` on the remote — the same commit this branch starts
from, so nothing was lost in the break. What *was* lost is the sandbox's
throwaway half: `ce-app/.venv`, `ce-app/.ffmpeg` and `ce-app/frontend/node_modules`
were gone and had to be rebuilt with `bash ce-app/scripts/dev-setup.sh` (§2).
Code, docs and this file are the durable half, and they were all there.

**The plan is in `docs/CuttingEdge/ROADMAP_1.0.md`** — release by release from
here to 1.0, each with the number that has to move. Read it after this file.

---

## 1. What is actually in the product

| Area | State |
|---|---|
| Backend | FastAPI + SQLite on port **8742**; job pipeline (ingest → prepare → transcribe → select → reframe → subtitle → export) |
| **Render engine** | `core/engine/compose.py` — the edit model becomes one FFmpeg `filter_complex`; NVENC when present, libx264 otherwise; progress streamed over the WebSocket |
| **Speech map** | Where someone is talking, which every cut depends on. Two sources, one return shape: FFmpeg's energy detector (the default, unchanged) and **silero-vad** (MIT, a 2.22 MB ONNX model fetched on demand, run by the `onnxruntime` that already ships with faster-whisper). Opt-in behind Settings → *Where the speech is*, which also has a **Measure it** button that runs both on a file you choose and shows the numbers — the verdict on real speech is deliberately left to that measurement, not claimed here |
| **Auto-edit** | `core/engine/analyze.py` — silence detection (FFmpeg `silencedetect`) and scene detection (PySceneDetect, FFmpeg fallback) |
| Frontend | React 18 + Vite + Electron 31, super-app launcher home, 8 screens, one shared `Page` shell |
| **Preview** | Real video **with sound**, shaped to the project canvas (Auto follows the footage), every effect applied live as CSS, transitions cross-faded between two layers, text drawn on top, 720p proxies for heavy footage. A `requestAnimationFrame` transport drives the playhead |
| **Export** | Format (9:16 / 1:1 / 4:5 / 16:9 / 4K), quality preset, frame rate, and a native save dialog |
| **Editor** | Multi-track timeline with **film strips** and **audio waveforms**, playhead pinned to the centre while the timeline scrolls, Ctrl+wheel / pinch zoom, drag between lanes, trim, split, ripple/roll/slip, duplicate, snap, undo/redo, keyboard shortcuts |
| **Projects** | Save/open `.ceproj` documents in `~/CuttingEdge/projects` (a few KB — media is referenced, never copied), Ctrl+S, unsaved-changes indicator, autosave every 20 s with a restore prompt at launch, and a clear report when media has moved |
| **Keyframes** | x, y, scale, rotate and volume animate over time; linear between keys in the monitor **and** in the export, markers on the clip |
| **Beats** | Tempo and beat grid from our own spectral-flux + autocorrelation detector (no new dependency), drawn on the ruler; cut-on-beat splits a clip on the music |
| **Timeline** | Starts empty; clips can never overlap on a lane; the scale control lives in the timeline's own corner |
| **Tool rail** | Undo/Redo always visible, then the context-sensitive toolbar (global set / 18-tool clip set) with nested panels: speed, volume + fades, crop, transform, opacity, rotate, freeze, reverse, mute, duplicate, replace, delete |
| **Colour** | 10 looks (warm, cool, cinematic, vivid, b&w, sepia, vintage, matte, night) plus manual brightness, contrast, saturation, temperature, sharpen and vignette |
| **Animation** | Per-clip in/out: fade, zoom in, zoom out, with adjustable length |
| **Title pack** | 15 presets in three groups — entrance, hold, caption — served by `GET /api/titles` and applied from the text panel as **one undoable step**. Every one animates only the five channels the exporter reproduces (`x, y, scale, rotate, volume`); `titles.validate()` refuses anything else, and `tests/test_titles.py` runs each preset through the real FFmpeg expression builder. Fades are deliberately absent: opacity needs a per-pixel `geq` pass (§4.23) |
| **On-screen text** | **RapidOCR 1.4.4** (Apache-2.0, checked from the wheel), an on-demand engine whose **models travel inside the wheel** (15.4 MB) — so, unlike DeepFilterNet, no runtime download and no dependency on a host that can fail; it runs on the `onnxruntime` that already ships. Installed to `~/CuttingEdge/runtime/py` with `--no-deps` (it hard-imports Pillow and wants pyclipper/Shapely; `opencv-python` is satisfied by the headless build). Unlocks reading the reference's caption typography, seeing hand-made titles, and the `no_on_screen_text` restriction — which, once OCR is fetched, is actually measured: `text_coverage()` samples frames and reports the share carrying type |
| **Text & captions** | Text clips rendered with libass (correct Persian shaping and bidi), four styles, three positions, colour and highlight, word-by-word karaoke; automatic captions from `faster-whisper` with pause-aware line breaking |
| **Audio cleanup** | Spectral noise reduction and a voice-enhance chain (high-pass, presence, compression, -16 LUFS) |
| **Assistant** | A **conversation**, not a one-shot command: history in, one reply out, with the steps it took and the milliseconds, and the provider named on every answer (`ollama:qwen2.5` or `offline` — never hidden). An editing request still comes back as a whitelisted dry run applied only on Apply and undoable in one step. Floating panel or full screen, RTL, animated, and **streamed**: `POST /api/assistant/chat/stream` sends each step as it happens and each word as it is written (NDJSON, so one `fetch` and a line split), because three bouncing dots are not evidence that anything is happening. The model is the user's choice (`auto`/`off`/ollama/openai/gemini/anthropic), stored in `~/CuttingEdge/config.json` and settable from the chat **or** Settings — one setting, two doors, and `auto` means *the stored choice* before it means *whatever is installed*; with none connected it answers from what was measured and says so. And it **knows what the video is for**: the Style Match answers ride along in the project document, so a question about a lesson is answered about a lesson |
| **Style Match** | A reference video becomes a `.cetemplate` of numbers, and your footage is rebuilt in its shape. The intake card asks what the video *is* — kind, goal, focus, rhythm, phrases to keep or drop, and a target length — because a frame cannot say any of it. Measured effect: the edit drew from **14.4 %** of a 120 s file before, and **97.9 %** with a length asked for; candidate moments that used to span **0.002** on a 0..1 scale now span the full range. *Built on the branch, version not bumped yet — bumping publishes a release* |
| **Transitions** | 28 real `xfade` types with adjustable duration, created from the clip rail or the junction marker between two clips; audio crossfades with them |
| **Shell** | No menu bar, no tabs, no heading band: the wordmark is centred on the launcher, docks top-left inside a section and is the way home. Fullscreen with **F11** |
| **Home** | Update card (version, check, progress, install), two starting cards, recent projects including the unfinished autosave, each deletable |
| **Languages** | English default + Persian, flips LTR/RTL instantly, persisted |
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

**Verified from a cold sandbox on 2026-08-24.** The script builds the venv, the
static ffmpeg and the frontend dependencies, and the suite then runs green
(197 passed, 3 skipped). The one step that needs a hand in a network-filtering
sandbox is the Electron binary: `npm install` dies inside `node install.js` with
`unable to verify the first certificate`, because that download goes to a host
the sandbox intercepts. Everything the checks need is TypeScript and the bridge
contract, neither of which needs the binary, so:

```
cd ce-app/frontend && ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm install --no-audit --no-fund
npm run verify
```

## 3. The checks that protect the product

| Command | What it guards |
|---|---|
| `python -m pytest` (in `ce-app/backend`) | render engine geometry/duration/audio, the silent-source regression, silence and scene detection against known ground truth, and `test_effects.py` / `test_keyframes.py` / `test_audio.py` / `test_proxy.py` — which measure the exported pixels, the animated expressions, the beat detector against synthesised click tracks and the proxy pipeline — **279 collected: 273 passed, 6 skipped** (re-measured 2026-08-24 after rebuilding the environment from nothing; the three skips are the auto-reframe tests whose portrait fixture is deliberately not committed — `scripts/fetch-test-face.sh`). Needs `CE_FFMPEG_DIR` pointed at a real ffmpeg |
| `npm run verify` (in `ce-app/frontend`) | TypeScript plus the renderer↔preload bridge contract |
| `npm run test:ui` (in `ce-app/frontend`, needs Chromium from `sandbox-test-env.sh`) | every route renders, no overlapping boxes, no horizontal overflow, one screen mounted after rapid tab switching, language switch flips direction and persists |
| `npm run test:playback -- --a a.webm --b b.webm` (in `ce-app/frontend`) | the transport and the monitor: the playhead advances, the red marker moves, playback crosses a cut, stops at the end, pause pauses, a seek is followed, the junction diamond opens the transition chooser, and opacity/transform/rotate/look/grade/crop/animation/transition are actually visible in the preview, plus the Delete key and Ctrl+Z |
| the same test also guards the layout the user asked for: no scale bar above the timeline, no magnifiers in the transport, the scale control inside the timeline, Ctrl+wheel zoom, the canvas shape, and the home screen's starting cards |
| the same test also checks the film strip, the waveform, the beat grid, cut-on-beat, keyframe interpolation, mute vs hide, the docked wordmark, readable toasts, and that the update card / Settings / Diagnostics are reachable from the home screen — 71 checks |
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
13. **Three bugs the user's own machine found (0.5.3).**
   • `timeout of 30000ms exceeded` — the API client's global 30 s budget applied
     to a 7B model thinking on a CPU. AI calls now carry their own 15-minute
     budget and say what they are waiting for.
   • `404 Not Found ... /api/generate` — that is Ollama saying *"no such model"*.
     The default was `llama3`; the machine had `qwen2.5:7b-instruct-q4_0`. The
     self-test now picks a model that is actually pulled, there is a model
     chooser in Settings, and a 404 is rewritten into plain words.
   • `Library cublas64_12.dll is not found` — faster-whisper reaching for CUDA on
     a machine with a graphics card but no CUDA runtime. `transcribe._load()`
     falls back to the CPU; that machine is normal, not broken.
   Also: a row whose self-test failed no longer wears a green tick, and its state
   is exposed as `data-state` so the test reads behaviour, not translated words.
14. **Optional engines must be checked, not assumed.** Settings has an AI runtime
   card: is Ollama installed, is it *running*, which models are pulled, is
   faster-whisper importable and is a model on disk — plus a self-test that
   reports **seconds**, because "the import worked" is not the question. It never
   installs Ollama silently (that is a several-hundred-megabyte application from
   another project); it offers the download link and can pull a model into an
   Ollama the user already runs. `tests/test_ai.py` runs on a machine with
   neither engine, which is the case that must not crash — and did, once, on a
   missing `requests`.
15. **Style Match measures, it never copies.** `core/engine/style.py` turns a
   reference video into a template (shot rhythm, tempo, cuts-on-beat ratio,
   camera move per shot, colour, speech ratio, hook, transition kind) and
   `build_timeline()` cuts the user's own footage into that shape — one clip per
   template shot, gapless, graded, with push/pull/pan becoming keyframes. Three
   attempts at motion classification failed before the fourth worked, and the
   order matters: **cancel translation, then measure scale in log-polar space,
   with the sign verified against clips built to zoom by a known amount.** The
   tests build every fixture to a recipe, so each has a right answer.
16. **Frames come in strips, not one process each.** `sample_strip()` decodes N
   frames in one FFmpeg call; the per-frame version spent more time spawning
   processes than decoding.
17. **Ducking is computed, not side-chained.** `sidechaincompress` looks like the
   right filter and is a trap in a large graph: when its key input reaches EOF a
   moment before the main — which happens **under load, never on an idle
   machine** — it emits silence for the rest of the render, so the music vanished
   from the last spoken word onward. Three graph shapes were tried (asplit,
   padded key, a dedicated second decode) and all three failed in parallel runs.
   The voice envelope is now measured in `audio.voice_envelope()` and applied as
   a volume automation curve on the bed: one stream, one expression, identical on
   every render, and readable as numbers. Depth 0.25 measures ≈ 6 dB in the
   finished file, verified in a 220 Hz band so the voice cannot flatter it.
17. **The old sidechain note, kept for the record:** Automatic ducking uses
   `sidechaincompress`, and the graph is load-bearing: the key is **its own second
   decode of the voice file**, padded past the end of the timeline. `asplit` was
   tried first and starves the compressor under load — with four renders running
   in parallel the music went silent from the last word onward. The key branch is padded (`-t` on the input can end the voice a few samples early,
   after which the compressor emits silence for the rest of the timeline — the
   music simply vanished at 4.2 s). Output length still follows the main input.
   Measured in `tests/test_audio.py` with a 220 Hz bed and a 300 Hz voice, using
   a bandpass so the bed can be judged inside the finished mix.
18. **Removing navigation removes features.** Deleting the tab bar in 0.4.1 also
   deleted the only path to Settings — and the update button lives there, so the
   user could not update the app at all. The updater is now a card on the home
   screen (version, check, progress, install) with a gear and a stethoscope next
   to it, there is a Settings tile in the grid, and `playback-test.mjs` asserts
   every one of those is present and actually navigates. **Before removing a
   route from the interface, list what is only reachable through it.**
19. **A saved project must appear immediately.** The home query cached for five
   seconds, so coming back from the editor after saving showed nothing — which
   looks exactly like a failed save. It now refetches on mount and on focus.
20. **Waveforms and beats are ours, not a dependency's.** `core/engine/audio.py`
   decodes with FFmpeg and does the maths in NumPy: a bucketed min/max envelope
   for the timeline (cached, clamped to 4000 points) and beat detection by
   spectral flux + autocorrelation. librosa would have added numba/scipy for
   forty lines, madmom's models are CC BY-NC and audiowaveform is GPL. Watch the
   **octave trap**: autocorrelation prefers double the true period, so a 150 BPM
   track reads as 75 unless half the winning lag is checked — that correction is
   in the code and in `tests/test_audio.py`, which measures 90/120/150 BPM
   click tracks it synthesises.
19. **"No audio" and "past the end" are answers, not errors.** A silent video
   returns an empty envelope (200) and a thumbnail request beyond the source
   returns the last frame. Both used to 422 and fill the console with failures
   for perfectly normal footage.
20. **The wordmark is the only chrome.** No Electron menu (`Menu.setApplicationMenu(null)`
   plus `autoHideMenuBar` — that white strip survived fullscreen), no tab bar, no
   heading band, no properties panel, no save bar. The wordmark is a shared
   `layoutId` element: centred on the launcher, docked top-left in a section, and
   it is the way home. Anything that used to live in a bar now lives on the
   launcher.
21. **Persistence stays even when its UI goes.** `ProjectAutosave` is headless:
   autosave every 20 s, `Ctrl+S`, and a flush on unload. Unfinished work is
   offered as the first card under "Recent projects" on the home screen — where a
   person looks for it — and every saved project has a delete button.
22. **A message nobody can read is no message.** Static antd toasts/tooltips render
   outside the theme provider and appeared as blank white shapes; they are styled
   in `global.css` and the browser test asserts the notice's computed background
   is dark.
23. **A keyframe the export cannot reproduce is a lie told twice.** Keyframes exist
   for exactly the five channels FFmpeg can genuinely animate — x, y, scale,
   rotate, volume — built by `keyframe_expression()` in `compose.py` as
   piecewise-linear `if(lt(t,..),..)` chains (commas escaped!). Opacity is
   deliberately absent: it needs a per-pixel `geq` pass; fade in/out and the
   in/out animations cover that case. Animated geometry switches the clip chain
   to `scale=eval=frame` plus an `overlay` onto a transparent canvas, which is
   the only combination that reproduces "scale about the centre, then translate".
   Static clips keep the old, fast chain — `tests/test_keyframes.py` asserts that.
24. **Mute silences, hide blanks — never the same switch.** Muting a video lane
   used to remove it from the monitor (black screen). A lane now has two flags:
   `muted` (audio only, speaker icon) and `hidden` (picture, eye icon), and the
   compositor makes the identical distinction — `tests/test_effects.py` renders
   both cases and measures the frame.
25. **One React instance.** Adding `framer-motion` to a running dev server
   produced "invalid hook call" from a duplicated React in the optimiser cache
   while `tsc` stayed silent. `vite.config.ts` now sets
   `resolve.dedupe: ['react', 'react-dom']`.
26. **A toggle must look pressed.** The clip Mute tool worked all along but gave
   no feedback, so it read as broken. Toggles in the rail now render with an
   active state and confirm with a toast.
27. **The preview may use a proxy, the export never may.** Import builds a 720p
   H.264 copy (keyframe every 15 frames) in a worker thread for anything wider
   than 1280 px; `clip.proxy` is used by `PreviewMonitor` only, and
   `tests/test_proxy.py` asserts the render command still points at the original.
28. **Centred playhead is a view mode, not a model change.** The marker is pinned
   to the middle and the lane carries half a viewport of padding on both sides, so
   `scrollLeft === playhead * pxPerSecond`. Scroll events set the playhead and the
   playhead sets the scroll — the loop is broken with a `programmatic` flag, not
   with timers. The classic mode is one click away in the timeline corner.
29. **A timeline needs frames.** Clips were flat colour rectangles; they now draw a
   film strip from `GET /api/media/thumb?path&t&h` (one JPEG per frame, cached in
   `~/CuttingEdge/data/thumbs`, times quantised to 0.1 s so zooming reuses the
   cache). Scale is by Ctrl+wheel or a two-finger pinch, anchored under the
   pointer — no slider anywhere, like the phone editors we are compared with.
30. **Home starts sessions, the rail edits clips.** Catalogue entries carry
   `place: 'editor'`; those tiles are gone from the home screen and appear in the
   editor's global tool rail instead (captions and silence removal run in place,
   the rest open their own screen).
31. **The monitor is the canvas, not a 16:9 box.** A phone video used to appear as
   a thin strip between black walls; the stage now takes the project ratio
   (`aspect`, default `auto` = the first video clip's real pixel size) and the
   export dialog opens on the matching format. Clips carry `width`/`height` from
   the probe for this.
32. **Advertised shortcuts must exist.** The buttons said "Delete", "S", "Ctrl+Z"
   while nothing listened for a key; Studio now owns one `keydown` handler and
   skips inputs, textareas and modals.
33. **Panels the timeline can open.** The tool rail's open panel lives in the store
   (`panel` / `setPanel`), because the junction diamond between two clips must open
   the transition chooser. Local `useState` inside the toolbar made that impossible.

34. **A dependency's weight is a measurement, not a feeling.** The installer's
   Python side is ≈ 339 MB of Windows wheels, and it was never checked which of
   them are imported. `ctranslate2` alone is **174.9 MB** (the whole speech stack
   ≈ 211 MB) and is carried by users who never make a caption; `mediapipe`
   (50.8 MB), `google-api-python-client` (12.1 MB), `Pillow`, `edge-tts`,
   `pexels-api` and the `openai` / `anthropic` / `google-generativeai` / `ollama`
   SDKs are shipped and **never imported** — every cloud provider is called with
   plain `requests`. Before adding a package: query PyPI for the Windows wheel
   size *and* its closure. Before defending one: grep for the import. The numbers
   are in `ROADMAP_1.0.md` §1.1.
35. **An outside review is a hypothesis, not a patch.** `REVIEW_AUDIT_0.5.3.md`
   checks ten suggestions from an external code review against this repository
   and against the registries: three were right, three were wrong on the facts
   (`ThresholdDetector` finds fades to black, not dissolves; `cuts_on_beat` never
   reads `transitions`; the proposed SSE code cannot work because `EventSource`
   is GET-only), and one — `librosa` — was right about the licence and silent
   about the ≈ 94 MB it drags in. The same review found a real bug in passing:
   the 30 s client timeout still applies to `POST /api/style/analyze`.

36. **Work longer than a request must be a task.** Style analysis was one
   synchronous POST against a client with a 30 s budget. Measured on the test
   machine: a **ten-minute reference takes 35.5 s**, so the user's own long file
   was guaranteed to reproduce `timeout of 30000ms exceeded` — the same failure
   as 0.5.3, in a different feature. Now `POST /api/style/analyze/start` returns
   in **1–4 ms** with a task id, stages stream over the existing `/ws` socket
   (`task:progress|done|failed|cancelled`), `GET /api/tasks/{id}` is the fallback
   for a dropped socket and carries the result, and `POST /api/tasks/{id}/cancel`
   stops it. The synchronous endpoints stay for scripts and tests.
37. **A cancel that does not reach the child is a lie.** `core/engine/cancellation.py`
   binds a cancel flag to the worker thread and every FFmpeg call goes through
   `cancellation.run()`, which kills the child within ~0.2 s. The longest stage
   is *not* FFmpeg though — shot detection runs inside PySceneDetect — so
   `detect_scenes` starts a watcher thread that calls `SceneManager.stop()`.
   Without it, Stop was honoured only when the 10 s stage ended, and the browser
   test failed exactly that way before the fix. Measured after: **0.2 s**.
38. **Heavy sync endpoints strangle the socket.** `/api/captions/transcribe`,
   `/api/analyze/silence`, `/api/analyze/scenes` and `/api/analyze` were plain
   `def`, so FFmpeg and Whisper ran *on the event loop* — which is also the loop
   that delivers task progress and answers `/api/health`. All four are now
   `async def` + `run_in_executor`, and the long ones carry their own client
   budget (transcribe 20 min, scans 10 min) instead of the global 30 s.

38. **Nothing ships that nothing imports.** The Windows dependency closure was
   measured with `uv pip compile --python-platform windows`: **378.3 MB across
   108 packages**. `mediapipe` was pinned, shipped to every user and imported
   **nowhere** — and it dragged in `jaxlib` (61.2 MB), `opencv-contrib-python`
   (46.2 MB), `scipy` (36.6 MB) and `matplotlib` (9.3 MB) behind it. The four
   cloud AI SDKs (`openai`, `anthropic`, `google-generativeai`, `ollama`) were
   dead as well: every provider is called with plain `requests`. So were
   `Pillow`, `edge-tts`, `pexels-api`, `google-api-python-client` and
   `sqlalchemy` — the database is standard-library `sqlite3`. After the cut:
   **137.9 MB across 50 packages**, with every remaining line imported by the
   code. `tests/test_dependencies.py` is the ratchet: a new pin must be imported
   somewhere or be named in `INDIRECT` with its reason, and the ten heavy ones
   are banned by name. A feature that needs a big engine fetches it on demand
   (that is how Whisper models already work) instead of taxing every user.
39. **A test that assumes an engine is missing only passes where it is missing.**
   `test_ai.py` hard-coded `whisper.installed is False`; it passed in the
   sandbox and would have failed on the machine we actually build, because
   `faster-whisper` ships. The suite now asks (`importlib.util.find_spec`) and
   asserts the honest answer in both directions.

40. **The build's own health check must not be a hand-written package list.**
   `before-pack.js` verified the embeddable runtime with
   `import fastapi, uvicorn, sqlalchemy, pydantic_settings`; dropping
   `sqlalchemy` made it abort every build with *"portable backend runtime is
   still incomplete"* and no cause. It now imports `app.main` — the application
   is the only honest answer to "can this runtime start?" — and prints the
   interpreter's traceback when it cannot. One more trap on the way: in an
   embeddable runtime the `.` entry of `python311._pth` is the folder holding
   `python.exe`, **not** the process's working directory, so the probe has to
   put the backend folder on `sys.path` itself. `smoke-test.ps1` does the same.

41. **Never pay for a smaller update with a slower app.** Bytecode was deleted
   from the payload so that unchanged files stay byte-identical between releases
   and differential patches stay small. Measured cost: starting the backend took
   **1.16 s** with no `.pyc` anywhere against **0.72 s** with bytecode present.
   That was a bad trade and it did not even have to be a trade —
   `compileall --invalidation-mode unchecked-hash` writes caches that contain
   the source hash instead of an mtime, so they are identical between builds
   *and* Python uses them. `before-pack.js` now compiles instead of deleting.
   The rule: when a size decision costs the user something, measure the cost and
   look for the option that costs nothing.
42. **The local build used a weaker FFmpeg than CI.** CI downloads
   `ffmpeg-release-full`; `before-pack.js` fell back to
   `ffmpeg-release-essentials`, so an installer built outside CI shipped fewer
   filters and nobody would notice until one was missing on a user's machine.
   Both use the full build now (it is 7z-only, so the unpacker learned `7z` and
   says so plainly if 7-Zip is absent).

43. **The preview is a product, not a placeholder.** The editing proxy was
   720p, CRF 26, `fast_bilinear` — chosen to be small and quick, and it is what
   the monitor actually shows, so every 4K clip was previewed soft. Measured on
   a 2-minute 1440p clip: **33.4 dB PSNR**. It is now 1080p, CRF 21,
   `bicubic`, `superfast` — **49.8 dB**, and *faster* to build than 1080p at
   `veryfast` (68 s vs 80 s). It costs disk in `~/CuttingEdge/work/proxies`,
   which is the one resource a scratch file is allowed to spend.
   `tests/test_proxy.py` no longer asserts "smaller than the source" (the wrong
   goal); it measures PSNR and requires > 40 dB. Film-strip thumbnails moved
   from `-q:v 6` with the default scaler to `-q:v 3` with `bicubic`.
44. **Use the best engine the machine already has.** Transcription hard-coded
   the `base` model and `int8`, so a user with `small` downloaded still got the
   weakest model, and a working CUDA runtime still got integer maths. Now
   `transcribe.best_local_model()` picks the most accurate model **already on
   disk** (nothing is downloaded), the device ladder is
   `cuda/float16 → auto/int8 → cpu/int8`, and the Settings card reports the
   model that will really be loaded instead of the string "base".

45. **The brain is a race with a referee, not a chatbot.** `core/brain/` is
   three small files: `objective.py` scores a candidate edit on seven measured
   terms (duration fit ×3, speech integrity ×3, on-beat ×2, silence avoided ×2,
   highlight strength ×2, variety ×1, shot-length match ×1); `planners.py` has
   the deterministic rule planner and an Ollama planner; `race.py` runs them and
   picks the winner. Three properties are load-bearing and tested:
   • the rule plan is **always** a candidate, so a model can only win by scoring
     higher — it can never make the edit worse than offline;
   • a **tie goes to the rules**, because determinism beats novelty;
   • the model returns **indices into the measured moments**, never timings of
     its own, so it cannot invent a moment that does not exist.
   The scoreboard is shown to the user — `rules 0.71 · ollama:qwen2.5 0.83 →
   used ollama:qwen2.5` — because that line is the only honest answer to "did
   the AI help?", and sometimes it is "no".
46. **A term that cannot be measured is dropped, not guessed.** The first
   version of `speech_integrity` fell back to coarse speech *ranges* when there
   were no word timings, and scored a flawless plan 0.82 — every cut inside a
   twenty-second range of talking counted as cutting through a word. Without a
   transcript the term is now skipped and the remaining weights renormalise.
47. **A free prompt gets a dry run, not a score.** "Did it understand me?" is
   not measurable, so the Assistant now plans, prints what it will do in the
   user's own language, and applies only on **Apply** — with Cancel changing
   nothing at all. Guarded by three checks in `playback-test.mjs`.

48. **`git reset --soft` can carry a stale workflow into your commit.** The
   sandbox loses remote refs between turns, so the recovery pattern is
   `git fetch -f origin <branch>` then `git reset --soft FETCH_HEAD`. That leaves
   **everything** from the discarded commit staged — including
   `.github/workflows/ce.yml`, which our token may push but may not *change*.
   In 0.7.0 an old copy of it went out and replaced the whole build with
   `on: workflow_dispatch`, so the release never built and the token could not
   put it back (403, `workflows` permission). Always run
   `git restore --staged --worktree .github/` **before** committing after a soft
   reset, and check `git show --stat HEAD | grep workflow` after it.
   The good copy lives in `ce-app/ci/ce-workflow.yml`; only the repository owner
   can paste it back into `.github/workflows/ce.yml`.

49. **Auto-reframe follows a measured face, and the answer is keyframes.**
   `core/engine/reframe.py` finds the largest face a few times a second with the
   Haar cascade **already inside the `opencv-python-headless` wheel we pin** (no
   download, no GPU, no MediaPipe and its ~160 MB of transitive wheels), then
   turns the path into ordinary `x` keyframes plus the scale that fills the
   canvas — so the camera move is visible on the timeline, draggable, and
   reproduced by the normal exporter. Measured on a 1280×720 fixture with a real
   photograph travelling a known line: the subject stays within **122 px** of
   centre (mean 59 px) against **1024 px** (mean 905) for the centre crop it
   replaces. The `BETA` badge is gone.
   Two traps found while building it: the smoothing must be **zero-phase** (the
   first, causal, exponential filter lagged the subject by 268 px — we are not
   live, the file is on disk, so the filter may look forwards), and a "known
   answer" fixture has to actually be known (the first one overlaid a portrait
   assuming the face sat in its middle; it sits 10 % right, and the test dutifully
   measured the fixture's error as the detector's).
50. **Highlights are read, not just heard.** `core/brain/meaning.py` scores each
   moment's transcript on discourse markers (English and Persian), questions,
   numbers, sentence completeness and density, and blends it half-and-half with
   the measured energy. It is a proxy for understanding, not understanding, and
   it runs offline on text we already have — no model required.
51. **A lazy import is still an import.** `planner._chat` did `import requests`
   inside the LLM path; on a machine without it every prompt came back as a 500
   instead of falling back to the offline rules — the same shape as the bug that
   once broke the AI self-test. Optional dependencies degrade, they do not fail.

52. **The rebuild was the amateur, not the analysis.** The user's verdict on
   Style Match 0.8.0 was "it worked like an amateur, as if there were no AI at
   all". They were right, and it was measurable in one line: on sixty seconds of
   continuous talking against a twenty-shot template, the result was **20 clips
   with 1 unique offset** — the same half second, twenty times. Three causes,
   all fixed:
   • `_highlights()` returned whole *ranges*: one unbroken minute of speech was
     a single candidate. It now slices ranges into overlapping shot-sized
     windows, so a minute yields dozens of candidates (measured: 20 clips from
     20 different moments, spread over 17 s).
   • `rule_plan()` did `ordered[index % len(ordered)]` — with one candidate that
     is the same moment every time. It now takes the strongest moment that does
     not overlap anything already on the timeline.
   • `variety` was weight **1 of 14**, so the repeated plan still scored 0.91.
     It is weight 3 now. A term nobody can outvote is not a check.
   Also: cutting on the beat is a **candidate** (`rules+beats`), not a rewrite —
   snapping a 0.62 s shot onto a 0.5 s grid shortens the edit by a fifth, so the
   score weighs rhythm (2) against length (3) instead of the code guessing. And
   dissolves are applied in the reference's own *proportion* (a 50 % reference
   used to produce none at all).
   Guarded by `tests/test_style_rebuild.py`, which asserts what the old suite
   never did: that the clips differ from each other.
53. **Counting is not checking.** Every Style Match test asserted counts — twenty
   clips, gapless, graded — and all of them passed while the edit was the same
   half second twenty times. When a feature's whole value is *variation*, assert
   the variation.

54. **The sweep for the same bug found three more.** After 0.8.1 the whole app
   was searched for the same shape — *measured, then never applied* — and for
   its twin, *applied in the file, invisible in the monitor*:
   • `hook` (how long the reference waited before its first cut) was measured
     from 0.5.0 and read by nothing. The rebuild now opens on it.
   • `handheld` was classified per shot and produced a perfectly still clip;
     it now gets a small five-key wobble.
   • `median_shot` and `speech_ratio` were dead too: the first now sizes the
     candidate windows, the second decides whether the rebuild hunts for speech
     at all — rebuilding a montage should not look for talking.
   • **Karaoke captions** were drawn word-by-word by the exporter (libass `\k`)
     and flat by the monitor, so `animateWords` looked like a switch that did
     nothing. The monitor now lights the spoken word — the same rule as §4.12,
     which we had already learned once and let slip.
   The ratchet is `tests/test_nothing_measured_is_wasted.py`: every field the
   template carries must be read by the rebuild or named in `DECLARED_UNUSED`
   with the reason. It caught `median_shot` and `speech_ratio` the moment it
   was written.

55. **The reference's soundtrack travels with the template.** We used to keep
   only the *behaviour* of the music (tempo, ducking depth) on copyright
   grounds — which was us making the owner's decision for them. It is their
   file and their export. `save_template()` now extracts the reference's audio
   once (`<name>.bed.m4a`, beside the `.cetemplate`, so it survives the
   reference being moved) and the rebuild places it when the user has not
   brought a track of their own. It is resolved **before** the planners run, so
   the cuts are scored against the beats of the track that will actually play.
   `tests/test_reference_bed.py` covers all four cases, including a silent
   reference keeping no bed and a user's own track still winning.
56. **The next big step is written down: `docs/CuttingEdge/STRONGER_AI.md`.**
   The honest diagnosis of why the AI does not feel present — the one model in
   the loop is text-only and has never seen a frame — and the costed, licence-
   checked plan: Ollama **vision** models first (no installer cost, the model
   lives in the user's Ollama), then beam search and a two-pass assistant (free
   and offline), then TransNetV2 for real transition detection, OCR for
   on-screen text, CLIP for content matching, Demucs as an on-demand engine.

57. **The graphics card is used, and it is probed — never assumed, never
   invented.** The owner has a GTX 1650 and asked that the card not be limited
   anywhere. Three things were wrong:
   • the compositor decided NVENC was available by **grepping FFmpeg's encoder
     list**, which lists `h264_nvenc` on machines whose driver refuses it, so
     the choice was wrong in both directions. `core/engine/gpu.py` now encodes
     one real frame and caches the answer;
   • **nothing ever decoded on the card.** Decoding is most of the work in
     building a proxy or scanning a long file; `-hwaccel cuda` now goes in
     front of the input in the proxy pipeline (and the flag order matters —
     after `-i` FFmpeg ignores it, which `tests/test_gpu.py` asserts);
   • `/api/system/doctor` returned `"cuda": {"available": false}` as a
     **hard-coded literal**, so a user with a working card was told they had
     none.
   Settings has a Graphics card panel: name, memory, driver, what the card is
   used for, and a **Measure it** button that encodes the same 5 s of 1080p on
   the processor and on the card and prints both times — a claim about a GPU
   that was not measured on the machine it runs on is a brochure.
   `faster-whisper` on CUDA needs cuBLAS and cuDNN (the `cublas64_12.dll` a user
   hit in 0.5.3); they are 1.3 GB of wheels, so `POST /api/ai/cuda/install`
   fetches them **on demand and only when an NVIDIA card is present** — it is a
   409 otherwise, because downloading a gigabyte of CUDA to a machine that
   cannot use it is not a favour.
58. **A test's question can go stale even when its assertion is right.** Adding
   the reference's soundtrack made three browser checks fail — "one clip per
   shot", "gapless", "graded" — because they counted *every* clip and the edit
   now legitimately carries a music clip. The numbers were right; the question
   was wrong. They ask about the video lane now.

59. **One machine is not the target; every machine is.** The owner's GTX 1650
   reported *decode yes, encode no* with a guessed excuse about the driver, and
   the honest answer — FFmpeg's own words — had been thrown away by the probe.
   `core/engine/gpu.py` now tries **eight** hardware encoders across NVIDIA,
   Intel Quick Sync, AMD and VAAPI, keeps the last line of stderr for each, and
   picks the first that works; the same for six decode backends. The Settings
   card lists every one with its reason, so "no" is never a dead end.
   The per-vendor flags differ (`-cq` for NVENC, `-global_quality` for QSV,
   `-qp_i` for AMF, `-qp` for VAAPI) and that mapping lives in one place.
   The video-memory advice scales with the card instead of being written for a
   4 GB one: 3B / 7B / 13B / 30B.
   Measured on the owner's machine: **x264 encodes 5 s of 1080p in 0.48 s**, so
   the missing encoder is a limitation, not an emergency — and the card is
   already doing the decoding and running Whisper in float16.
60. **The test environment must pin what production pins.** The sandbox
   installed the *latest* `opencv-python-headless`; OpenCV 5 dropped the bundled
   Haar cascades, so face detection silently disappeared and the auto-reframe
   test failed for an environment reason. `sandbox-test-env.sh` pins
   `4.10.0.84`, the version in `requirements.txt`. And `reframe.plan()` now
   distinguishes "this OpenCV build has no detector" from "no frames could be
   read" — the old message sent the reader to inspect the video file.

61. **The probe was wrong, not the card.** A GTX 1650 reported
   `Nothing was written into output file, because at least one of its streams
   received no packets` and we told its owner NVENC did not work. It does: the
   probe asked for **three frames into `-f null -`**, and NVENC buffers several
   frames internally and only flushes at end of stream, so the run finished
   before a packet existed. x264 emits packets in those same three frames, which
   is why the shape was never questioned. The probe now encodes **1.5 seconds to
   a real file** and requires the file to be non-empty; `tests/test_gpu.py`
   asserts the shape (no `-frames:v`, a real duration, a size check) so it
   cannot regress. **When a measurement disagrees with the hardware, suspect the
   measurement first.**
62. **The model catalogue is filtered by the machine.** Settings lists the
   Ollama models this app can use — three vision models included, because a
   model that can see frames is the difference between reasoning about numbers
   and having looked at the video — each with its size, what it is for, and a
   download button. What "fits" means is computed from the card's memory, so a
   4 GB laptop is told `qwen2.5vl:3b` fits and `llama3.2-vision:11b` does not.
63. **`docs/CuttingEdge/OSS_SWEEP_0.9.2.md`** is the verified sweep of GitHub and
   PyPI with a GPU on the table: what we should already have had (TransNetV2 MIT
   for real transition detection, silero-vad MIT for the speech map every
   editing decision rests on), what a card unlocks (Demucs, whisperX,
   Real-ESRGAN, RIFE, CLIP — all verified permissive), and what is refused
   (`RobustVideoMatting` GPL-3, `Wav2Lip` no licence, `GFPGAN` NOASSERTION,
   `open-clip-torch` MIT on PyPI but NOASSERTION on GitHub). Hugging Face is
   **unreachable from the sandbox**, so model-card licences there are marked
   *verify before adopting* rather than guessed.

64. **An update could not delete the previous version — because we only killed
   the child, not the tree.** `child.kill()` on Windows terminates the direct
   child; our backend is Python and Python spawns **FFmpeg** (proxies,
   thumbnails, probes). Those grandchildren kept `resources\ffmpeg\ffmpeg.exe`
   open, and the NSIS uninstaller that runs during an update could not remove
   the old folder. `stopBackend()` now runs `taskkill /pid <pid> /T /F`, it is
   called from `before-quit`, `will-quit`, `window-all-closed`, the
   `update:install` IPC handler (with a beat for Windows to release the handles)
   and `before-quit-for-update`, and `npm run verify` fails if any of those
   wires is cut.
   The first version of that guard passed on the **comment** above the call
   (`taskkill /T /F takes the whole tree`) instead of the argument — the same
   "counting is not checking" mistake, made twice now. It matches `'/T'` with
   quotes, and it was proved by deleting the flag and watching the check fail.
65. **Why every update is the same ~16.6 MB, and why that is not a cap.**
   Differential updates work at the level of the installer's **compressed
   blocks**, not files. What changes every release is our `app.asar` (the whole
   1.6 MB bundle is rewritten because its filenames are content-hashed) plus the
   backend `.py`/`.pyc` — but those live inside NSIS's solid LZMA blocks, so the
   download is the size of the blocks that contain them, not the size of the
   diff. Hence a near-constant figure. It is not a limit and nothing is being
   skipped: `electron-updater` verifies a SHA-512 of the fully reassembled
   323 MB installer before running it, and falls back to a full download if it
   does not match.

66. **`-loglevel error` hid the reason for two releases.** After the frame-count
   bug was fixed the GTX 1650 still said *"Nothing was written into output file,
   because at least one of its streams received no packets"* — which is the
   **symptom**: FFmpeg's closing summary when the muxer got nothing. The
   encoder's own explanation is emitted at *warning* level, and we were
   filtering it out. The probe runs at `-loglevel warning` now, keeps the
   encoder's own lines (`nvenc`, `cuda`, `device`, `driver`) as the reason and
   the last three lines as detail, and tries two rescue variants per encoder
   (`-rc constqp`, `-gpu 0`; `-low_power` for QSV; `-rc cqp` for AMF) before
   giving up. The reason reported is always the **first** attempt's, because a
   rescue attempt can fail for a reason of its own (`Unrecognized option 'gpu'`)
   and bury the real one.
   When a card is present and still nothing encodes, the card now names the
   three causes worth checking on Windows — the app running on the integrated
   GPU (Settings → Display → Graphics → High performance), an old or dirty
   driver, and another program holding the encoder — and answers the question
   the owner actually asked: turning it on is safe, NVENC is a separate block on
   the chip built to run for hours and cannot damage anything.

67. **A download the user paid for must survive the next update.** `pip
   install` into the bundled Python lasts exactly until the next release,
   because the installer replaces the whole application folder — and the CUDA
   libraries are 1.3 GB. `core/runtime_packages.py` installs on-demand packages
   into `~/CuttingEdge/runtime/py` and `app.main` puts that on `sys.path` at
   startup, ahead of the bundled site-packages. The other two downloads were
   already safe for the same reason and are left alone: Ollama keeps models in
   its own store, Whisper in the Hugging Face cache. Both also **resume** a
   partial download instead of restarting it.
68. **Every long download has a real bar.** All three run as tasks now:
   `POST /api/ai/ollama/pull/start` streams Ollama's own `completed`/`total`
   byte counts; `POST /api/ai/whisper/download/start` passes a `tqdm_class` into
   `huggingface_hub.snapshot_download` and turns its callbacks into stages;
   `POST /api/ai/cuda/install` parses pip's output. The bar is the download, not
   a timer pretending to be one — and where a byte count genuinely is not
   available (the Whisper fallback path) the label says "no progress available"
   instead of inventing a number.

69. **The GPU preference is a setting, not a permission — so it is a button.**
   The owner asked for a button that requests whatever Windows permissions are
   needed. The honest answer is that no permission controls NVENC; what controls
   it on a laptop is Windows' *per-application graphics preference*, and that
   lives in `HKEY_CURRENT_USER\Software\Microsoft\DirectX\UserGpuPreferences`
   as `GpuPreference=2;`. Writing it needs **no elevation at all** — the app is
   choosing a preference for itself.
   The important part: the preference is **per executable**, and the process
   that opens the encoder is not the one the user clicked. Electron starts
   Python, Python starts FFmpeg, and FFmpeg is what talks to NVENC. Windows'
   own Settings page can only reach the app, which is why setting it there can
   leave the encoder on the integrated GPU. `prefer_discrete_card()` sets it for
   the app (`CE_APP_EXE`, passed in by `main.ts`), the backend's `python.exe`,
   `ffmpeg.exe` and `ffprobe.exe`, and then clears the cached probes because
   they are stale by definition. `POST /api/gpu/preference`; the card also links
   straight to `ms-settings:display-advancedgraphics`.

70. **`ps` truncates at 80 columns, and a test's venv path can be longer than
    that.** Rebuilding the environment after a wipe, `pytest` reported
    *the child never started* in `test_cancel_kills_the_child_process` — while the
    child was running. `_python_sleepers()` greps `ps -eo pid,args` for
    `time.sleep(60)`, and with no terminal to ask, procps cuts each line at 80
    columns. From `/tmp/cevenv` (what `sandbox-test-env.sh` builds) the marker
    survives; from `ce-app/.venv` inside a deep checkout the line ends at
    `... -c import time; ti`. Measured side by side: `ps -eo` → 80 chars,
    `ps -eww -o` → all 84. The helper passes `-ww` now, so the test no longer
    depends on where the virtualenv happens to live. **A test that measures the
    world through a fixed-width tool is measuring the width, not the world** —
    the same shape as §61 (the probe was wrong, not the card) and §60.
71. **The recovery script is code too, and it had rotted against today's PyPI.**
    `dev-setup.sh` is the documented way back after a wipe (§2) and it produced
    an environment where the suite could not even collect: starlette's
    `TestClient` needs `httpx`, which the light set never installed — five test
    modules failed at import, so `pytest` ran nothing at all. And `scenedetect`
    pulls the GUI `opencv-python`, which cannot import without `libGL`, so
    `test_camera_motion_is_recognised[pull]` failed exactly the way §60 warns
    about. Both are now installed by the script itself (`httpx`, then uninstall
    the GUI wheel and pin `opencv-python-headless==4.10.0.84`), matching what
    `sandbox-test-env.sh` already did. After that: **197 passed, 3 skipped** from
    a cold sandbox. Note one thing the sandbox *cannot* do: `npm install` fails
    downloading the Electron binary (`unable to verify the first certificate`),
    so `ELECTRON_SKIP_BINARY_DOWNLOAD=1` is needed to get the frontend's
    TypeScript and bridge checks running here. Packaging still happens on the
    Windows runner.

72. **A scorer with no opinion makes "best" mean "earliest".** The user's report
    was two sentences — "it shortens the first video" and "the highlight
    detection of the second video is very weak" — and both were one bug, both
    measured before anything was changed:
    • the candidate moments were cut **inside the speech ranges**, ranked, and
      truncated, so on 120 s of footage against a 12 s reference the rebuild
      touched **17.3 s — 14.4 % of the material** — and produced the *same*
      offsets it produced for a 30 s file;
    • every window inside a speech range carried weight 1.0 and the only
      variation was a 0.85–1.0 term for how full the window was: 26 candidates
      scored 0.998–1.0, a spread of **0.002**. `list.sort` is stable, so the
      ranking was decided by nothing and the earliest moments won by default.
    Windows now cover the file end to end and each signal — speech coverage,
    picture motion, audio activity, proximity to the footage's own shot changes —
    is normalised *across the candidates* before it is weighted. `core/engine/intent.py`
    holds what the answers are worth, because "the best moment in a lesson" and
    "the best moment in a music clip" are not the same measurement. Measured
    after: **97.9 %** of a 120 s file reachable, and a requested length lands on
    the number (60 s asked, 60.00 s built).
73. **A value resolved after the measurement it should have shaped is a value
    that never happens.** `hook.firstCut` extended the opening shot *after* the
    candidate windows had been cut to the old shot length, so the clip was
    clamped straight back: a 4 s shot with a 7 s hook measured **4.0 s** in the
    edit. The test that caught it was written expecting 7.0 and failed, which is
    the point of a fixture with a known answer. The hook is resolved before the
    measurement now, and it only ever **extends** an opening — the form it
    replaced assigned the value outright inside a 6 s window, free to chop an
    opening to a fraction of a second and blind to a held intro longer than that.

74. **A substring is not a word.** The assistant's rule planner matched the hiss
    people ask to remove with the three-letter fragment `"خش"` — which is also
    inside **«بخش»**. So «کدام **بخش** قوی‌تر است؟» (*which part is the
    strongest?*) came back as a noise-reduction plan, and the same trap was
    waiting in `"نما"` inside «نمایش». Found by asking a question in Persian over
    HTTP and reading the answer, not by reading the code. `wants()` now requires
    a word boundary for any token of three letters or fewer, and
    `tests/test_chat.py` pins both directions: the questions that must stay
    questions, and the requests that must still be edits — a boundary fix that
    deafened the assistant would be the same bug wearing a different hat.

75. **A name the renderer does not know is a silent fallback, not an error.**
    The first draft of the title pack asked for `textStyle: "plain"`, `"box"` and
    `position: "center"`. The renderer's vocabulary is `clean`, `boxed`, `outline`,
    `shadow` and `top`, `middle`, `bottom` — so libass would have taken its own
    default and every one of those titles would have looked different in the file
    than on the screen, with no error anywhere. Caught by reading
    `subtitles.STYLE_PRESETS` instead of trusting the names I had written, and
    then locked: `titles.validate()` checks the vocabulary, and
    `test_the_pack_speaks_the_renderers_vocabulary` compares it against
    `subtitles.py` directly, so renaming a style there now fails the suite.
    **A string that crosses a boundary is an interface, and interfaces get
    checked.**

76. **A deprecation warning is a failed check, not a warning.** The Style Match
    intake card used antd's `addonAfter` on the seconds field. antd logs
    `[antd: InputNumber] addonAfter is deprecated`, and `npm run test:playback`
    asserts the console is clean — because the 1.0 criterion is a clean install
    with **no console error**, and "it is only a warning" is how a real error
    arrives six months later riding along with fifty others. The whole browser
    suite was green apart from that one line, which is the argument for running
    it: 71 backend tests could not see it, and the user would have.
77. **Coverage says where the tests are not.** Measured with `coverage run
    --source=core,app`: **73 %** overall. The engine the editor uses is well
    covered (`compose` 87 %, `style` 88 %, `subtitles` 98 %, `tasks` 98 %), and
    the gap is one place: the **job pipeline** — `core/engine/export.py` 0 %,
    `ingest.py` 0 %, `app/routers/jobs.py` 33 %, `services/pipeline.py`
    untested. Not dead code (the pipeline imports both), just never exercised.
    That is the honest next target for tests, ahead of any new feature.

78. **An engine that is not shipped is not a dependency.** silero-vad's PyPI
    package declares `torch>=1.12` and `torchaudio`, so `pip install silero-vad`
    would pull several hundred megabytes to run a **2.22 MB** model that
    `onnxruntime` — already in the installer, via faster-whisper — runs at
    **165× realtime on this CPU**. So `vad.fetch()` does `pip download
    --no-deps` and takes one file out of the wheel. The licence was read from the
    wheel's own `METADATA` (`Classifier: License :: OSI Approved :: MIT
    License`), not from a README, because the two have disagreed before.
    Nothing new enters the installer: 0 MB.
79. **"The model is better" is a claim, so it stayed a claim.** No real speech
    exists in this sandbox — no apt, no espeak, GitHub blocked, no bundled sample
    in any wheel — so the verdict was **not** invented. What was measured on a
    known-answer fixture (three loud amplitude-modulated tone bursts, nobody
    talking): the energy detector reports **51.5 % speech in 3 regions**, the
    model reports **0 % in 0 regions**. That is the specific failure the energy
    detector has — it cannot tell a loud tone from a voice — and it is why the
    engine exists. Whether the model is better *on speech* is what
    `POST /api/vad/compare` answers on the user's own file, from the Settings
    card. Until that is read, the default is unchanged and choosing the model
    without fetching it is refused with a 409 rather than silently downgraded.
80. **The graceful path was found by accident, and kept.** The running server was
    using a different virtualenv that had no `onnxruntime`, so
    `/api/vad/status` answered `{"model": true, "ready": false}` and
    `/api/vad/compare` returned the energy detector's numbers with `silero:
    null` — no crash, no 500. That is the §4.51 rule holding on the first contact
    with a real machine that lacks the runtime, and `tests/test_vad.py` now pins
    it.

81. **The shot detector was chosen by a scoreboard, not by a changelog.**
    `scenedetect` was already shipping, so both of its detectors were free — and
    "free" is exactly when a choice gets made by taste. Measured on fixtures built
    to a recipe (`tests/test_scenes.py`):

    | fixture | known cuts | ContentDetector | AdaptiveDetector |
    |---|---|---|---|
    | hard cuts, static shots | 6 | 6 found, precision 1.00 | 6 found, precision 1.00 |
    | hard cuts, camera push | 6 | 6 found, precision 1.00 | 6 found, precision 1.00 |
    | **fast pan + handheld wobble** | **2** | **3 found — a cut invented at 2.6 s, precision 0.67** | **2 found, precision 1.00** |
    | 3 s clip, one cut | 1 | correct | correct |
    | 1.5 s single shot | 0 | correct | correct |

    A false cut is not cosmetic: it becomes a clip boundary in the rebuild and a
    shot in the template's rhythm. Fast camera motion is what a phone video is
    made of. AdaptiveDetector is now the detector, the scoreboard is the test, and
    the `threshold` parameter — which belonged to ContentDetector and which
    nothing passed — is gone rather than left as a knob that does nothing.
82. **Two open-source candidates were checked and not added, and that is the
    result.** Both were verified from the wheel's own `METADATA`, not a README:
    • **`transnetv2-pytorch` 1.0.5 — refused.** Licence is genuinely MIT
      (`License-Expression: MIT`, and the bundled `LICENSE` starts "MIT
      License"), and it would give real transition detection. But it requires
      `torch>=1.9.0` plus `ffmpeg-python`, `pandas`, `pillow` and `tqdm` — and
      `pandas` and `Pillow` are precisely the dead weight removed in §4.38.
      Several hundred megabytes of torch to replace a detector that now scores
      1.00 on the fixtures above is not a trade this app makes.
    • **`deepfilternet` 0.5.6 — deferred.** Licence MIT *or* Apache-2.0 (both
      files are in `DeepFilterLib`), and — pleasantly — **no torch**: the runtime
      is a 1.29 MB compiled `libdf` plus numpy, with a Windows wheel
      (`DeepFilterLib-0.5.6-cp311-none-win_amd64.whl`, 0.49 MB). The whole
      closure measured **10.5 MB**, of which **9.4 MB is sympy + mpmath** for a
      denoiser, which is worth questioning before it is accepted. The blocker is
      the model: `df/enhance.py:270` fetches it from
      `https://github.com/Rikorose/DeepFilterNet/raw/main/models/*.zip` — GitHub
      raw only, no PyPI, no mirror. That exact URL was tried here and failed
      (curl exit 35 after a 302). Shipping a denoiser whose weights come from a
      source we have just watched fail, with no way to measure it in dB against
      the current chain, is the brochure §4.57 warns about. Revisit if the model
      lands on a registry.

83. **The one open-source import that passed every gate, and the gates it passed.**
    RapidOCR 1.4.4 was verified the same way as everything else, from the wheel
    rather than the README: licence **Apache-2.0** in the PyPI `License` field
    (models are Baidu's PaddleOCR, also Apache-2.0); the **three ONNX models are
    bundled in the wheel** (det 4.5 + rec 10.4 + cls 0.6 MB), so there is no
    runtime download and it works with the network unplugged; it runs on
    `onnxruntime` + `numpy`, both already in the installer. The honest costs are
    on the wheel too: it hard-imports `Pillow` and wants `pyclipper` and
    `Shapely`, and nominally `opencv-python` — satisfied by the headless build,
    which is exactly why it is installed `--no-deps` into the user's runtime dir
    rather than shipped. Measured on the repo's own launcher screenshot it reads
    "New video", "Recent projects", "Face Tracking" and refuses words that are
    not there; on a blank video `text_coverage()` is 0, which is an answer, not
    an error. The newer `rapidocr` 3.x was checked and passed over: no licence
    field, and its models come from a CDN at runtime. One quirk is named in the
    module docstring rather than hidden: on tightly-set type the detector drops
    spaces ("Open the editor" → "Opentheeditor"), so matching goes through
    `normalise()`.
84. **A restriction that becomes checkable must stop saying "cannot be checked".**
    The moment OCR is fetched, the Style Match `no_on_screen_text` restriction is
    measured: `text_coverage()` samples the footage and, if type covers more than
    a fifth of frames, the summary says so in the open — OCR can read it and warn,
    not erase it. Until then it reports "the OCR engine is not fetched". A
    checkbox that flips from "impossible" to "checked" silently would be the same
    lie in the other direction.

86. **The gallery is an interface, so it is checked.** An exported `.cetemplate`
    is a file someone else made, and a file someone else made is exactly the kind
    of boundary this app checks (§4.75). `validate_template()` names what is wrong
    (no shots, a negative length, an unknown camera move, an aspect the canvas
    cannot hold) and `import_template()` refuses with a 422 listing the reasons;
    a sound document round-trips and rebuilds footage. A fresh gallery is seeded
    with three **starters** — hand-written rhythms that say they are starters,
    each itself valid — rather than an empty room, and saving one copies it where
    it can be deleted like anything else.
87. **The sound pack is an online, key-required shelf, shaped like the other
    opt-ins.** Freesound results carry licences, so only Creative-Commons-0 /
    Attribution are offered, previews let the user hear before a byte downloads,
    and files land in `~/CuttingEdge/sounds`. Without a key `status()` says
    "not configured" and search is an empty shelf, not an error — nothing about
    it is in the installer. The real search needs the user's own account, so its
    verdict is theirs, exactly as the GPU benchmark left its verdict to the
    user's card (§4.57).

88. **"Encoding stays off even though I set High performance" is two different
    failures wearing one symptom, and the owner's screenshot showed which.** The
    probe said `[h264_nvenc] Driver does not support the required nvenc API
    version. Required: 13.1 Found: 13.0`. That string is a driver story, not a
    settings story: the bundled FFmpeg is compiled against `nv-codec-headers`
    needing NVENC API 13.1, and the installed driver exposes 13.0. The Windows
    graphics preference — which the owner had correctly set to High performance —
    chooses *which GPU runs the app*; it **cannot** raise the NVENC API version,
    which is exactly why it changed nothing. Searching the open web confirms the
    only fixes: update the NVIDIA driver to the latest Studio release, or build
    FFmpeg against older `nv-codec-headers` (the bundled build cannot). So
    `capabilities()` now parses `Required: X Found: Y`, says "this is a driver
    story, not a settings story", names the driver update, and explicitly tells
    the user their High-performance setting was right but is the other half.
    No new library fixes it — this is an ABI between two pieces of software the
    app does not compile, and pretending otherwise would be the brochure.

89. **The credit screen is generated, not written.** A hand-typed licence list
    drifts the day a dependency changes; so `/api/system/attribution` reads each
    pinned backend package's *installed* metadata — name, version, licence,
    preferring `License-Expression` over a wall of classifiers — and the test only
    requires a licence for packages that are actually present, so the light dev
    venv passes while the packaged runtime (every pin installed) is fully
    covered. Bundled non-Python pieces (FFmpeg, Electron, embeddable CPython) and
    the on-demand engines are named beside them, marked "yours, not shipped". The
    page is reachable from Diagnostics. It is the 1.0 criterion "every shipped
    package listed with its licence" as a screen a person can read.

90. **A rally has no face; the moving region is the subject.** The reframe used
    only the Haar face cascade, which needs a ≥24 px face looking at the camera —
    a volleyball player mid-rally, a back turned on a pull-up bar, a jumper
    mid-rope have none of that. So when the face cascade gives up, `reframe`
    now follows the **centroid of motion** (the frame-to-frame difference above
    a floor), OpenCV-only and shipped already. `plan()` reports which signal it
    followed (`tracker`: face / motion / none), so a followed rally and a centred
    still are both honest. Measured on a white block sweeping a black frame:
    `tracker: motion`, followed 15/16 frames, the camera actually pans; on a
    still frame with no face it stays centred. The old "no face means centre
    crop" test was rebuilt on a *still* fixture — its testsrc2 fixture moves, and
    a moving faceless frame is now (correctly) followed, not centred.

91. **A sports highlight lands on the burst, and the best one lingers.** Three
    additions for the footage the owner actually shoots (volleyball, gym, jump
    rope), each measured on a recipe-built fixture (`tests/test_sports.py`):
    `action` = the largest frame-to-frame change in a window (a spike is one
    violent frame; a pan is a steady moderate one — the max, not the mean,
    separates them), `presence` = the share of frame pairs with change above a
    floor (an empty court or a rest between sets ranks low), and `slowmo` = the
    single best clip plays at half speed as a highlight beat while the rest keeps
    the reference rhythm (the source window consumed is unchanged, so nothing is
    invented). The sport kind weights action 1.0 / presence 0.9. Both new signals
    come from the grayscale strip one FFmpeg call already decodes.
92. **The blueprint lives in `docs/CuttingEdge/ARCHITECTURE.md`.** Written for a
    reader who has never seen the program: the process topology, the edit model
    (five keyframe channels, opacity deliberately absent), the render engine and
    its CSS-twin preview, the analysis pipeline, Style Match and the intent
    weights, the brain's "a model may only win by scoring higher" rule, the
    on-demand/licence discipline, packaging and the differential update, the
    known-answer testing philosophy, a directory map, and a rebuild-from-zero
    checklist. It is the contract a new developer must not break.

93. **Two external reviews were triaged against the code, not accepted on
    authority.** Items the reviewers flagged that were already true (waveforms on
    the timeline, Ctrl+wheel zoom, an existing shortcut set, an Ollama timeout with
    a rule fallback) were verified and left; the genuinely-missing, safe ones were
    built: J/K/L transport + `,`/`.` frame-stepping; CORS locked from a wildcard
    with credentials to an allowlist (dev origins + the packaged opaque origin,
    no credentials) with a test that a foreign origin gets no allowance; a
    path-injection guard on the media endpoint (null byte / relative → 400); and
    filler-word removal (EN+FA) that strips time-buying tokens from captions as
    whole tokens only, behind `intent.clean_fillers`. Heavy or licence-risky
    suggestions (typed-ffmpeg, YOLO, librosa, Playwright-as-replacement, macOS
    packaging, TransNetV2's torch) were deferred with reasons, not shipped.

94. **Right-click context menu on clips** (the pro affordance both reviews asked
    for): Split at playhead / Duplicate / Delete, rendered through a portal so it
    never clips inside the scroll pane, closed by outside-click / Escape / wheel.
    Mute/Hide were left out of the menu because those flags live on the *track*,
    not the clip — putting them on a clip menu would have lied about the model.

95. **A busy port degrades, never kills; a crash leaves a quotable id.** Port
    discovery: in the packaged app Electron picks the first free port from 8742
    and hands it to the backend (`CE_PORT`) and the renderer
    (`--ce-backend-port` via preload), so a port conflict means "use another
    port", not a silent death; dev keeps 8742 so the Vite proxy lines up. Crash
    reporting: renderer-gone, uncaughtException and unhandledRejection each write
    a small JSON `crash-<id>.json` beside the logs (nothing leaves the machine),
    so a field bug report is a file, not a guess. Both are the P0/1.0 reliability
    items from the advisors' ranked list.

96. **On-demand installs must not need pip.** The packaged backend runs on an
    embeddable CPython that ships **without pip**, so every on-demand fetch that
    shelled out to `python -m pip` died on the user's machine with "No module
    named pip" (reported from the field on the Fetch-model / Fetch-OCR buttons).
    A wheel is just a zip and PyPI's JSON API gives the URL, so
    `core/engine/_pypi.py` fetches and unpacks wheels with the stdlib alone —
    pure `py3-none-any` wheels, or a `win_amd64` wheel matching the interpreter —
    and `runtime_packages.install` falls back to it whenever pip is absent.
    `vad.fetch` uses it too. Measured: the silero model downloads and unpacks
    pip-free, and a pure wheel installed this way imports from the runtime dir.

97. **Every accepted AI engine is now a declared on-demand engine; the rejected
    stay rejected, visibly.** `core/engine/engines.py` is a registry: RIFE,
    whisperX, TransNetV2, Demucs, MediaPipe-pose, CLIP/SigLIP, Real-ESRGAN,
    pyannote, FILM and OpenTimelineIO, each with its verified licence, role and
    explicit fetch list; `/api/engines/status` reports installed/licence and the
    rejected set (YOLO/AGPL, madmom/CC-BY-NC, gl-transitions, Remotion,
    DeepFilterNet-NOASSERTION, librosa) with reasons. Nothing ships; each degrades
    gracefully. A Settings card shows the shelf and the gate.
98. **OTIO interchange is real and round-trips.** `core/engine/interchange.py`
    exports the video lane to `.otio` and reads one back (Apache-2.0, on-demand);
    transitions/keyframes are dropped on export rather than faked. Tested with a
    round-trip.

99. **RIFE arrives as a thin, on-demand, experimental bridge.** Real slow-mo
    needs optical-flow interpolation; `setpts` only holds frames. `core/engine/rife.py`
    bridges the ncnn/vulkan RIFE bindings (`rife-ncnn-vulkan-python`, no torch) and
    is only exercised when fetched; otherwise `available()` is False and the caller
    keeps the `setpts` slow-mo. Any upstream API mismatch raises a clear error
    rather than a wrong frame — experimental means labelled, not unguarded.
100. **First-run tour** as an inline dismissible banner (never a trapping
    overlay): drop → auto-clip 30s vertical → captions → export; shown once via
    localStorage.

101. **AI Transitions button** in the editor rail: one music-sized transition per
    contiguous junction (half a beat, clamped 0.2–0.8s), alternating soft and
    directional types, suggested by `/api/style/ai-transitions` and applied via the
    existing `addTransition` — a first, music-aware pass, not one repeated dissolve.
102. **Transcript/caption engines registered on-demand** (not shipped): Hazm,
    Virastar, whisperX, DadmaTools, Hezar, pyannote, python-ass join the registry
    with verified licences; actual integration awaits the owner's go-ahead.

103. **Persian captions are typeset, not dumped.** `core/engine/persian.py`
    cleans ASR output deterministically (Arabic→Persian letters, diacritics
    stripped, half-space for می/نمی and های/ام, Persian digits when the run is
    Persian, spaces collapsed) and runs Hazm first when fetched. It feeds both
    `subtitles.cues_from_clips` (so libass renders like a typesetter set it) and
    `meaning.score_text` (so discourse scoring sees canonical tokens). Word
    timings are left untouched so karaoke stays in sync.

104. **Every tool the app owns is in the editor's brain — automatically.** The
    owner's standing directive: *anything built from now on is added to the
    editor brain and Style Match without being asked.* Made real in
    `core/brain/editor_brain.py`, whose `TOOLS` inventory is the app's toolbelt —
    now **14 tools**: beat-cuts, slow-mo, captions, **Persian-caption
    normalisation**, karaoke, **filler removal**, ducking, reframe, grade,
    transitions, **RIFE motion-transitions**, hook-first, denoise, **OTIO
    interchange** (the four bolded are capabilities built this session that had
    shipped *without* being in the brain — now folded in). Each has one `assess()`
    decision keyed off a measured signal, so a new tool is *considered*, never
    sprinkled. The convention is written into the module docstring and
    `ROADMAP_1.0.md` §2c, and `tests/test_editor_brain.py` enforces one decision
    per tool plus the four new signals (Persian → normalise, unscripted talk →
    trim fillers, high motion at junctions → RIFE dissolves, handoff asked →
    export OTIO). Suite: **336 passed, 0 failed, 10 skipped** (was 335).

105. **Word-level forced alignment for tighter Persian karaoke — on-demand,
    degrade-safe.** faster-whisper already gives word timings and `subtitles.build_ass`
    already lights them word by word (`{\kf}`); the gap was that those edges
    drift, so a highlight can fire a frame off the word. `core/engine/whisperx_align.py`
    bridges **whisperX** (BSD-3) + the Apache-2.0 Persian wav2vec2 aligner
    (`jonatasgrosman/wav2vec2-large-xlsr-53-persian`) to snap word edges to the
    audio. Like `rife.py` it is a thin defensive bridge, but unlike RIFE it
    **never raises into the caller** — alignment is a *refinement* of timings we
    already have, so on any machine without the engine (or on any upstream
    failure) `align()` returns the input words with `status: no-engine|error` and
    the karaoke keeps working. `transcribe_to_cues(align=True)` uses it and
    reports `alignment`; `/api/captions/align-status` tells the UI honestly
    whether it is fetched. The editor auto-uses it when present (like Hazm for
    text — no separate button) and the toast says "word-aligned" only when it
    actually ran. Deliberately **not** a `TOOLS` entry: it is not a tool the
    editor chooses on/off, it is a quality refinement *inside* the captions/
    karaoke decision — noted here so the §104 convention was considered, not
    skipped. Nothing ships; `torch` stays `heavy` and on-demand.
    `tests/test_whisperx_align.py` (8 tests): no-engine returns words unchanged,
    row conversion (incl. dropping rows missing a time/text), a mid-run failure
    still returns the originals, a successful pass reports the Persian aligner,
    the fetch list excludes torch, whisperX stays a registered engine, and the
    endpoint reports honestly. Suite: **344 passed, 0 failed, 10 skipped**.

106. **The engine shelf stopped being a brochure: every row now says whether it
    can be downloaded on *this* machine, and the button only exists when it can
    win.** The owner photographed Settings with ten "not fetched" rows and asked
    that they actually be downloadable. The audit found six separate lies of
    omission, all fixed:
    • the card had **no fetch button at all** — it now has one per engine, wired
      to `/api/engines/install/start` with a polled task bar;
    • `transnet` was registered under a module name the wheel does not contain
      (the PyPI wheel's top level is `transnetv2_pytorch`, verified by unpacking
      it) — `available()` would have said "not fetched" forever after a
      successful download;
    • `virastar` is **not on PyPI** under any of five names (all 404) — now
      honestly repo-only, its role covered by the built-in `persian.py` + Hazm;
    • `mediapipe` 1.x ships no win_amd64 cp311 wheel — pinned to **0.10.21**, the
      newest release that does (verified against PyPI's release list);
    • heavy engines never fetched their heavy part — `install/start` now takes
      `heavy=true` and adds the torch wheels (~120 MB CPU, opt-in behind a modal
      that states the size);
    • RIFE has no wheel at all: its sdist is 1081 C++ files and the upstream
      GitHub zips stop at Python 3.10 — on a pip-less runtime the row now says
      *why* instead of dying mid-download; where pip exists it remains a source
      build.
    The pip-free installer grew a real sdist path: a pure-Python sdist is
    unpacked by `extract_sdist` (`.py` only, tests/docs skipped) and refused
    outright when it carries compiled code — the check lives in the extractor
    too, because silently dropping binaries would make a package that imports
    and then dies. `engines.probe()` verifies each row against PyPI once per
    process and the card renders `fetchable`/`why`. Proof, not promise:
    `test_python_ass_downloads_end_to_end_through_the_real_endpoint` runs the
    button's exact path (POST start → task poll → `is_installed("ass")`) and
    passed in CI-sandbox.
107. **Roadmap step 1 closes and step 2 opens.** `core/engine/assfile.py` round-
    trips karaoke ASS: a file edited in Aegisub comes back as cues with the word
    timings **reconstructed from the `\\kf` tags** (built-in parser as the tested
    floor, python-ass as the fetched reader), and our cues write out through the
    same `build_ass` the compositor burns — endpoints `/api/captions/ass/import|
    export`. `core/engine/transnet.py` is the TransNetV2 bridge (boundaries when
    fetched, degrade-safe) **plus junction typing that needs no engine at all**:
    cut = one violent frame, dissolve = a ramp, fade = a dip towards black,
    measured with the OpenCV we already ship; `/api/engines/transnet/detect`
    serves both, naming which detector ran. Per the §104 convention both were
    considered for the brain and deliberately filed as refinements, not new
    tools: ASS is another door of the existing `interchange` tool, and junction
    typing sharpens the measurement behind `transitions` — the reasoning is
    written here so the convention was applied, not skipped. Suite: **363
    passed, 0 failed, 10 skipped**.

108. **The brain thinks out loud, the Audio panel extracts, and the home screen
    stops spacing its icons.** Three owner asks, one release-worth of work:
    • **Style Match no longer asks the user anything a measurement can answer.**
      The ten-question intake card is gone. `core/brain/intake.py` makes the
      brain interrogate *itself* — once when the reference arrives
      (`answer_reference`: energy, platform, captions, beat, hook, junction
      softness, length — each with the number behind it), once when the footage
      arrives (`measure_footage` + `answer_footage`: kind, focus, goal, audience,
      speech %, action burstiness, aspect, and an honest "noise not measured
      yet"). Both interrogations render on screen as they run, so the owner
      *watches* the brain think in Style Match — and the Assistant rides the
      chosen plan because it travels with the edit. Then `edit_options` offers
      four genuinely different starts (faithful / short-&-punchy / speech-first
      or motion-montage / calm-cinematic), each carrying the intent payload the
      rebuild already understands; picking one applies it. Guarded by
      `tests/test_intake.py` (answers change with the measurements, options are
      pairwise different, the endpoint answers for both videos).
    • **Audio extraction** in the clip Audio panel: one button lifts the clip's
      audio onto the audio lane, aligned under its picture — pure bundled
      FFmpeg (AAC 192 k), probed so the clip length is exact, written to
      `~/CuttingEdge/exports`; a second button splits stems
      (vocals/drums/bass/other) with **Demucs** (facebookresearch/demucs, MIT —
      the open-source GitHub engine the owner asked for), on-demand and an
      honest 409 until fetched, run as a polled task because torch is minutes.
      `core/engine/audio_extract.py` + `app/routers/audio.py`, guarded by
      `tests/test_audio_extract.py` (a muxed video+audio clip lifts to an
      audio-only file).
    • **Home tiles left-aligned and packed**: `.ce-grid` used
      `repeat(auto-fit, minmax(104px, 1fr))`, which stretched icons across the
      window with big gaps — the owner said the spaced look is ugly. Columns are
      now fixed-size with `justify-content: start` (and the grid stays LTR so
      "left" holds in the Persian RTL UI too).
    Per the §104 convention the two new builds were considered for the tool
    belt: audio extraction is a utility door (the interchange family), and stem
    separation is a refinement *inside* `ducking`'s future beat-accuracy — both
    documented here as considered, not skipped. Suite: **371 passed, 0 failed,
    10 skipped**.

109. **Ducking listens to the vocals stem when Demucs has cached one.** The
    duck envelope used to be measured on the voice clip's raw mix; if that mix
    carries music, the activity curve hears the band as well as the words. Now
    `audio.voice_source()` checks `~/CuttingEdge/exports/<name>.stems/vocals.wav`
    — exactly where the Audio panel's Split-stems button writes — and the
    compositor measures the envelope on that clean stem when present, on the
    raw file otherwise (no stem → byte-identical behaviour, which the unchanged
    ducking-depth test re-proves). A render **never** separates by itself:
    torch is minutes of CPU and must stay a button the user presses, so the
    refinement is a cache hit, not a hidden download. `tests/test_audio_extract.py`
    pins the resolution both ways. Suite: **372 passed, 0 failed, 10 skipped**.

110. **When no face looks at the camera, a person still has a body: MediaPipe
    pose enters the reframe ladder.** The ladder was face → motion-centroid →
    centred; the centroid follows *anything* that moves, including a waving
    crowd. `core/engine/pose.py` bridges **MediaPipe Pose** (google-ai-edge/
    mediapipe, Apache-2.0, pinned `0.10.21` — the newest release with a
    win_amd64 cp311 wheel, §106) and, when fetched, sits between the two: a
    frame with no face asks the 33-point model for the visible torso (mid of
    shoulders+hips, joints under 0.5 visibility do not vote, one visible joint
    is not a person) and the camera follows *the person*; `tracker` reports
    `pose` and the plan's noun says so. Absent engine or upstream mismatch,
    `track_frame` returns None per frame and the centroid takes over exactly as
    before — the bridge degrades, never crashes, nothing ships. The geometry
    (`centre_from_landmarks`) is tested without MediaPipe; the no-engine path
    is tested; the fetched path runs on the owner's machine where the button
    can win. Per the §104 convention this was considered for the tool belt and
    filed as a refinement inside `reframe`'s measurement — documented, not
    skipped. Suite: **376 passed, 0 failed, 10 skipped**.

127. **0.9.41 — Style DNA + a real MCP stdio server close out the tier work.**
    `core/engine/dna.py` (`/api/style/dna`) projects a measured template into a
    deterministic fingerprint — pacing histogram, cut-rate, dominant motion, colour
    mood, BPM, on-beat ratio, talk — surfaced as a badge in Style Match; a recipe an
    agent can store/replay. `core/mcp_server.py` is a local Model-Context-Protocol
    JSON-RPC server over stdio (`python -m core.mcp_server`) exposing the brain and
    the tier tools (`assess`, `parse_nl_command`, `style_dna`, `emotional_arc`,
    `hook_score`, `propose_clips`) so an external agent drives the real editor —
    tested by spawning the server and round-tripping initialize/tools-list/tools-
    call. Backend **493 passed / 0 failed / 10 skipped**; `verify`, `build`,
    `test:ui`, `test:playback` green on the production bundle.

126. **0.9.40 — Hook Lab + the intensity dial complete the tier package.**
    `clips_board.hook_lab` (`/api/board/hook-lab`) returns five measured cold-open
    variants (zoom-punch / jump-in / text-card / reverse-tease / reaction), each an
    executable recipe carrying the hook score of its own window; `reaction` re-aims
    the open at the loudest crowd cue. The Tier-3 intensity slider (`style.
    _apply_intensity`, threaded through `/api/style/apply` and `/api/board/propose`)
    moves caption-pop + zoom-punch + cut-rate together *without moving any measured
    cut* — asserted by `test_intensity_never_moves_a_cut`. Both are wired into the
    Hybrid Tier panel (slider + variant chips, click = seek). Backend **485 passed /
    0 failed / 10 skipped**; `verify`, `build`, `test:ui`, `test:playback` and a
    Hook-Lab browser check all green on the production bundle.

125. **0.9.39 — the professors' tiers 1–3, shipped as one package.** Tier 1:
    transcript-first editing + one-click jump cut (`core/engine/transcript_edit.py`,
    `/api/transcript/*`) — keep/cut are exact complements and apply through the
    existing ripple `keepRanges`; emotional arc + hook score
    (`core/engine/arc_hook.py`, `/api/board/arc|hook`). Tier 2: batch clips board +
    sports/gym markers (`core/engine/clips_board.py`), the social export pack
    (`core/engine/export_pack.py`, `/api/render/export-pack`: MP4+SRT/ASS+thumb+
    description.md+meta.json+OTIO, video copied never re-encoded), and the cut
    inspector (`/api/board/explain-cut`). Tier 3: an agent-native surface
    (`app/routers/agent.py`: `/api/agent/tools|nl|call`) mapping the brain's 17 tools
    to JSON-Schema actions with a deterministic fa/en NL parser. Seven new brain
    tools (`text_based_edit`…`agent_tools`) registered with measured decisions. One
    Hybrid panel (`editor/TierPanel.tsx`) wires arc/hook/markers/jump-cut/inspector/
    agent; screenshots in `ui-proposal/hybrid-tier-panel.png`. Suites: backend
    **480 passed / 0 failed / 10 skipped**; `verify`, `build`, `test:ui`, `test:playback`
    and a TierPanel browser check all green on the production bundle. Tier 0 gates
    (filmed clean install, workflow paste, real-GPU NVENC number) remain owner-side
    and are unchanged.

124. **0.9.38 — a stabilization release: nothing new shipped, everything re-proven.**
    The sandbox wiped `/tmp`, `node_modules` and the live servers between turns,
    so the whole stack was rebuilt from nothing (`sandbox-test-env.sh` +
    `npm i`) and the *already-shipped* 0.9.37 code was re-executed end to end on
    the fresh environment: backend **454 passed / 0 failed / 10 skipped**;
    `npm run verify` and `build` green; `test:ui` PASSED and `test:playback`
    all-checks-passed against the production bundle; the three 0.9.37 doors
    (`/api/emotion/status`, `/api/providers`, `/api/multicam/align`) answered
    live, with multi-cam honestly reporting a weak match for unrelated clips.
    No code changed — this number exists so the published installer always
    corresponds to a state that was rebuilt and re-verified from a cold start.

123. **The three deferred advisors' items are built, not promised (B2, B3, B8).**
    *B2 Cut-on-Emotion*: `core/engine/emotion.py` measures the *sound* of a
    reaction with the maths the backend already ships — `crowd` (broadband, loud,
    not tonal), `voiced` (tonal bursts with a 2–9 Hz rhythm), `whoosh` (a fast
    broadband rise that dies again) — and feeds `joy = 0.9·crowd + 0.3·voiced`
    into the highlight scorer as one capped vote (`MAX_WEIGHT 0.25`, on by
    default, toggle + *Measure it* in Settings). Measured on synthetic fixtures:
    applause joy 0.57 vs a steady voice 0.30, and a sustained clap bed no longer
    reads as a whoosh. There is no `vit-fer` on PyPI (404 ×3), so there is no
    wheel whose METADATA could be read — the visual half is two honest optional
    doors instead: MediaPipe FaceLandmarker blendshapes (Apache-2.0, fetched on
    demand, named as *action units*) and an `emotion.score` provider.
    *B3 Multi-cam*: `core/engine/multicam.py` finds each angle's offset by the
    normalised cross-correlation of their audio — the lag is the offset and the
    peak is the confidence, verified numerically to have the right sign — then
    builds a switch plan whose cuts follow the loudest talker / crowd with a
    minimum dwell and hysteresis. Driven end to end in the browser: two angles
    imported, aligned (offset +2.0 s on a delayed copy, confidence 1.0), planned
    in crowd mode (one switch at the burst), and the segments landed on the
    timeline.
    *B8 Providers*: `core/providers/channel.py` is a VSCode-style channel into
    `~/CuttingEdge/providers/<name>/` — a manifest declares `licence` and
    `capabilities`, the provider runs as its **own subprocess** over a line-JSON
    protocol, and nothing is imported, so GPL or licence-less code can be used
    without touching this app. Two capability consumers are wired for real:
    `captions.polish` (one batched call after the built-in clean, with a rewrite
    guard) and `emotion.score`. Broken, slow, lying or disabled providers are
    reported and ignored, never raised.
    The brain's toolbelt gained `cut_on_emotion`, `multicam` and `providers`
    (each with a measured decision, and `denoise` now honestly offered only when
    a provider declares `audio.denoise`). Suites: backend **454 passed / 0
    failed / 10 skipped** (was 414 before these); UI audit PASSED; playback
    all-checks-passed; a targeted 13/13 browser check of the three new cards plus
    a full multi-cam E2E, all on the production bundle.

122. **0.9.36 — sections A, B, C finished and wired to the new UI.** A done:
    A4 `_safe_path` gate on captions/render/media (absolute, no traversal, exists,
    ≤4 GB); A5 Ollama warm-up ping with `warmup slow` in the scoreboard note;
    A9 `TransportClock` subscribes alone so playhead ticks stop re-rendering
    Studio; A10 JSON-lines rotating log at `~/CuttingEdge/data/logs/backend.jsonl`;
    A11 axios interceptors (503 → readable warning, optional 404 silent,
    correlation id + ms logging); A12 already conformant. B wired and producing
    output: B1 Director Mode (mic → on-device faster-whisper → assistant plan,
    shown before anything touches the timeline); B4 beat-synced word pop, twin in
    preview (CSS, duration = 60/bpm) and in the ASS export (`\t` scale pop when
    bpm ≥ 60, bpm rides in the project document); B5 recipes saved as shareable
    JSON in `~/CuttingEdge/recipes` (+ save button on the Style Match result);
    B7 storyboard endpoint (`/api/media/storyboard`, top-10 informative frames)
    with a modal in the MediaBin; B9 F3 performance HUD (fps / ws events/s);
    B10 every planner's picks ride the scoreboard and each row has
    «همین برنامه» which re-applies that exact plan via `use_plan`. Suites:
    backend 424/0/10; verify/build green; ui-audit PASSED; playback all-checks-
    passed on the production bundle.

121. **0.9.35 — the Hybrid redesign from the root + advisors' P0/P1 fixes.**
    The professors' `v0.9.34-hybrid-design-system` package was audited item by
    item (report in chat): `design-tokens.css` ported as the source of truth and
    every legacy variable remapped onto it (near-black minimal surfaces, neon
    pink playhead/primary with glow, cyan info, purple text lane, 4px spacing,
    type scale C2); the reference `Scoreboard` component (cyberpunk variant)
    replaces the plain brain line; source docs + screenshots live in
    `docs/CuttingEdge/HYBRID_*` and `ui-proposal/hybrid-*.png`. Debug items A1–A3,
    A6–A8 fixed for real (concurrent WS broadcast with per-client timeout, pip
    watchdog, thread-local sqlite, drag latest-ref, RAF pause on hidden tab, WS
    exponential backoff + manual reconnect). Verdicts for the rest (A4/A5/A9–A12,
    B1–B10, C7–C14) are recorded with accept/defer/reject reasons. Suite
    424/0/10; ui-audit + playback green on the production bundle.

120. **The owner's three field bugs + Package C, released as 0.9.34.**
    (1) "Fetch + torch" meant nothing to the owner — relabelled "دانلود موتور +
    هستهٔ هوش مصنوعی" with a one-line explanation (torch = the one-time ~120 MB
    base library) and the modal says the same in plain words; unavailable rows
    now carry a green alternative line (RIFE→setpts slow-mo, FILM→RIFE,
    virastar→built-in cleaner). (2) Downloads "stuck at 10%": the pip-free
    installer reported once per package, so a 120 MB torch wheel sat silent for
    minutes — `_pypi.download_wheel/sdist` now stream chunks with
    `on_bytes(done,total)` and the task label shows real megabytes
    ("torch 34/122 MB"). (3) open-unmix said "unavailable": PyPI publishes that
    project as **`umx`** ("open-unmix" 404s) — deps fixed, it fetches now.
    Package C: chapters from the transcript (`chapters.suggest_chapters`,
    boundaries only at cue edges, marker-named titles) with a panel button that
    drops chapter cards on the text lane; guarded hook-title
    (`captions_llm.hook_title`, ≤80 chars/14 words else the first cue stands);
    three one-click recipes (punchy sport reel / calm lesson / story vlog) on
    Style Match that arm a template plus intent. Suite 424/0/10; verify+build
    green.

119. **Package B — creator tools, done.** Screen/webcam recorder
    (`RecorderModal`, MediaRecorder → POST `/api/render/recordings/save` →
    `~/CuttingEdge/recordings` → auto-import onto the timeline; nothing leaves
    the machine); brand kit: watermark text + animated progress-bar overlay
    (`compose` `progressBar`/`brandText`, drawbox `eval=frame`, drawtext only
    when a real font exists — absent font skips, never fails a render); export
    queue: extra platform shapes (1:1/4:5/16:9/4K) render alongside the main
    export; brand choices persist in localStorage. Verified: 419/0/10 backend,
    production bundle UI AUDIT PASSED + playback all-checks-passed + record
    button present in the built app.

118. **Package A — captions at Veed grade, built local-first.** (1) quality
    ladder `auto/fast/balanced/best` → base/medium/large-v3 with a chooser
    before transcription; asking for an absent rung returns 409 + the size and
    the UI offers the one-time fetch (model lands in the user's HF cache,
    survives updates); no silent downgrade to `base` ever again. (2) multilingual
    spelling floor `core/engine/text_polish.py`: fa (existing normaliser), en
    true-casing + punctuation spacing + contractions, ar shape normalisation,
    shared whitespace/repeat/punct layers — runs on every cue, after alignment.
    (3) local-LLM proof-read `captions_llm.refine_cues` with a hard guard
    (same line count, word count ±2, similarity ≥0.55 — else the recogniser's
    text stands) plus translation with immutable timings. (4) confidence
    surfacing: words carry `prob`; the monitor tints unsure words amber and the
    new Captions panel lists them as chips — click edits the word in place as one
    undoable step (`patchCaption`); SRT export/import (`~/CuttingEdge/exports`,
    `~` expanded server-side). (5) every task stage now carries a human label
    (the old empty "starting" stage failed the playback suite's own invariant —
    fixed in `core/tasks.py`, an app improvement, not a test dodge). Tests:
    415 passed / 0 failed / 10 skipped; on the production bundle test:ui
    PASSED and playback all-checks-passed.

117. **The sixteen — the visual wave that turns 2/20 into ≥12/20, all executed,
    tested on the production bundle, published as 0.9.33.** (1) aurora ambient
    light + depth-layered glass; (2) DaVinci-flavoured timeline: 64px lanes,
    gradient clip headers, glowing snap guide, playhead time-bubble; (3)
    cinematic monitor: letterbox/safe-area/vignette toggles + a live luma scope
    sampled at 64×36 every 400 ms; (4) bold typography + tabular timecodes;
    (5) living wordmark + one-second launch splash; (6) motion system: staggered
    rise, hover lift, ripple, skeletons; (7) sonner-style glass toasts; (8)
    heartbeat empty states; (9)+(13) BrainBar status strip with pulsing brain
    chip, clips/aspect/GPU/version; (10) recharts bar charts of the brain's
    measured signals in Style Match; (11) assistant gradient bubbles, typing
    dots, pill suggestions; (12) collapsible inspector sections; (14) `?`
    shortcuts sheet; (15) embla-carousel project reel with hover lift; (16)
    four-accent theme picker, saved per device. Verified on `vite preview`
    (production bundle): test:ui PASSED, playback all-checks-passed, targeted
    checks (brainbar, splash, shortcuts, monitor toggles, accent, bubble) all
    true with zero console errors. Backend suite unchanged at 404/0/10.

116. **0.9.32: the filmed clean-install gate, scripted.** The last 1.0 item a
    sandbox cannot run is watching the app be born on a real Windows machine,
    so `docs/CuttingEdge/CLEAN_INSTALL_CHECKLIST.md` now scripts that film, fa
    + en: uninstall → install → first launch with zero errors → differential
    update under 50 MB on camera → exactly one file picker → no blue selection
    → Ctrl+K captions → Style Match brain Q&A → export → autosave restore. A
    stumble at any step is the gate. Released as 0.9.32 on the owner's word so
    installed clients also get a fresh nudge.

115. **The 0.9.31 debug round: everything re-tested by running it, on the
    PRODUCTION bundle, then published.** (a) The "Import fires twice on entry"
    complaint was reproduced-hunted on a `vite build` + `vite preview` run: the
    entry guard held (exactly one picker), and a second lock (`pickerBusy`
    around `importMedia`) now makes a double fire impossible from *any* door —
    entry effect, toolbar, palette, MediaBin. (b) No more word-processor blue:
    app-wide `user-select: none` with inputs/textareas re-enabled; a puppeteer
    drag across Home and the Studio toolbar leaves `getSelection()` empty.
    (c) The official suites now audit the final bundle, not only dev: the
    `__ceEditor` handle is exposed in production too, and the CORS allowlist
    gained the local preview port — `test:ui` and the full `playback-test`
    (all checks) now pass against `vite preview`. (d) A click-around on the
    production build verified live: tiles navigate, MediaBin shows/toggles,
    Ctrl+K opens/filters/Esc-closes, the Text tool adds a clip and Ctrl+Z
    removes it, zero console errors. Backend suite 404/0/10. Published as
    0.9.31 on the owner's instruction.

114. **The UI/UX rebuild the professors demanded (2/20 → aimed ≥12/20),
    implemented for real, not mocked.** From their two blueprints, triaged in
    `BRAIN_UPGRADE.md` and proven first as mockups (`ui-proposal/*.png`,
    including the *implemented* shots): (a) the 0.9.31 design tokens — layered
    darks (#0A0E17→#243044, never pure black), brand = the wordmark's
    violet+cyan, semantic success/warning/danger, softer borders; (b) real
    Inter + JetBrains Mono (timecodes) + Vazirmatn, all bundled offline; (c)
    timeline to spec: hue-coded lanes (video #3B82F6, audio #10B981, text
    #A78BFA), 8px accent keyframe diamonds, rose playhead glow, mono ruler;
    (d) the Studio's new panel grammar — a **MediaBin** library pane derived
    from the timeline itself, and a **resizable** working-surface↔timeline
    split (`react-resizable-panels` v4 Group/Panel/Separator); (e) **Ctrl+K
    command palette** (cmdk, MIT) wired to the *real* handlers in both
    languages; (f) glass toasts/modals and gradient CTAs on Home; (g) a11y:
    :focus-visible rings everywhere. Guards: `npm run verify` OK, `test:ui`
    PASSED (one new overlap fixed by capping the monitor inside its panel
    share), `test:playback` — **all checks passed** on the restructured editor
    (reframe, captions, assistant dry-run, undo all green). Suite/backend
    untouched and green (404). No version bump — publishes on the owner's word.

113. **1.0 hardening opens: tripwires, a manual, the other half of the taste
    loop, and eyes on the packaged window.** (a) `tests/test_perf_regression.py`
    is a ratchet, not a benchmark: caps set from a real clock on 3 s fixtures
    (motion_curve 0.03 s, silence 0.02 s, beats 0.02 s, scenes 0.20 s, build_ass
    <1 ms; caps 25–100× above) so a slow runner never flakes but a per-frame
    FFmpeg loop — the class this project actually shipped once (§16) — trips
    every wire. (b) `docs/CuttingEdge/MANUAL.md`: the one-page manual, Persian
    first, true of 0.9.30 — home, editor, the brain's visible interrogation,
    engines shelf, assistant, diagnostics. (c) The taste loop's missing half:
    the result card now has **«این را نپسندیدم»** posting `rejected` to
    `/api/brain/feedback`; the prior stays bounded either way. (d)
    `scripts/packaged-ui-audit.mjs` attaches to the real packaged Electron
    window over CDP (`--remote-debugging-port`) and runs the render/no-error/
    overflow checks at the artefact level; the canonical workflow copy
    (`ce-app/ci/ce-workflow.yml`, the file the owner pastes into
    `.github/workflows/ce.yml`) gained the step after the smoke test. That step
    runs on the Windows runner, not in this sandbox — stated, not pretended.
    Suite after: **404 passed, 0 failed, 10 skipped**.

112. **The professors' blueprints, triaged line by line — nothing dropped
    silently.** Both upgrade plans (InternVL/LangGraph/ChromaDB list and the
    FeatureBus/critic list) were read in full and triaged in
    `docs/CuttingEdge/BRAIN_UPGRADE.md`: built this release — the FeatureBus
    (`core/engine/features.py`, honest `unknown` list), the auto-editor-inspired
    motion curve + `keep = speech OR motion` (Public Domain), meaning 2.0
    (`narrative_arc`: hook/payoff/Q→A from markers, feeding a new objective
    term), three new planners (narrative/retention/variety) that only emit
    measured times, the bounded **critic** (≤2 revisions, bottom-quantile picks
    swapped for unused highlights, rule plan as floor, `…+critic` on the
    scoreboard), taste memory as a stdlib JSON prior clamped 0.75–1.33 with
    `/api/brain/feedback` and the frontend reporting accepted edits, the
    contact-sheet vision upgrade (4 frames/window, blend still ≤ 0.3),
    `clip_embed.py` (open_clip on-demand, absent → renormalise, never fake),
    glm-4v:9b in the Ollama catalogue for reasoning-on-scoreboard. Registered
    on-demand: sentence-transformers, librosa (moved OUT of REJECTED — the
    objection was shipping it to everyone), open-unmix, DOVER. Rejected with
    reasons: Essentia (AGPL binds even on-demand), LangGraph/ChromaDB as
    dependencies (architecture adopted natively, packages are tax),
    InternVL/VideoLLaMA/Molmo (HF-gated multi-GB; roles covered by catalogued
    Ollama vision + MediaPipe + CLIP), RTMPose (mmcv closure), YOLO-World
    (weight licences unverified), pyAudioAnalysis (deferred: scipy closure for
    signals we already measure). Suite: **399 passed, 0 failed, 10 skipped**.

111. **The brain fires from every door.** The owner re-sent the same three asks
    (Audio extraction, brain-asks-itself Style Match, left-aligned home) after
    they had shipped in 0.9.29 — so every claim was re-verified live instead of
    trusted: the backend served `/api/style/brain` (Persian Q&A with the numbers
    behind each answer) and `/api/audio/extract` (a 3.02 s `.m4a` in the exports
    dir) over HTTP; `npm run test:ui` walked all routes green; a targeted
    Chromium check on `#/style` rendered with **zero console errors, the old
    questionnaire absent, the reference card present**; the home grid rule is at
    `global.css:275`. The audit also exposed one real gap: the *"Only analyse a
    reference"* door set the template without waking the brain — `analyse()` now
    calls `askBrain` too, so whichever door the first video arrives through, the
    self-interrogation is on screen. Version stays 0.9.29 until the owner says
    to publish; the fix rides the next release.

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

## 5b. Open-source survey

`docs/CuttingEdge/OSS_SURVEY_0.3.8.md` is the verified list (GitHub API + Hugging Face
API + PyPI, on the day of writing) of what we may and may not use. Headlines:

* MediaPipe (Apache-2.0) unblocks real auto-reframe; **Ultralytics YOLO is AGPL-3.0**,
  so every "MIT" reframe repo built on it is unusable for us.
* `piper` is MIT on GitHub and **GPL-3.0-or-later on PyPI** — always check the wheel,
  not just the repo. It has the only good local Persian voices, so it belongs in the
  plugin channel as a separate process.
* madmom's beat models are CC BY-NC → use librosa (ISC).
* wavesurfer.js (BSD-3) for audio waveforms, OpenTimelineIO (Apache-2.0) for project
  interchange, DeepFilterNet (MIT/Apache) for denoise, Demucs (MIT) for ducking.

## 5c. Third-party roadmap review

`docs/CuttingEdge/ROADMAP_REVIEW.md` audits the `video-editing-app-roadmap` archive the
user uploaded to the `Gif` branch. Short version: the phases match ours and no repository
in it is invented, but Remotion (commercial), Shepherd (AGPL), pedalboard (GPL-3),
pyvideotrans (GPL-3) and GSAP (no licence file) cannot be shipped, `edge-tts` is an online
service rather than local, and celery/better-sqlite3/dnd-kit/i18next are the wrong tools
for a single-process desktop app. Adopted from it: bezierjs, colour-science,
freesound-python, apscheduler.

## 5d. Style templates from a reference video

`docs/CuttingEdge/STYLE_TEMPLATES.md` — the feasibility study for the "Roll"-style
feature the user asked about (send a video, get a template). Verdict: the *editing
grammar* is measurable and transferable (shot rhythm, cut-on-beat ratio, camera motion,
colour look, caption style and rhythm, hook shape, ducking depth) and steps 1–3 need no
new dependency. What is impossible is stated there too, so nobody promises "one click,
same video". Licence notes: OCR (PaddleOCR/EasyOCR/Tesseract) and OpenCV and MediaPipe
are Apache-2.0, TransNetV2 is MIT; **ultralytics is AGPL and ImageBind is CC-BY-NC** —
both unusable here.

## 5e. The brain: Whisper + Ollama

`docs/CuttingEdge/BRAIN_DESIGN.md`. The division of labour that must not be blurred:
**signal processing measures, Whisper transcribes, the LLM judges, arithmetic decides.**
An LLM cannot see the video, so it is never asked how many shots or what tempo — it is
asked which moments tell a story and what the caption should say. Candidate plans (rule
planner, Ollama planner, optionally a second model) are scored by one objective function
— duration fit, speech integrity, on-beat cuts, silence avoided, highlight strength,
variety, shot-length match — and the best wins. The rule plan is always a candidate, so a
bad LLM answer can never be worse than offline. Whisper gets a second pass only when its
own confidence is low.

**One brain, two doors** (§7 of that document): the Assistant button and Style match are
the same pipeline — same operation whitelist, same validator, same single undoable step —
with one difference that must not be blurred. Style match has an objective target, so
candidates can be scored and raced; a free-form prompt has none, so it gets a dry-run
preview and undo instead of a fake score.

## 6. Next, in order

**Four steps to 1.0** — the table with the state of each, measured from the code
on 2026-08-24, is at the top of `docs/CuttingEdge/ROADMAP_1.0.md`:
template gallery and title/sound packs → audio depth (DeepFilterNet, measured in
dB or dropped) → a vision model that has actually seen frames → stabilisation
(tour, crash reporting, manual, attribution screen, a filmed clean install).

The full plan, with the measurement each step has to pass, is in
`docs/CuttingEdge/ROADMAP_1.0.md`. The short form:

1. ~~0.6.0 — nothing waits in silence.~~ **Shipped.** Measured: start 1–4 ms,
   7–8 stages reported, Stop honoured in 0.2 s, a ten-minute reference analysed
   in 35.5 s without a timeout.
2. **0.6.1 — slim the installer, part one: shipped.** The published installer
   went **458 MB → 305 MB**. The never-imported
   packages are gone: **378.3 MB → 137.9 MB** of wheels, 108 → 50 packages,
   measured with `uv pip compile --python-platform windows`. Part two is the
   speech stack (`ctranslate2` + `av` + `onnxruntime` + `tokenizers` ≈ 62 MB
   here) fetched on demand through the AI runtime card. The user should report
   the installer size and the next differential update — it must stay < 50 MB.
3. **0.6.2 — Style Match measured, not adjusted.** `AdaptiveDetector`, affine
   push/pull, and colour transfer as a curve; each scored on the known-answer
   fixtures, winners only, scoreboard published.
4. **0.7.0 / 0.7.1 — the brain.** Objective score, rule planner and Ollama planner
   raced, Assistant dry-run preview; then highlights chosen from what was said.
5. **0.8.0 — real face tracking** with MediaPipe as an on-demand engine, with a
   stated pixel error before the `BETA` badge comes off.
6. **0.8.1 → 1.0 —** template gallery and title/sound packs, YouTube publishing,
   optional DeepFilterNet, then stabilisation: tour, manual, attribution screen,
   and a clean install filmed doing a whole edit.

Done in 0.5.3: the three failures reported from the installed app — the 30 s
timeout, the Ollama model mismatch, and the CUDA-less Whisper.
Done in 0.5.2: the AI runtime card in Settings — installed / running / models /
measured latency for Ollama and Whisper, with an honest refusal to install other
people's software silently.
Done in 0.5.1: Style Match became fully automatic (captions and a ducked music bed
placed without a prompt, with an honest list of what was and was not done), and
ducking moved from a sidechain to a computed envelope after parallel test runs
proved the sidechain fragile.
Done in 0.5.0: **Style Match** — a tile on the home screen that measures a
reference video into a `.cetemplate` and rebuilds the user's footage in its shape,
shown shot by shot before it opens in the editor. Ducking's sidechain moved to a
dedicated input (an `asplit` key starved under parallel load).
Done in 0.4.4: automatic ducking — mark a music bed and it steps aside for the
voice on every word (sidechain compression in the export, approximated live).
Done in 0.4.3: the update card on the home screen (regression fix — 0.4.1 made
updating unreachable), Settings and Diagnostics reachable again, projects list
refreshes on arrival.
Done in 0.4.2: waveforms on the audio lane, beat detection (own implementation,
no new dependency), the beat grid on the ruler and cut-on-beat.
Done in 0.4.1: the bars are gone (menu bar, tabs, heading, properties, save bar),
the wordmark navigates home and animates between hero and docked, readable toasts,
unfinished projects and deletion on the home screen.
Done in 0.4.0: keyframes (x, y, scale, rotate, volume) in the monitor and in the
export, with markers on the clip and a panel that keys at the playhead.
Done in 0.3.9: immersive sections (the chrome fades, the section fills the window,
Escape or the top edge brings it back), route transitions with `framer-motion`
(MIT), mute/hide split on lanes, pressed-state toggles.
Done in 0.3.8: centred playhead, 720p editing proxies, ripple/roll/slip trims.
