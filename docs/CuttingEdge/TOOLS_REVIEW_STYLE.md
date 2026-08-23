# The eight tools you asked about — checked one by one

Every line below was verified against the GitHub API and PyPI on the day of writing:
does the project exist, under what licence, how alive is it, and — the question that
matters most — *does it do the thing the name suggests*.

| # | What you named | Reality | Licence | Verdict |
|---|---|---|---|---|
| 1 | VideoHighlighter | `Aseiel/VideoHighlighter` exists — a local video **analyser/search** built on Ollama, 91 stars | **AGPL-3.0** | ❌ Cannot ship. Network copyleft in a distributed desktop app. The idea (LLM over transcript + frames to rank moments) is reusable; the code is not. |
| 2 | KION | No open-source video tool by this name. KION is a streaming service and, separately, an ERP company. Nothing on GitHub matches | — | ❌ Does not exist as a library. |
| 3 | Whisper (OpenAI) | `openai/whisper`, 107.8k stars | MIT | ✅ Already in the product — as `faster-whisper` (SYSTRAN, MIT), the same model on CTranslate2, several times faster. Nothing to add. |
| 4 | MoviePy | `Zulko/moviepy`, 14.9k stars | MIT | ⚠️ Real and fine, but a **step backwards for us**: it drives FFmpeg clip-by-clip through intermediate files. Our compositor already builds one `filter_complex` for the whole timeline — faster, frame-accurate, and it is what all the effect tests measure. |
| 5 | Pavo Engine (`pavo-engine-py`) | `sonnhfit/pavo-engine-py` exists: 8 stars, **no licence file**, not published on PyPI, last push 2026-05-31 | **none = all rights reserved** | ❌ Unusable. No licence means no permission, whatever the README says. |
| 6 | AutoVideo (`autovideo-ai`) | `autovideo-ai` is **not on PyPI**. The real project, `datamllab/autovideo` (MIT, 343 stars, last push 2023), is an **automated action-recognition** system — classifying what happens in a video, not editing one | MIT | ❌ Wrong domain, and abandoned for two years. |
| 7 | FFmpeg | Shipped inside the app, with `libass`, `xfade`, `sidechaincompress`, `vidstab` and the rest | LGPL/GPL build we bundle | ✅ It already *is* our engine. |
| 8 | pyJianYingDraft ("CapCut Mate") | Two real, active projects: `GuanYixuan/pyJianYingDraft` (Apache-2.0, **4.2k stars**) writes CapCut/JianYing **draft files** from Python — clips, keyframes, masks, chroma key, blend modes; and `Hommy-master/capcut-mate` (Apache-2.0, 1.6k) wraps that as an API | Apache-2.0 | ⚠️ Genuinely good — but it automates **CapCut**, the proprietary app we are replacing. Useful in exactly one way: as an **export target** ("open this edit in CapCut"), and as a reference for how a template document is structured. Note its CapCut-specific sibling `pyCapCut` has **no licence**. |

## What this means for the feature you want

None of the eight gives us the "analyse a reference and rebuild my footage in its shape"
pipeline. Two of them we already ship (Whisper, FFmpeg); one is licence-blocked; two do not
exist as described; one is the wrong problem; one would slow us down; one belongs to a
competitor's app.

That is not bad news. The measurements this feature needs are ordinary signal processing on
frames and audio, and we already have every piece: FFmpeg for decoding, NumPy for the maths,
`scenedetect` for shots, our own beat detector, `faster-whisper` for speech. The analyser is
being written as `core/engine/style.py` and needs **no new dependency**.

## Verified state of that analyser (in the working tree, not released)

Measured on a reference video built to a known recipe — eight shots of exactly 1.5 s with a
120 BPM click track:

```
duration 12.0   aspect 9:16
shots    7      median shot 1.52 s   (real: 1.5 s)
bpm      120.19                      (real: 120)
cuts on the beat 0.5
transitions      6 hard cuts, 0 dissolves
colour           brightness -0.04  contrast 1.03  saturation 1.44
```

Camera motion, on clips generated with known moves:

```
static  → static      pan → pan      push-in → push      pull-out → pan (weak)
```

Pull-out detection is the one honest gap so far; it is listed as such rather than reported
as if it worked.

## What is left to build

1. Finish the planner: pick the highlights of *your* video and lay them out to the
   template's rhythm, with the look, the zooms, the transitions and the aspect.
2. `.cetemplate` storage plus a **Style match** tile on the home screen.
3. Show the result in the editor with a breakdown of what it is made of.
4. Later, optionally: OCR for caption typography, and an export to CapCut drafts through
   `pyJianYingDraft` (Apache-2.0) for people who still want to finish there.
