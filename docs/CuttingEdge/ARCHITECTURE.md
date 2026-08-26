# Cutting Edge — Engineering Blueprint

> A document for someone who has **never seen this program** and must be able to
> rebuild it from zero. It describes what the app *is*, how every layer is shaped,
> the invariants each layer protects, and how the pieces talk to each other. It is
> descriptive (what exists) plus normative (the rules a change must not break).
>
> Version described: **0.9.21**. The living state log is `STATE.md`; the release
> plan is `ROADMAP_1.0.md`; this file is the *shape* of the system.

---

## 1. What the product is

Cutting Edge is a **desktop video editor for short-form video** with an
**AI auto-edit** pass. A user drops in long footage (a talk, a volleyball rally,
a gym set) and either edits it by hand on a multi-track timeline, or asks the app
to rebuild it in the *editing grammar* of a reference video they like (Style
Match), or asks a conversational assistant to make a change in one sentence.

Three surfaces share one engine:

1. **The editor** — manual, frame-accurate, undoable.
2. **Style Match** — "measure a video I like, cut my footage the same way".
3. **The assistant** — a sentence becomes validated, undoable timeline operations.

Everything that can be measured is measured; everything that cannot is said out
loud. That sentence is the whole culture of the codebase, and most modules exist
to keep it true.

---

## 2. Process topology

```
┌──────────────────────── Electron main (Node) ────────────────────────┐
│  spawns & supervises the backend · auto-update · window · IPC bridge │
└───────────────▲───────────────────────────────▲──────────────────────┘
                │ spawn/kill                     │ IPC (window.cuttingEdge)
┌───────────────┴──────────────┐   ┌────────────┴─────────────────────┐
│  Renderer (React + Vite)     │   │  Backend (Python · FastAPI)      │
│  pages/, editor/, api/       │──▶│  app/routers/*  (HTTP + /ws)     │
│  preview = CSS twin of the   │HTTP│  core/engine/*  (the real work)  │
│  compositor                  │◀──│  core/brain/*   (the judge)      │
└──────────────────────────────┘   └───────────────▲──────────────────┘
                                                   │ subprocess
                                   ┌───────────────┴──────────────────┐
                                   │  FFmpeg (+ffprobe)  ·  on-demand │
                                   │  engines: Whisper, Ollama, OCR,  │
                                   │  silero-vad (each optional)      │
                                   └──────────────────────────────────┘
```

* **Electron main** (`frontend/electron/main.ts`) owns the window, spawns the
  backend as a child process (and kills the *whole tree* on quit — Python spawns
  FFmpeg grandchildren that hold files open, §4.64), runs auto-update, and exposes
  a narrow IPC bridge.
* **Renderer** is a Vite/React SPA. `base:'./'` is mandatory (the packaged app
  loads over `file://`, where absolute `/assets` resolves to the drive root).
* **Backend** is a FastAPI app on `127.0.0.1:8742`. Long work never blocks the
  event loop: heavy endpoints are `async def` + `run_in_executor`, and anything
  longer than a request becomes a **task** (`core/tasks.py`) reported over `/ws`.
* **Dev**: Vite proxies `/api` and `/ws` to 8742. Packaged: the renderer talks to
  `http://127.0.0.1:8742` directly (there is no origin under `file://`).

---

## 3. The edit model (`frontend/src/editor/model.ts`)

The single source of truth the editor, the preview, and the exporter all consume.

* `tracks[]` (video `v1`, audio `a1`, text `t1`, …), each `muted`/`hidden`/`locked`.
* `clips[]`: `{ id, trackId, start, duration, offset, sourceDuration, src, props }`.
  `start` = position on the timeline; `offset` = where in the source file the clip
  begins. Clips never overlap on a lane; a transition is an *overlap* between two
  clips.
* `transitions[]`: `{ fromClipId, toClipId, type, duration }`.
* `props.keyframes[]`: the **only five animated channels**: `x, y, scale, rotate,
  volume`. **`opacity` is deliberately absent** — FFmpeg cannot vary alpha over
  time without a per-pixel `geq` pass; in/out fades cover that case (§4.23).
