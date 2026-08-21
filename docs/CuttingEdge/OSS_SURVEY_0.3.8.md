# Open-source survey — 0.3.8 (GitHub + Hugging Face)

Every entry below was checked against the GitHub API (`repos/<owner>/<name>`) or the
Hugging Face API on the day of writing: existence, SPDX licence, star count and last
push. Nothing here is from memory — the earlier survey found thirteen repositories
that simply do not exist, so the rule is: **if the API did not answer, it is not on
this list.**

Two questions decide everything: *does it exist and is it maintained*, and *does its
licence let a free desktop app bundle it*. A permissive repo with restricted **weights**
is still a trap, and that is the most common one in this field.

---

## 1. Adopt now — clear win, clean licence

| Project | Licence | Stars | Last push | What it buys us |
|---|---|---|---|---|
| `google-ai-edge/mediapipe` | Apache-2.0 | 36.7k | 2026-08-21 | Face landmarks + pose for **real auto-reframe**. Wheels exist for Windows (`mediapipe-1.0.1-py3-none-win_amd64.whl`), Python ≤ 3.12 only. |
| `katspaugh/wavesurfer.js` | BSD-3-Clause | 10.4k | 2026-08-21 | **Waveforms on the audio lane.** Today the audio track is a blank rectangle; this is the cheapest large improvement in the timeline. |
| `librosa/librosa` | ISC | 8.6k | 2026-08-21 | **Beat detection** for cut-on-the-beat, without a single restricted model file. |
| `Rikorose/DeepFilterNet` | MIT **or** Apache-2.0 (dual) | 4.6k | 2024-10-17 | Speech denoise far better than `afftdn`; ships a standalone binary, so it can run as a plugin process. |
| `adefossez/demucs` | MIT | 3.1k | 2026-07-11 | Vocal/music separation → real **auto-ducking** and "remove the music" |
| `tkarabela/pysubs2` | MIT | 437 | 2026-08-16 | Subtitle parsing/writing (SRT/ASS/VTT) — replaces hand-rolled import/export. |
| `AcademySoftwareFoundation/OpenTimelineIO` | Apache-2.0 | 2.0k | 2026-08-07 | `.otio` interchange: open a CE project in Resolve/Premiere and back. A serious differentiator for a free tool. |
| `xinntao/Real-ESRGAN-ncnn-vulkan` | MIT | 2.2k | 2024-05-10 | Upscaling as a **prebuilt binary**, GPU-agnostic (Vulkan), no PyTorch. |
| `hzwer/Practical-RIFE` | MIT | 1.0k | 2025-11-20 | Frame interpolation for real slow motion, better than `minterpolate`. |
| `googleapis/google-api-python-client` | Apache-2.0 | 8.9k | 2026-08-20 | The official path for **YouTube upload**. |
| `yt-dlp/yt-dlp` | Unlicense | 186k | 2026-08-20 | Ingest from a link (already the plan for Auto Clip). |

## 2. Adopt with care — permissive code, watch the details

| Project | Licence | Catch |
|---|---|---|
| `danielgatis/rembg` | MIT | Code is MIT; the U²-Net weights it downloads have their own terms. Check the model actually pulled at runtime. |
| `ZhengPeng7/BiRefNet` | MIT | Best-in-class matting quality, MIT including weights — but it is a PyTorch model, so it belongs in the optional plugin channel, not the installer. |
| `nadermx/backgroundremover` | MIT | Wraps U²-Net for video; same weight question as rembg. |
| `hexgrad/Kokoro-82M` (HF) | Apache-2.0 (model card + tag) | 82 M params, 12.8 M downloads, Apache **weights** — rare and excellent. English-first; Persian is not among its voices. |
| `rhasspy/piper` (repo) | MIT | **The wheel is not MIT.** `piper-tts 1.7.0` on PyPI declares `GPL-3.0-or-later` (it links espeak-ng). Voices include **five Persian ones** (`amir`, `ganji`, `ganji_adabi`, `gyro`, `reza_ibrahim`). Use it as a separate process, never linked into our code, and show the licence before download. |
| `rhasspy/piper-voices` (HF) | `mit` on the repo card | Individual `MODEL_CARD` files say "License: see URL" and point at the dataset author — the Persian ones must be checked one by one before shipping. |
| `idiap/coqui-ai-TTS` | MPL-2.0 | The maintained Coqui fork. Code is fine; **XTTS weights are CPML, non-commercial** — that is the trap that killed the original plan. |
| `m-bain/whisperX` | BSD-2-Clause | Word-level timing and diarization, but diarization needs gated `pyannote` models and a token. |
| `artbyjazi/autoclip` | MIT | Same stack as ours (FastAPI + faster-whisper + scenedetect + MediaPipe) and the only reframe implementation found that is MIT **and** AGPL-free. Its `pipeline/reframe/{faces,speaker,tracker,smoothing,croppath}.py` split is a good model to study for our own reframe. |
| `KazKozDev/auto-vertical-reframe`, `fralapo/clippyme`, `NaufalRizqullah/opensource-clipping` | MIT (repo) | All three depend on **Ultralytics YOLO, which is AGPL-3.0**. The repo licence is not the licence you end up shipping. |
| `subzeroid/instagrapi` | MIT | Works, but it is a private-API client: account-ban risk is on the user, so it can never be the default path. |

