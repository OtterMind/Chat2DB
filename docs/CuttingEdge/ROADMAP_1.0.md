# Road to 1.0 — the plan as of 0.5.3

Written after auditing an outside review (`REVIEW_AUDIT_0.5.3.md`) and after
**measuring** where the installer's weight actually is. Everything with a number
in it was measured on the day of writing, not estimated.

---

## 1. New data this plan is built on

### 1.1 The installer's Python side, measured

Windows wheel sizes (compressed) for what `backend/requirements.txt` pins today:

| Package | Windows wheel | Imported anywhere in `app/` or `core/`? |
|---|---:|---|
| `ctranslate2` 4.3.1 (engine under faster-whisper) | **174.9 MB** | yes, via `faster-whisper` |
| `mediapipe` 0.10.14 | **50.8 MB** | **no — zero imports** |
| `opencv-python` 4.10.0.84 | 38.8 MB | yes (1 optional import in `style.py`) |
| `av` 12.3.0 | 26.0 MB | via `faster-whisper` |
| `numpy` 1.26.4 | 15.8 MB | yes, everywhere |
| `google-api-python-client` 2.139.0 | **12.1 MB** | **no** |
| `onnxruntime` 1.18.1 | 5.6 MB | via `faster-whisper` (VAD) |
| `yt-dlp` 2024.8.6 | 3.1 MB | 1 place |
| `Pillow` 10.4.0 | **2.6 MB** | **no** |
| `tokenizers`, `faster-whisper`, `sqlalchemy` | ~6.3 MB | yes |
| `openai` + `anthropic` + `google-generativeai` + `ollama` SDKs | ~1.5 MB | **no — every provider is called with plain `requests`** |
| `edge-tts`, `pexels-api` | ~0.1 MB | **no** |
| everything else | ~1.5 MB | yes |
| **Total** | **≈ 339 MB of wheels** | |

Two conclusions that change the order of work:

* **The speech stack is the installer.** `ctranslate2` + `av` + `onnxruntime` +
  `tokenizers` + `faster-whisper` ≈ **211 MB**, more than half of everything we
  ship on the Python side — carried by every user, including those who never use
  captions. The Windows `ctranslate2` wheel carries GPU support that the user's
  own machine could not even load (`cublas64_12.dll is not found`, 0.5.3).
* **≈ 70 MB is dead weight today**: `mediapipe`, `google-api-python-client`,
  `Pillow`, the four AI SDKs, `edge-tts`, `pexels-api` — shipped, never imported.

### 1.2 The audit of the outside review

Full table in `REVIEW_AUDIT_0.5.3.md`. What it changed here:

* A real user-facing bug was found in passing: `api/client.ts` has
  `timeout: 30000` and `POST /api/style/analyze` is one synchronous request, so a
  long reference video reproduces the same `timeout of 30000ms exceeded` the user
  already reported. That is now the next thing we ship.
* `librosa` is rejected for now: ISC licence is fine, but its Windows closure is
  ≈ 94 MB (`llvmlite` 43.0, `scipy` 37.4, `scikit-learn` 9.0, `numba` 2.8) and
  `librosa` 1.0.0 needs `numpy>=2.1` against our `numpy==1.26.4` pin. It also does
  not remove the octave ambiguity we already correct for.
* `AdaptiveDetector`, affine push/pull and colour-curve transfer are accepted as
  **experiments scored on the known-answer fixtures**, not as certainties.
* `ThresholdDetector` for dissolves and "bad transition typing breaks
  `cuts_on_beat`" were checked against the wheel and the source and are wrong.

### 1.3 Where the product actually stands

Everything in `STATE.md` §1 works and is measured. The honest gaps: face tracking
is still a centre crop, the brain is designed but not built, Style Match chooses
highlights by energy and speech rather than meaning, and captions inherit our own
typography rather than the reference's.

---

## 2. The road, release by release

Each release states **how we will know it worked** — a measurement, not an
opinion — because two bugs that reached the user compiled cleanly.

### 0.6.0 — Nothing waits in silence ✅ shipped
*Fixed a failure the user could hit today.*

* `POST /api/style/analyze` becomes a job: stages (`shots`, `beats`, `colour`,
  `motion`, `transitions`) stream over the existing `/ws` channel.
* `StyleMatch.tsx` shows the stage, the elapsed time and a **Cancel** button
  instead of one boolean `busy`.
* Audit every long endpoint against the 30 s client budget — analyse, apply,
  transcribe, render, proxy — and give each an explicit budget that matches what
  it really does. The AI calls got theirs in 0.5.3; the rest never did.