* `props.speed` (0.25–4) for retime; `props.adjust` for colour; text props for
  captions.

**Invariant:** every mutation goes through `commit()` so undo/redo is free; the
assistant and auto-reframe also land as single undoable steps.

---

## 4. The render engine (`core/engine/compose.py`)

Turns the edit model into **one FFmpeg `filter_complex`** and runs it.

* Video: per-clip trim→scale→crop→colour→keyframe-expression chain, then `overlay`
  onto a canvas; transitions are `xfade`; captions are `libass` (correct Persian
  shaping/bidi) or drawn text.
* Keyframes become **piecewise-linear FFmpeg expressions** (`keyframe_expression`),
  with every comma escaped (an unescaped comma silently truncates the graph).
* Audio: `atempo` chains for speed, computed-envelope **ducking** (not
  sidechain — sidechain starves under parallel load, §4.17).
* Encoder chosen by **probe, not by list**: `core/engine/gpu.py` asks each hardware
  encoder to actually encode a frame; NVENC if the driver allows it, else `libx264`.
  Decoding uses `-hwaccel cuda` *before* `-i` (after `-i` FFmpeg ignores it).

**Invariant:** the preview (`frontend/src/editor/preview.ts`) is a **CSS twin** of
this file — the same expressions sampled in JS — so what you see is what exports.
Anything CSS cannot do (unsharp, reverse) is shown as a badge, not faked.

---

## 5. The analysis pipeline

`app/services/pipeline.py` chains, each stage a module in `core/engine/`:

```
ingest → prepare(proxy) → transcribe → analyze(select) → reframe → subtitles → export
```

* `ingest.py` / `proxy.py` — probe; build a 1080p editing proxy for heavy footage
  (preview only; the export is asserted to use the original, `test_proxy.py`).
* `analyze.py` — `detect_silence` (FFmpeg `silencedetect`), `detect_scenes`
  (PySceneDetect **AdaptiveDetector**), `keep_ranges`.
* `audio.py` — waveform envelope + **beat detection** (spectral flux +
  autocorrelation, with the octave trap corrected), all NumPy, no new deps.
* `transcribe.py` — faster-whisper, device ladder `cuda/float16 → auto/int8 →
  cpu/int8`, best *already-downloaded* model.
* `reframe.py` — a camera path as `x` keyframes. Tracker chain: **Haar face →
  motion centroid → centred**, reporting which it used (`tracker`). The motion
  fallback is what makes volleyball/gym/jump-rope reframable.
* `titles.py` — 15 title presets built only from the five keyframe channels.
* `ocr.py` / `vad.py` / `vision.py` — on-demand engines (RapidOCR, silero-vad,
  an Ollama-vision bridge). Each degrades to "not installed", never to a crash.

---

## 6. Style Match (`core/engine/style.py` + `intent.py`)

* `analyse(reference)` → a `Template` of **numbers**: shot lengths + camera move,
  bpm + cuts-on-beat, colour, aspect, speech ratio, hook, transitions, captions
  preference, and the reference's soundtrack (kept beside the `.cetemplate`).
* `_highlights(footage)` → candidate windows **covering the whole file**, scored on
  normalised signals `speech, motion, onset, edge, vision, action, presence`.
  Normalisation is *across candidates* (relative), so a quiet file still ranks.
* `intent.py` — the user's answers (kind/goal/focus/platform/audience/captions/
  restrictions/music/seconds/slowmo) become **weights** over those signals and
  **multipliers** over the judge. Neutral when unanswered; a restriction that is
  checkable is checked, one that is not is reported in `skipped`.
* `build_timeline(template, footage, intent)` → an editor document. A requested
  `seconds` repeats the reference rhythm; `slowmo` halves the speed of the single
  best clip; the hook only ever *extends* an opening.

---

## 7. The brain (`core/brain/*`)

"A model may only win by scoring higher than the rules."

* `objective.py` — one score over measured terms (duration fit, speech integrity,
  on-beat, silence avoided, highlight strength, variety, shot-length match). A term
  that can't be measured is **dropped and weights renormalised**, never faked.
