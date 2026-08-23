# Audit of an external code review (0.5.3)

An outside review of `Style Match` and the engine arrived with ten suggestions.
This file checks every one of them **against the code in this repository and
against the package registries**, and records the verdict, so the same argument
does not have to be had twice.

Method: read the actual function, or query `pypi.org/pypi/<name>/json`, or unpack
the wheel. Nothing here is taken on trust.

| # | Suggestion | Verdict | Why |
|---|---|---|---|
| 1 | Replace our beat detector with `librosa` | **Reject for now** | Licence claim is right (PyPI `librosa` 1.0.0 = ISC). Cost is wrong: the Windows dependency closure is ≈ 94 MB of wheels (`llvmlite` 43.0 MB, `scipy` 37.4 MB, `scikit-learn` 9.0 MB, `numba` 2.8 MB, `soundfile` 1.0 MB, rest < 1 MB), unpacked far more, while the current task is to take the installer from 480 MB to ~120 MB. `librosa` 1.0.0 also requires `numpy>=2.1.0`; we pin `numpy==1.26.4` because `mediapipe` 0.10.14 and `opencv-python` 4.10 are built against NumPy 1.x. And it does not solve the stated problem: `librosa.beat.beat_track` carries its own octave ambiguity (it takes a `start_bpm` prior, default 120). Our detector already measures 120.19 on a synthetic 120 BPM reference. |
| 2 | `AdaptiveDetector` instead of `ContentDetector` | **Accept as an experiment** | Correct: `AdaptiveDetector` (scenedetect 0.6.4) is a two-pass rolling average over `ContentDetector` scores, explicitly for camera movement. Cheap to try, no new dependency. Must be scored against the known-answer fixtures before it replaces the default. |
| 2b | `ThresholdDetector` to find dissolves | **Reject — factually wrong** | Unpacked the wheel: `ThresholdDetector` triggers on **average pixel intensity**, i.e. fade to/from **black**. It cannot see a cross dissolve between two shots. Our difference-profile heuristic in `style.analyse` can. |
| 2c | "Bad transition typing corrupts `cuts_on_beat`" | **Wrong** | `cuts_on_beat` is computed from cut timestamps against beat times (`style.py`, `analyse`). It never reads `transitions`. The reviewer did not read that function. |
| 3 | MediaPipe FaceLandmarker for reframing | **Already agreed, and cheaper than stated** | `mediapipe==0.10.14` is *already* in `backend/requirements.txt` and already shipping — unused. The missing piece is code (camera path + smoothing), not a dependency. Apache-2.0 confirmed on PyPI. |
| 4 | Keyword scoring on the transcript for highlights | **Accept, small** | No new dependency, `faster-whisper` is already there. But a flat keyword list is weak and language-specific; the useful version is discourse markers + speech-rate + energy as a *score*, wired into the same objective function described in `BRAIN_DESIGN.md`, not a separate ad-hoc rule. |
| 5 | Histogram matching instead of four grade numbers | **Accept in principle, their code cannot ship** | The proposed function edits NumPy frames. Nothing in our render path touches pixels in Python — colour is applied by FFmpeg (`eq`, `curves`, `colorbalance`). The shippable form: store per-channel CDFs in the template, fit a monotone curve, emit `curves=r=…:g=…:b=…` (or a `.cube` for `lut3d`, which our build has). Also needs a strength control: matching the histogram of a night reference onto daylight footage is a known way to make footage look broken. |
| 6 | `estimateAffinePartial2D` + ORB for push/pull | **Accept as an experiment, not as a certainty** | The claim "this will no longer be wrong" is unverified. ORB with 500 features on our 96×96 grayscale frames is feature-starved, and `cv2` is optional by design (`style.py` runs without it). Worth an A/B at 256 px against the synthetic push/pull/pan/static clips, kept only if it wins, with the NumPy path preserved as the fallback. |
| 7 | Builder pattern for `compose.py` | **Defer** | Fair as architecture, but it is a rewrite of 928 lines that 111 tests depend on, buys the user nothing visible, and the comma-escaping it promises to centralise is already centralised. Revisit after 1.0. |
| 8 | Progress for `Style Match` | **Accept — and the reviewer missed the real bug** | The gap is real: `StyleMatch.tsx` only has a `busy` boolean. Their SSE code cannot work: `EventSource` is GET-only and they declared `@router.post`, and `run_stage` does not exist. We already have a WebSocket at `/ws` with a job event manager — analysis should become a job and stream stage events over it. **The real bug next door:** `api/client.ts` sets `timeout: 30000` and `POST /api/style/analyze` is one synchronous request, so a long reference video reproduces exactly the `timeout of 30000ms exceeded` the user already reported in 0.5.3. |
| 9 | Installer size table | **Directionally right, numbers invented** | The per-component figures in the review are guesses; the real ones must come from the electron-builder output. The three levers that are certainly real: `opencv-python` → `opencv-python-headless`, models fetched on first launch instead of bundled, and dropping shipped-but-unused packages (`pexels-api`, `edge-tts`, `yt-dlp`, `google-generativeai`/`anthropic`/`openai` if the AI path is Ollama-first). |
| 10 | Priority order | **Re-ordered** | See below. |

## Order we will actually work in

1. `Style Match` progress + no 30 s wall (item 8) — a user-visible failure, not a refinement.
2. `AdaptiveDetector` A/B on the known-answer fixtures (item 2).
3. Colour curve transfer with a strength control (item 5).
4. Affine push/pull experiment (item 6).
5. MediaPipe reframing (item 3) — the dependency is already paid for.
6. Highlight scoring inside the brain's objective function (item 4).
7. Installer slimming with measured numbers (item 9).
8. `compose.py` refactor (item 7) — after 1.0.
9. `librosa` (item 1) — only if the beat detector is ever shown to fail on real music, and only if the NumPy 2 migration happens for other reasons.

Every one of these ships with a known-answer test, the same way `test_style.py` works.