**Measured, not asserted:**

| Question | Before | After |
|---|---|---|
| How long does the request stay open? | the whole analysis | **1–4 ms** |
| A ten-minute reference | 35.5 s — past the 30 s client budget | same work, no timeout |
| What can the screen say? | "busy" | 7–8 named stages with a progress bar and a clock |
| Stop | did not exist | task ends **cancelled in 0.2 s**, FFmpeg child killed |
| Socket drops | progress freezes | `GET /api/tasks/{id}` poll keeps the bar honest |

Also in this release: `/api/captions/transcribe`, `/api/analyze/silence`,
`/api/analyze/scenes` and `/api/analyze` were synchronous `def` endpoints running
FFmpeg and Whisper **on the event loop** — the same loop that delivers progress
and answers `/api/health`. They are `async def` + `run_in_executor` now, with
their own client budgets (20 min for transcription, 10 min for scans).

Tests added: `backend/tests/test_tasks.py` (9) and eight browser checks in
`playback-test.mjs` — 120 backend tests and 100 browser checks all green.

### 0.6.1 — The installer stops carrying what it never uses  ✅ **part one shipped**
*Measured on the published installer: **458 MB → 305 MB** (−153 MB, −33 %).*
*Dependency closure: 378.3 MB → 137.9 MB of wheels, 108 → 50 packages.*

* **Done.** Deleted every never-imported package. The closure was re-measured
  with `uv pip compile --python-platform windows` before and after: `mediapipe`
  alone took `jaxlib` (61.2 MB), `opencv-contrib-python` (46.2 MB), `scipy`
  (36.6 MB) and `matplotlib` (9.3 MB) with it; `sqlalchemy` turned out to be
  dead too (the database is standard-library `sqlite3`). `mediapipe` returns in
  0.8.0 as an on-demand engine, not as ballast. `tests/test_dependencies.py`
  fails if any of it comes back.
* **Done.** `opencv-python` → `opencv-python-headless`: same size, no `libGL`
  trap. This one was hiding a false test failure — without an importable `cv2`
  the log-polar zoom measurement silently disappears and
  `test_camera_motion_is_recognised[pull]` fails for an environment reason.
* ~~Move the speech stack behind the AI runtime card.~~ **Cancelled — and the
  reason matters.** The owner's instruction is explicit: download size is not
  worth a worse product. Making captions depend on a first-use download means a
  new user's first automatic edit silently comes back without subtitles, or
  waits on a download they did not ask for. `ctranslate2` + `av` +
  `onnxruntime` + `tokenizers` + `faster-whisper` stay in the installer (≈ 62 MB
  of the 137.9 MB) so that transcription works the moment the app is installed,
  offline. Only *bigger* models remain on demand, which is already how the AI
  runtime card behaves. The same logic applies to `mediapipe` in 0.8.0: when
  face tracking is real, ship it.
* **Proof:** the wheel closure was measured before and after (378.3 → 137.9 MB);
  the whole backend suite (122 tests) then ran against a **fresh virtualenv built
  from the pruned `requirements.txt` alone**, and the UI audit and the 100
  browser checks ran against a backend started from that same virtualenv. What
  is still unmeasured is the published `.exe`: the user reports its size and the
  size of the next in-app update, which must stay < 50 MB.

### 0.6.2 — Style Match gets measured, not adjusted
*Three experiments, a scoreboard, and only the winners ship.*

* `AdaptiveDetector` vs `ContentDetector` on the synthetic fixtures: shot-boundary
  error in frames, on clips built with known cut points.
* Affine push/pull (`estimateAffinePartial2D` at 256 px) vs the current log-polar
  path: confusion matrix over `static / pan / push / pull / handheld` clips built
  to a recipe. The NumPy fallback stays for machines without OpenCV.
* Colour transfer as a per-channel curve (`curves=r=…:g=…:b=…`, or a `.cube` for
  `lut3d`) with a strength slider, against the current four grade numbers:
  histogram distance to the reference, measured in the rendered file.
* **Proof:** `docs/CuttingEdge/STYLE_SCOREBOARD.md` — old number, new number, and
  the decision. Anything that does not win is deleted, and the reason is recorded.

### 0.7.0 — The brain, part one
*`BRAIN_DESIGN.md` becomes code.*

* One objective function (duration fit ×3, speech integrity ×3, on-beat ×2,
  silence avoided ×2, highlight strength ×2, variety ×1, shot-length match ×1).
* Rule planner and Ollama planner produce candidate plans; the score picks. The
  rule plan is always a candidate, so a bad model answer can never be worse than
  offline.
