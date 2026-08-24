# Cutting Edge — current state

**Read this first.** Working sessions are wiped every few hours; this file, the
code and the docs next to it are the only things that survive. Everything below is
verified, not planned.

Branch: `arena/01a0214a-chat2db` · App version: `0.9.3` · Last released: `v0.9.2` (installer 323 MB) (installer **323 MB**; 458 → 305 by dropping ballast, +18 for shipping bytecode again)

**The plan is in `docs/CuttingEdge/ROADMAP_1.0.md`** — release by release from
here to 1.0, each with the number that has to move. Read it after this file.

---

## 1. What is actually in the product

| Area | State |
|---|---|
| Backend | FastAPI + SQLite on port **8742**; job pipeline (ingest → prepare → transcribe → select → reframe → subtitle → export) |
| **Render engine** | `core/engine/compose.py` — the edit model becomes one FFmpeg `filter_complex`; NVENC when present, libx264 otherwise; progress streamed over the WebSocket |
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
| **Text & captions** | Text clips rendered with libass (correct Persian shaping and bidi), four styles, three positions, colour and highlight, word-by-word karaoke; automatic captions from `faster-whisper` with pause-aware line breaking |
| **Audio cleanup** | Spectral noise reduction and a voice-enhance chain (high-pass, presence, compression, -16 LUFS) |
| **Assistant** | Floating button: a sentence in English or Persian becomes whitelisted timeline operations, validated and applied as one undoable step. Works offline with rules; uses Ollama/OpenAI/Gemini/Claude when configured |
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

## 3. The checks that protect the product

| Command | What it guards |
|---|---|
| `python -m pytest` (in `ce-app/backend`) | render engine geometry/duration/audio, the silent-source regression, silence and scene detection against known ground truth, and `test_effects.py` / `test_keyframes.py` / `test_audio.py` / `test_proxy.py` — which measure the exported pixels, the animated expressions, the beat detector against synthesised click tracks and the proxy pipeline — 86 tests |
| `npm run verify` (in `ce-app/frontend`) | TypeScript plus the renderer↔preload bridge contract |
| `npm run test:ui` (in `ce-app/frontend`) | every route renders, no overlapping boxes, no horizontal overflow, one screen mounted after rapid tab switching, language switch flips direction and persists |
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
