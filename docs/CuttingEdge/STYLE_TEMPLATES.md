# Style templates from a reference video ("Roll"-style)

Question from the user: an Instagram app called **Roll** takes a video, analyses it, and
hands back a *template* for making something similar. Can we do that, and is there
open-source code for it?

Short answer: **most of it, yes — and with permissively licensed pieces we can ship.**
What is genuinely impossible is also listed, because a feature that promises "one click,
same video" and delivers a rough imitation is worse than no feature.

---

## 1. What a "template" actually is

A video that feels like another video shares *measurable* properties. None of these
require copying a single frame of the original:

| Property | How it is measured | Tool |
|---|---|---|
| Shot list and cut rhythm | shot boundaries → durations, average shot length, variance | `scenedetect` (BSD-3, already shipped), `TransNetV2` (MIT) for hard cases |
| Cut-on-beat behaviour | beat grid vs cut times → what fraction of cuts land on a beat | our own detector (`core/engine/audio.py`) |
| Music tempo and energy | BPM, onset density | ours |
| Camera motion per shot | dense optical flow → static / pan / push-in / handheld | OpenCV (Apache-2.0) |
| Framing | subject size and position in frame, aspect ratio | MediaPipe (Apache-2.0) |
| Colour look | palette, contrast, saturation, temperature → nearest of our 10 looks + grade offsets | `colour-science` (BSD-3), NumPy |
| Caption style | sampled frames → OCR: position, size, colour, stroke, words per line | PaddleOCR / EasyOCR / Tesseract (all Apache-2.0) |
| Caption rhythm | words per minute, words per caption, gap between captions | `faster-whisper` (MIT, already shipped) |
| Hook shape | time to first cut, time to first spoken word, length of the opening shot | derived |
| Loudness and ducking | music vs voice levels over time | FFmpeg `astats` + ours |
| Transition types | frame-difference profile across a boundary: hard cut vs dissolve vs whip | NumPy |

The output is a small JSON document — a **recipe**, not media:

```json
{
  "aspect": "9:16",
  "shots": { "count": 14, "mean": 1.9, "median": 1.6, "shortest": 0.6 },
  "cuts": { "onBeat": 0.78, "bpm": 128 },
  "motion": { "static": 0.35, "push": 0.4, "handheld": 0.25 },
  "look": { "name": "cinematic", "contrast": 1.08, "saturation": 0.94, "temperature": 0.12 },
  "captions": { "position": "bottom", "size": 0.055, "wordsPerCard": 3, "wpm": 168, "style": "outline" },
  "hook": { "firstCut": 0.8, "firstWord": 0.35 },
  "audio": { "musicUnderVoice": -9.5 }
}
```

Applied to *the user's own* footage this produces a video with the same pacing, framing
discipline, look, caption style and music behaviour — which is what "make one like this"
means in practice.

## 2. Open source we can actually use (verified today via the GitHub API)

| Project | Licence | Stars | Role |
|---|---|---|---|
| `Breakthrough/PySceneDetect` | BSD-3-Clause | 5.1k | shot boundaries (already shipping) |
| `soCzech/TransNetV2` | MIT | 1.0k | learned shot-boundary detection, for fast-cut montage where thresholds fail |
| `opencv/opencv` | Apache-2.0 | 90.6k | optical flow → camera motion classification |
| `google-ai-edge/mediapipe` | Apache-2.0 | 36.7k | subject/face framing statistics |
| `PaddlePaddle/PaddleOCR` | Apache-2.0 | 88.1k | on-screen text: where captions sit and how big they are |
| `JaidedAI/EasyOCR` | Apache-2.0 | 29.9k | lighter OCR alternative |
| `tesseract-ocr/tesseract` | Apache-2.0 | 76.1k | OCR without any Python ML stack |
| `colour-science/colour` | BSD-3-Clause | 2.6k | palette and colour statistics done properly |
| `openai/CLIP` | MIT | 34.2k | optional: tag what a shot shows ("gym", "food", "talking head") |
| `SYSTRAN/faster-whisper` | MIT | 25k | speech, for caption rhythm (already shipping) |
| `yt-dlp/yt-dlp` · `instaloader/instaloader` | Unlicense · MIT | 186k · 13.2k | fetching a link, **with the caveat in §4** |

Rejected for this feature, on licence grounds:

* `ultralytics` (YOLO) — **AGPL-3.0**; the obvious detector, unusable in a shipped app.
* `facebookresearch/ImageBind` — **CC-BY-NC**; no commercial use.

## 3. What cannot be done, and will not be promised

* **Copying the original's material.** Its footage, music, fonts and graphics are someone
  else's work. The template carries *numbers and names*, never media.
* **Reproducing bespoke motion graphics.** A hand-animated title sequence cannot be
  recovered from pixels; we can say "there is a boxed caption at the top for 1.2 s", not
  rebuild the animation.
* **Matching the music.** We can match the *tempo* and the ducking behaviour; the track
  itself has to be the user's or from a CC0 library.
* **"Same video, one click."** The editing grammar transfers. The performance, the script
  and the location do not.

## 4. The legal and practical caveat about fetching links

Downloading another account's Instagram video is a question of that platform's terms and
of copyright, and it is the user's call, not ours to make silently. The honest design:

* analysing a **file the user provides** is always allowed and is the default path;
* a **link** field exists but shows, before anything downloads, what the app is about to
  do and whose responsibility it is;
* nothing is uploaded anywhere: analysis runs on the machine, like the rest of the app.

## 5. Suggested build order

1. **Analyser** (`core/engine/style.py`): shots, cut rhythm, beats, motion, colour, hook —
   NumPy + FFmpeg + what we already ship. No new dependency.
2. **`.cetemplate` document** and a template gallery on the launcher (save, name, delete).
3. **Apply to my footage**: cut the user's clips to the template's rhythm (on the beat when
   the template says so), apply the look, set the aspect, place captions in that style.
4. **OCR pass** for caption style (optional dependency, downloaded on demand).
5. **CLIP tagging** for shot-type suggestions ("this template wants a wide establishing
   shot here") — last, and only if it earns its size.

Steps 1–3 need nothing that is not already installed, which is why they come first.
