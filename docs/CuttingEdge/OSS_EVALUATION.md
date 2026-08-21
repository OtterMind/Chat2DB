# Evaluation of the proposed open-source projects

Checked against the GitHub API on 2026-08-21. The question was: which of these can we
actually reuse to move faster? Verdicts below are based on existence, licence,
activity and how well each fits our architecture (FastAPI backend + FFmpeg + a pure
edit model in the renderer).

---

## 1. The two “debugging tools” do not exist

| Proposed | Result |
|---|---|
| `josephgrand/VantaCut` | **HTTP 404 — no such repository** |
| `yourkinodev/kinocut` | **HTTP 404 — no such repository** |

Both URLs return “Not Found” from the GitHub API. They are very likely hallucinated
names; there is nothing to evaluate. (If you have the real project names, send them
and I will re-check.)

## 2. The five that do exist

| Repo | Stars | Licence | State | Verdict |
|---|---|---|---|---|
| `francozanardi/movielite` | 68 | **MIT** | active (Jul 2026) | ✅ **worth using**, narrowly |
| `sonnhfit/pavo-engine-py` | 8 | **none** | May 2026 | 📖 ideas only — copying is not permitted |
| `mbekana/autovideo-ai` | 2 | MIT | Jul 2025, 8 KB | ❌ too small to matter |
| `programmersd21/yarn` | 6 | **none** | Aug 2025, 21 KB | 📖 ideas only |
| `KinanCodeaz/opencut` | 1 | **none** | Aug 2025, PyQt5 | ❌ wrong stack (Qt desktop, not Electron) |

**A licence note that matters legally:** a public GitHub repo with *no licence file*
is “all rights reserved”. We may read it, we may not copy its code into an
open-source product. That rules out `pavo`, `yarn` and `opencut` as code sources —
only as inspiration.

### MovieLite — the one real win

MIT, actively maintained, and it solves a problem we will hit soon: per-frame
compositing in Python is painfully slow. Its own benchmark against MoviePy 2.2.1:

| Task | MovieLite | MoviePy | Speedup |
|---|---|---|---|
| Text overlay | 7.82 s | 35.35 s | 4.5× |
| Video overlay | 18.22 s | 75.47 s | 3.1× |
| Alpha overlay | 10.75 s | 42.11 s | 3.9× |
| Complex mix | 38.07 s | 175.31 s | 4.6× |

It gets there with Numba JIT on the blending loops plus multiprocessing.

**But** — and this is the important part — we do **not** currently use MoviePy at
all, and raw FFmpeg is still faster than any Python frame loop for cuts, scaling,
concatenation and encoding. So MovieLite should be adopted **only** where FFmpeg is
awkward:

- animated / kinetic captions with per-word timing beyond what libass can express
- B-roll picture-in-picture with eased motion paths
- generated intros/outros and animated titles

Cost: `numba` + `llvmlite` add roughly 35–45 MB to the installer. Acceptable, but it
should be a lazily imported optional dependency so the app still starts without it.

### Pavo Engine — validates our design, do not copy

Its structure (`pavolang`, `perception`, `preparation`, `sequancer`) is essentially
“LLM understands the video → emits a JSON edit script → engine renders it”. That is
the same shape as our edit model in `src/editor/model.ts` plus the pipeline stages in
`app/services/pipeline.py`. Useful confirmation that the JSON-EDL approach is right,
and its schema design is worth reading — but with no licence, not a code source.

### yarn / opencut / autovideo-ai

`yarn` is a MoviePy-based PySide editor, `opencut` a PyQt5 tool, `autovideo-ai` an
8 KB script skeleton. Nothing here is faster, more complete or more maintained than
what we already have.

---

## 3. What would actually accelerate us more

Projects with real scale, permissive licences and a direct fit:

| Need | Project | Licence | Why |
|---|---|---|---|
| Timeline/NLE render engine | **MLT Framework** (`mltframework/mlt`, ⭐1.8k) | LGPL-2.1 | the engine behind Shotcut and Kdenlive: multitrack timeline, transitions, filters, already battle-tested. Callable as the `melt` binary, so LGPL stays clean |
| Silence & filler removal | **auto-editor** (`WyattBlue/auto-editor`, ⭐5k) | Unlicense | exactly our “حذف سکوت” feature, public domain, actively developed |
| Voice activity detection | **silero-vad** (⭐10k) | MIT | tiny, fast, better than threshold-based silence detection |
| Music / vocal separation | **demucs** (⭐10.4k) | MIT | stems for ducking and remixing |
| Background removal | **RobustVideoMatting** (⭐9.5k) | **GPL-3.0** ⚠️ | excellent quality, but GPL would force our whole app to GPL — prefer BiRefNet/rembg or run it as a separate optional process |
| Upscaling / denoise | **Real-ESRGAN** (⭐36k) | BSD-3 | ncnn builds run on any GPU, ship as an optional model |
| Subtitle rendering | **libass** (⭐1.1k) | ISC | already inside our bundled FFmpeg — animated ASS captions need no new dependency |