* Assistant gets the dry-run preview: what will change, applied as one undoable
  step. Free prompts get validation + preview + undo, never a fake score.
* **Proof:** on a fixture with a known best cut list, the raced plan scores at
  least as high as the rule plan on 20 out of 20 runs, and every plan the LLM
  returns is either valid or rejected — never partially applied.

### 0.7.1 — Highlights that understand what was said
* The transcript enters the score: discourse markers, speech rate, and the shape
  of the answer, instead of loudness alone. This is the item the outside review
  asked for, done inside the objective function rather than as a keyword list.
* **Proof:** on a talking-head fixture with a scripted "the important part is…"
  moment, that moment is inside the chosen highlights in every run.

### 0.8.0 — Face tracking for real
* MediaPipe FaceLandmarker as an on-demand engine (Apache-2.0, 50.8 MB, fetched
  when the feature is first used), a smoothed camera path, and a fallback to the
  centre crop when no face is found.
* **Proof:** on fixtures where the subject's position is known per frame, the
  crop centre stays within a stated pixel error, and the camera path has no jump
  larger than a stated per-frame limit. The `BETA` badge comes off only if both
  pass.

### 0.8.1 — Things to put on the screen
* Template gallery (our own `.cetemplate` files, shipped and shareable), a title
  animation pack built from the keyframe channels we can genuinely export, and a
  sound-effect pack through `freesound-python` (MIT).
* **Proof:** every title in the pack renders identically in the monitor and in the
  export — the CSS twin test we already have, extended per title.

### 0.9.0 — Publishing
* YouTube upload with `google-api-python-client` (Apache-2.0), fetched on demand,
  OAuth in the system browser, resumable uploads, and a clear failure path.
* **Proof:** a dry-run against the API's test surface plus a real upload from the
  user's machine, with the size and time reported.

### 0.9.1 — Audio depth
* Optional DeepFilterNet (MIT/Apache) for denoise as an on-demand engine, measured
  in dB against our current chain; kept only if it wins.
* Music bed library and per-clip sound effects.

### 1.0 — Stabilise and say what it is
* First-run tour, crash reporting, a real user manual in both languages, licence
  and attribution screen (every shipped package listed with its licence), and a
  full pass of the four test suites plus the packaged smoke test on the user's own
  machine.
* **Proof:** a clean install on a machine that has never seen the app, with no
  Ollama and no Whisper, does a complete edit and export without a single console
  error — filmed, not asserted.

---

### 0.6.2 — Ideal, not small  ✅ **shipped**
*A pass over every place where size was traded against quality.*
*Installer 305 MB → **323 MB**: the 18 MB is the bytecode, bought back on purpose.*

* **Bytecode is shipped again.** It had been deleted from the payload so that
  differential patches stayed small. Measured cost: **1.16 s** to start the
  backend with no `.pyc` anywhere against **0.72 s** with bytecode present.
  `compileall --invalidation-mode unchecked-hash` gives both — caches that carry
  the source hash instead of an mtime, so they are byte-identical between builds
  *and* Python uses them.
* **The local build now uses the same full FFmpeg as CI.** `before-pack.js` fell
  back to `ffmpeg-release-essentials`, so an installer built outside CI quietly
  shipped fewer filters.
* **Captions stay in the installer** (see above): no first-use download.
* **Checked and found clean:** export presets (`high` = CRF 18 / `slow`, and the
  default `balanced` = CRF 21), the 720p preview proxy (preview only — the
  export is asserted to use the original in `tests/test_proxy.py`), thumbnail
  and waveform caches, and `compression: "normal"` in electron-builder — that
  last one is a size-versus-patch balance with no effect on what the app does,
  and it stays because this project ships a release most days.

## 3. What is deliberately not on this road

* `librosa` — see §1.2. Revisit only if our detector is shown to fail on real
  music, and only alongside a NumPy 2 migration.
* Rewriting `compose.py` into a builder — after 1.0; it is 928 lines that 111
  tests stand on and the user sees nothing for it.
* AGPL and non-commercial components: `ultralytics`, `upscayl`, `Nuitka`,
  `pedalboard`, `pyvideotrans`, `LibreTranslate`, `madmom` models, `RMBG`,
  and the `piper-tts` wheel (GPL-3 despite an MIT repo). The list with evidence is
  in `OSS_SURVEY_0.3.8.md`.
* Cloud processing of any kind. Everything runs on the user's machine.

---

## 4. The rule this plan is written under

A release is not "done" when it compiles or when the tests pass; it is done when a
number moved in the direction we said it would. Every item above names that
number before the work starts.