* `planners.py` — a deterministic rule planner + an Ollama planner. The model
  returns **indices into measured moments**, never its own timings.
* `race.py` — runs both, scores both, picks the higher; **ties go to the rules**.
  The scoreboard is shown to the user ("rules 0.71 · ollama 0.83 → used ollama").
* `meaning.py` — scores a moment's transcript on discourse markers (EN+FA), so
  "the part where the point is made" can outrank "the loud part".

---

## 8. On-demand engines & the licence discipline

`core/runtime_packages.py` installs user-fetched packages to
`~/CuttingEdge/runtime/py` (an update can't delete them). The rules (§4.34/§4.38):

* **Nothing ships that nothing imports.** `test_dependencies.py` is the ratchet.
* Every candidate's licence is read from the wheel `METADATA`, not a README.
* Heavy engines (Whisper models, CUDA libs, RapidOCR, silero-vad) are fetched on
  demand and measured before being kept. GPL/AGPL/no-licence are refused.

---

## 9. Packaging, update, CI

* `before-pack.js` converts the dev venv to an **embeddable CPython 3.11** and
  bundles FFmpeg; NSIS builds `Cutting-Edge-Setup-x.y.z.exe`.
* **Differential update**: `electron-updater` downloads only changed LZMA blocks
  (~16 MB) against the previous installer's blockmap. **Never delete old releases**
  — the patch needs the old blockmap.
* CI (`ce-app/ci/ce-workflow.yml`, mirrored at `.github/workflows/ce.yml`): on push
  to `ce-app/**`, `decide` builds **only if the version is new**, then builds,
  smoke-tests the packaged app, and publishes. Bumping
  `frontend/package.json` version + push is the whole release process.

---

## 10. Testing philosophy

* **Known-answer fixtures**: every detector is tested on media built to a recipe
  (cuts at known times, a 120 BPM click track, a moving block, a sweeping white
  box) so the right answer is known in advance.
* **Measurement over counts**: a test that only counts clips passes while the edit
  is the same half-second twenty times; these assert the clips *differ*.
* **Browser suites** (`npm run test:ui`, `test:playback`) drive a real Chromium:
  routes render without overlap, playback advances, every effect is *visible* in
  the preview, the console is clean.
* **The suite is the contract**: 305 tests; the GPU guard proves the app works with
  *no* card; the version/attribution tests prove labels match what ships.

---

## 11. Directory map

```
ce-app/
  backend/
    app/            config · main · routers/* (HTTP+ws) · services/pipeline
    core/engine/    analyze audio compose export gpu ingest intent ocr proxy
                    reframe sounds style subtitles titles transcribe vad vision
                    attribution cancellation
    core/brain/     objective planners race meaning
    core/           runtime_packages tasks assistant/
    tests/          one file per behaviour, known-answer fixtures
  frontend/
    electron/       main preload updater        (Node: spawn backend, IPC, update)
    src/pages/      Home Studio StyleMatch Settings Doctor Attribution Uploads …
    src/editor/     model preview Timeline EditorToolbar PreviewMonitor
                    AssistantButton applyPlan ProjectAutosave
    src/api/        one thin client per backend router
    src/i18n/       en + fa, RTL flip
  scripts/          dev-setup.sh sandbox-test-env.sh smoke-test.ps1 …
  ci/ce-workflow.yml
```

---

## 12. Rebuild-from-zero checklist

1. `bash ce-app/scripts/dev-setup.sh` (venv + FFmpeg + frontend; Electron optional).
2. Backend: `CE_FFMPEG_DIR=… .venv/bin/python ce-app/backend/run_backend.py`.
3. Frontend: `cd ce-app/frontend && npm run dev`.
4. Verify: `pytest` (backend), `npm run verify`, `npm run test:ui`, `test:playback`.
5. Respect the invariants: five keyframe channels; preview is the compositor's
   twin; long work is a task; on-demand engines degrade; nothing ships unimported;
   labels (version/attribution) are generated, not written.

If a future change breaks one of those, the corresponding test is the thing that
will tell you — trust it over your intuition.