## 4. Recommended plan

1. **Keep FFmpeg as the core renderer.** Nothing in the list beats it for cuts,
   scaling and encoding, and it gives us NVENC hardware encoding for free.
2. **Add MovieLite as an optional accelerator** for per-frame compositing effects,
   imported lazily so it never blocks startup.
3. **Adopt auto-editor + silero-vad** for the silence-removal feature instead of
   writing our own detector — it is the single biggest feature win per hour spent.
4. **Evaluate MLT** before hand-rolling the render backend for the timeline. If it
   fits, it removes months of work; if it is too heavy to bundle, we fall back to
   generating `filter_complex` graphs from our edit model.
5. **Avoid GPL dependencies inside the app process** so the project can stay
   permissively licensed.


---

# Second review — the extended list (2026-08-21)

Every entry checked against the GitHub API again. Two more do not exist:

| Proposed | Result |
|---|---|
| `pixclip/pixclip` | **404 — no such repository** |
| `ffmpeg-toolkit/ffmpeg_toolkit` | **404 — no such repository** |

## Verdicts

| Project | Stars | Licence | Verdict |
|---|---|---|---|
| `WyattBlue/auto-editor` | 5.0k | Unlicense | ✅ **idea adopted, implemented natively** (see below) |
| `Breakthrough/PySceneDetect` | 5.1k | BSD-3 | ✅ **now used** for shot detection (already a pinned dependency) |
| `SYSTRAN/faster-whisper` | 25k | MIT | ✅ already bundled — keep |
| `rastikerdar/vazirmatn` | 3.2k | OFL-1.1 | ✅ already bundled offline via `@fontsource` |
| `francozanardi/movielite` | 68 | MIT | ⏳ keep for per-frame effects only; FFmpeg stays the core |
| `m-bain/whisperX` | 23.7k | BSD-2 | ⏳ better word alignment + diarization, but drags in torch (~2 GB). Offer as an optional download, never in the base installer |
| `smacke/ffsubsync` | 7.8k | MIT | ⏳ worth adopting when we support importing external subtitles |
| `alibaba-damo-academy/FunASR` / `FunClip` | 20k / 6.2k | MIT | ➖ excellent, but Mandarin-first and heavy; no advantage for Persian/English over what we ship |
| `Zulko/moviepy` | 14.9k | MIT | ❌ slower than our FFmpeg graph; MovieLite already covers the same niche faster |
| `kkroening/ffmpeg-python` | 11k | Apache-2 | ❌ unmaintained since 2024 and only builds argument strings — we already generate `filter_complex` directly |
| `jiaaro/pydub` | 9.8k | MIT | ❌ wraps FFmpeg for things we do in the graph, and depends on `audioop`, removed in Python 3.13 |
| `opencv/opencv-python`, `python-pillow/Pillow` | — | MIT / HPND | ✅ already dependencies |
| `OpenShot/openshot-qt` | 6.2k | **GPL-3** | ❌ copyleft — reading it is fine, linking it would force our whole app to GPL. MLT (LGPL) remains the safe engine option |
| `programmersd21/yarn`, `KinanCodeaz/opencut`, `sonnhfit/pavo-engine-py` | tiny | **no licence** | ➖ inspiration only; no licence means no reuse |
| `mbekana/autovideo-ai` | 2 | MIT | ❌ 8 KB skeleton |
| Helsinki-NLP (Hugging Face) | — | mostly Apache-2/MIT per model | ⏳ `opus-mt-en-fa` / `opus-mt-fa-en` are the right choice for translation, downloaded on demand into `~/CuttingEdge/models` |

## What was actually built from this review

**Silence removal** — the core value of `auto-editor`, implemented in
`core/engine/analyze.py` on FFmpeg's own `silencedetect` filter, so it needs **zero
new dependencies** and runs on the binary we already ship. It also keeps 50 ms of
room tone on each side so cuts do not clip the first consonant of a word.

**Scene detection** — PySceneDetect's `ContentDetector`, with an FFmpeg-only
fallback (`select='gt(scene,0.4)'`) so the feature survives a trimmed install.

Both are exposed as `POST /api/analyze/silence` and `POST /api/analyze/scenes`, and
wired to two toolbar buttons in the editor that rewrite the edit model — rippling
the timeline closed after cuts, fully undoable with Ctrl+Z.

Measured on synthetic material with known ground truth:

| Test | Expected | Detected |
|---|---|---|
| 8.7 s audio, gaps at 2.0–3.5 s and 5.5–6.7 s | 2 gaps | `2.05–3.45`, `5.55–6.65` (padding applied) — 0.05 s |
| 9 s video, cuts at 3 s and 6 s | 2 cuts | `[3.0, 6.0]` — 0.05 s |
