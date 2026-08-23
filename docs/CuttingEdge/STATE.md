# Cutting Edge — current state

**Read this first.** Working sessions are wiped every few hours; this file, the
code and the docs next to it are the only things that survive. Everything below is
verified, not planned.

Branch: `arena/01a0214a-chat2db` · App version: `0.4.4` · Last released: `v0.4.3`

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
13. **A sidechain that runs out kills the signal.** Automatic ducking uses
   `sidechaincompress`, and two things about the graph are load-bearing, both
   found by measurement: the voice must be **split before `apad`** (splitting one
   already-padded stream starves the compressor), and the key branch is padded
   **without a limit** (`-t` on the input can end the voice a few samples early,
   after which the compressor emits silence for the rest of the timeline — the
   music simply vanished at 4.2 s). Output length still follows the main input.
   Measured in `tests/test_audio.py` with a 220 Hz bed and a 300 Hz voice, using
   a bandpass so the bed can be judged inside the finished mix.
14. **Removing navigation removes features.** Deleting the tab bar in 0.4.1 also
   deleted the only path to Settings — and the update button lives there, so the
   user could not update the app at all. The updater is now a card on the home
   screen (version, check, progress, install) with a gear and a stethoscope next
   to it, there is a Settings tile in the grid, and `playback-test.mjs` asserts
   every one of those is present and actually navigates. **Before removing a
   route from the interface, list what is only reachable through it.**
15. **A saved project must appear immediately.** The home query cached for five
   seconds, so coming back from the editor after saving showed nothing — which
   looks exactly like a failed save. It now refetches on mount and on focus.
16. **Waveforms and beats are ours, not a dependency's.** `core/engine/audio.py`
   decodes with FFmpeg and does the maths in NumPy: a bucketed min/max envelope
   for the timeline (cached, clamped to 4000 points) and beat detection by
   spectral flux + autocorrelation. librosa would have added numba/scipy for
   forty lines, madmom's models are CC BY-NC and audiowaveform is GPL. Watch the
   **octave trap**: autocorrelation prefers double the true period, so a 150 BPM
   track reads as 75 unless half the winning lag is checked — that correction is
   in the code and in `tests/test_audio.py`, which measures 90/120/150 BPM
   click tracks it synthesises.
17. **"No audio" and "past the end" are answers, not errors.** A silent video
   returns an empty envelope (200) and a thumbnail request beyond the source
   returns the last frame. Both used to 422 and fill the console with failures
   for perfectly normal footage.
18. **The wordmark is the only chrome.** No Electron menu (`Menu.setApplicationMenu(null)`
   plus `autoHideMenuBar` — that white strip survived fullscreen), no tab bar, no
   heading band, no properties panel, no save bar. The wordmark is a shared
   `layoutId` element: centred on the launcher, docked top-left in a section, and
   it is the way home. Anything that used to live in a bar now lives on the
   launcher.
19. **Persistence stays even when its UI goes.** `ProjectAutosave` is headless:
   autosave every 20 s, `Ctrl+S`, and a flush on unload. Unfinished work is
   offered as the first card under "Recent projects" on the home screen — where a
   person looks for it — and every saved project has a delete button.
20. **A message nobody can read is no message.** Static antd toasts/tooltips render
   outside the theme provider and appeared as blank white shapes; they are styled
   in `global.css` and the browser test asserts the notice's computed background
   is dark.
21. **A keyframe the export cannot reproduce is a lie told twice.** Keyframes exist
   for exactly the five channels FFmpeg can genuinely animate — x, y, scale,
   rotate, volume — built by `keyframe_expression()` in `compose.py` as
   piecewise-linear `if(lt(t,..),..)` chains (commas escaped!). Opacity is
   deliberately absent: it needs a per-pixel `geq` pass; fade in/out and the
   in/out animations cover that case. Animated geometry switches the clip chain
   to `scale=eval=frame` plus an `overlay` onto a transparent canvas, which is
   the only combination that reproduces "scale about the centre, then translate".
   Static clips keep the old, fast chain — `tests/test_keyframes.py` asserts that.
22. **Mute silences, hide blanks — never the same switch.** Muting a video lane
   used to remove it from the monitor (black screen). A lane now has two flags:
   `muted` (audio only, speaker icon) and `hidden` (picture, eye icon), and the
   compositor makes the identical distinction — `tests/test_effects.py` renders
   both cases and measures the frame.
23. **One React instance.** Adding `framer-motion` to a running dev server
   produced "invalid hook call" from a duplicated React in the optimiser cache
   while `tsc` stayed silent. `vite.config.ts` now sets
   `resolve.dedupe: ['react', 'react-dom']`.
24. **A toggle must look pressed.** The clip Mute tool worked all along but gave
   no feedback, so it read as broken. Toggles in the rail now render with an
   active state and confirm with a toast.
25. **The preview may use a proxy, the export never may.** Import builds a 720p
   H.264 copy (keyframe every 15 frames) in a worker thread for anything wider
   than 1280 px; `clip.proxy` is used by `PreviewMonitor` only, and
   `tests/test_proxy.py` asserts the render command still points at the original.
26. **Centred playhead is a view mode, not a model change.** The marker is pinned
   to the middle and the lane carries half a viewport of padding on both sides, so
   `scrollLeft === playhead * pxPerSecond`. Scroll events set the playhead and the
   playhead sets the scroll — the loop is broken with a `programmatic` flag, not
   with timers. The classic mode is one click away in the timeline corner.
27. **A timeline needs frames.** Clips were flat colour rectangles; they now draw a
   film strip from `GET /api/media/thumb?path&t&h` (one JPEG per frame, cached in
   `~/CuttingEdge/data/thumbs`, times quantised to 0.1 s so zooming reuses the
   cache). Scale is by Ctrl+wheel or a two-finger pinch, anchored under the
   pointer — no slider anywhere, like the phone editors we are compared with.
28. **Home starts sessions, the rail edits clips.** Catalogue entries carry
   `place: 'editor'`; those tiles are gone from the home screen and appear in the
   editor's global tool rail instead (captions and silence removal run in place,
   the rest open their own screen).
29. **The monitor is the canvas, not a 16:9 box.** A phone video used to appear as
   a thin strip between black walls; the stage now takes the project ratio
   (`aspect`, default `auto` = the first video clip's real pixel size) and the
   export dialog opens on the matching format. Clips carry `width`/`height` from
   the probe for this.
30. **Advertised shortcuts must exist.** The buttons said "Delete", "S", "Ctrl+Z"
   while nothing listened for a key; Studio now owns one `keydown` handler and
   skips inputs, textareas and modals.
31. **Panels the timeline can open.** The tool rail's open panel lives in the store
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

## 6. Next, in order

1. Slim the installer: fetch the Python runtime and models on first launch
   (~479 MB → ~120 MB). Delta updates are verified working (< 50 MB per update on
   the user's machine), so this is measurable — re-check that number right after.
2. Real MediaPipe face tracking for auto-reframe (currently centre-crop).
3. YouTube publishing (google-api-python-client, Apache-2.0).
5. Template gallery, title animation pack, sound-effect pack (freesound, MIT client).

Done in 0.3.8: centred playhead, 720p editing proxies, ripple/roll/slip trims.
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
3. Slim the installer: fetch runtime and models on first launch (~479 MB → ~120 MB).