## 3. Rejected — licence or weights make them unusable for us

| Project | Licence | Why not |
|---|---|---|
| `ultralytics/ultralytics` (YOLOv8/11/26) | **AGPL-3.0** | Network-copyleft in a desktop app we distribute. Use MediaPipe instead. |
| `upscayl/upscayl` | AGPL-3.0 | Same reason; its underlying ncnn binaries are MIT, so take those directly. |
| `CPJKU/madmom` | BSD code, **models CC BY-NC-SA** | The beat-tracking models are non-commercial. librosa does the job licence-free. |
| `facebookresearch/co-tracker` | CC-BY-**NC** 4.0 | Point tracking we cannot ship. |
| `bbc/audiowaveform` | GPL-3.0 | We can compute peaks with FFmpeg ourselves. |
| `bbc/peaks.js` | LGPL-3.0 | wavesurfer.js (BSD) is the cleaner choice. |
| `LibreTranslate/LibreTranslate` | AGPL-3.0 | Use `argosopentech/argos-translate` (MIT), which is the engine underneath. |
| `mifi/lossless-cut` | GPL-2.0 | Good ideas, cannot copy code. |
| `remotion-dev/remotion` | custom (paid for companies) | Not free software for our purpose. |
| `sczhou/ProPainter` | S-Lab, non-commercial | Object removal stays out until an Apache/MIT model exists. |
| `Thomcles/Chatterbox-TTS-Persian-Farsi` (HF) | CC-BY-NC-4.0 | The best-sounding Persian clone voice found — and non-commercial. |
| `ZHKKKe/MODNet` | Apache-2.0 code | Pretrained weights are CC BY-NC; only the code is reusable. |
| `gauravzazz/smart-reframe` | **no licence file** | No licence = all rights reserved. Reading it is fine, using it is not. |

## 4. What this changes in the plan

1. **Auto-reframe** is unblocked with MediaPipe (Apache-2.0) + our existing `scenedetect`,
   and `artbyjazi/autoclip` is a working reference implementation under MIT. No YOLO,
   no AGPL.
2. **Audio waveforms** (wavesurfer.js) jump the queue: small, BSD, and the timeline
   looks unfinished without them.
3. **Beat detection** with librosa is a weekend feature, not a research project.
4. **Voice-over** should be two options, both as *plugin processes*: Kokoro (Apache
   weights, English) and Piper (GPL wheel, but the only good local **Persian** voices).
   The licence must be shown before the download, exactly as the plugin channel design
   already requires.
5. **`.otio` import/export** is a cheap credibility feature no free editor in this class has.
6. Nothing here changes the installer: every heavy item belongs to the optional plugin
   channel, which is also what keeps the base download small.

## 5. Method, so this can be repeated

```
gh api repos/<owner>/<name> --jq '.full_name, .license.spdx_id, .stargazers_count, .pushed_at'
gh api repos/<owner>/<name>/license --jq '.content' | base64 -d | head -40
curl -s https://huggingface.co/api/models/<id>            # tags contain license:<spdx>
curl -s https://pypi.org/pypi/<package>/json              # the shipped wheel's licence
```

The PyPI check is not optional: `rhasspy/piper` is MIT on GitHub and GPL-3 on PyPI.
Where code and weights are licensed separately, both must be recorded.
