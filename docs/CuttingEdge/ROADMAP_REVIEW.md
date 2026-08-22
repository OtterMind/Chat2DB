# Review — `video-editing-app-roadmap (1).zip` (branch `Gif`)

The archive is a Next.js dashboard (`nextjs-postgresql-template`) whose whole payload is
`src/lib/roadmap-data.ts`: seven phases, 43 recommended repositories, 23 "improvements",
and a set of tips. This file is the audit of that content, done the same way as every
other survey in this project: **every repository checked against the GitHub API for
existence, SPDX licence, stars and last push.**

## Verdict in one line

The structure is good and no repository is invented — but **five of the recommendations
carry licences a free desktop app cannot ship**, two contradict the local-first promise,
and roughly a third are churn: replacing things that already work here.

---

## 1. What it gets right

* All 43 repositories **exist** (last survey found thirteen that did not).
* The seven phases match the plan we already agreed, in the same order.
* Genuinely good calls, already on our own verified list:
  `wavesurfer.js` (BSD-3), `librosa` (ISC), `mediapipe` (Apache-2.0),
  `demucs` (MIT), `OpenTimelineIO` (Apache-2.0), `yt-dlp` (Unlicense),
  `google-api-python-client` (Apache-2.0), `rembg` (MIT), `faster-whisper` (MIT),
  `vazirmatn` (OFL-1.1), `playwright` + `vitest` (Apache/MIT).
* Correct observations about *our* code: `differentialPackage` is already on, the
  edit model is pure enough to unit-test, and AI models must download on first use.
* New and worth taking: `Pomax/bezierjs` (MIT) for velocity curves,
  `colour-science` (BSD-3) for LUT/colour maths, `MTG/freesound-python` (MIT) for CC0
  sound effects, `apscheduler` (MIT) for scheduled publishing, `dnd-kit` (MIT) —
  though see §3.

## 2. Licence traps — do not adopt as written

| Recommendation | Actual licence (verified) | Why it is a problem |
|---|---|---|
| `remotion-dev/remotion` | custom, **not OSI** — free for individuals, paid for companies | Suggested as a *replacement for our preview*. It would make the core of a "completely free" editor depend on a commercial licence. |
| `shipshapecode/shepherd` | **AGPL-3.0** or commercial | Suggested for the onboarding tour. AGPL in a shipped desktop app is exactly what we refuse elsewhere. |
| `spotify/pedalboard` | **GPL-3.0** | Suggested for audio effects. Our FFmpeg chain already covers most of it. |
| `jianchang512/pyvideotrans` | **GPL-3.0** | Suggested for dubbing. Use `argos-translate` (MIT) + a permissive TTS instead. |
| `greensock/GSAP` | **no licence file in the repo** (custom "standard licence") | Animation is already covered by `framer-motion` (MIT), which we adopted in 0.3.9. |
| `Nuitka/Nuitka` | **AGPL-3.0** (with a runtime exception) | Fine as a *build tool*, but proposed as a packaging swap for something that already works; the exception has to be read carefully before anyone bets the installer on it. |
| `rany2/edge-tts` | LGPL-3 **and it is Microsoft's online service** | The tip "you already have edge-tts" is wrong on two counts: we do not, and it sends text to a remote server — the opposite of local-first. Kokoro (Apache-2.0) or Piper (Persian voices) are the honest options. |

`konva` (MIT), `celery` (BSD-3), `pyinstaller` (GPL-2 **with** a packaging exception) and
`all-contributors` (MIT) are reported as "Other" by the API but are fine on reading.

## 3. Technically wrong for *this* application

* **`celery`** — a task queue that needs Redis or RabbitMQ. In a desktop app that must
  work offline with one bundled Python, a broker is a second server to install and a new
  class of failure. Our thread pool plus the WebSocket progress channel already does this.
* **`better-sqlite3`** — a Node-side SQLite driver. The database is owned by the Python
  backend; adding a second writer from Electron invites locked-database bugs.
* **`dnd-kit`** — good library, wrong layer: the timeline needs pointer-level control
  (snapping, ripple, trim handles, pinch-zoom) that a generic drag-and-drop abstraction
  fights rather than helps. Keep it for lists (e.g. reordering a template gallery).
* **`i18next`** — the roadmap's own tip admits our `t(en, fa)` is fine. Migrating hundreds
  of call sites buys nothing a user can see.
* **`sentry-javascript`** — a crash reporter that phones home. Only acceptable
  opt-in and self-hosted; otherwise it breaks the "everything runs on your machine" promise
  the home screen makes.
* **`transformers`** — pulling the whole Hugging Face stack into a desktop installer for
  one or two models is how a 479 MB installer becomes 3 GB. Use ONNX/ncnn builds.
* **"Store proxies in `%TEMP%\.ce-cache`"** — wrong for us: `%TEMP%` is cleaned by Windows
  while a project is still open, which would silently delete the media the timeline plays.
  Proxies live in `~/CuttingEdge/work/proxies` and are tied to the source's mtime.
* **"Rename the repository from Chat2DB to cutting-edge"** — sensible one day, but it
  breaks every existing release URL and the updater's feed. Not before 1.0, and only with
  a redirect plan.

## 4. Things it missed that we already verified as better

`BiRefNet` (MIT weights, matting) · `DeepFilterNet` (MIT/Apache, speech denoise) ·
`Kokoro-82M` (Apache-2.0 TTS) · `piper` Persian voices · `Practical-RIFE` (MIT, frame
interpolation) · `Real-ESRGAN-ncnn-vulkan` (MIT **binary**, no PyTorch) ·
`argos-translate` (MIT) · `pysubs2` (MIT) · `scenedetect` (BSD-3, already shipped) ·
`Light-ASD` / `TalkNet-ASD` (MIT, active-speaker detection for reframing).

## 5. The archive as software

It is the stock `nextjs-postgresql-template`: `src/db/schema.ts` exports nothing,
`src/db/index.ts` throws without `DATABASE_URL`, and `/api/health` is the template's.
So the zip is a **document rendered as a web page**, not a tool — useful as a plan,
irrelevant as code. Nothing in it should be merged into `ce-app`.

## 6. What we actually take from it

1. `bezierjs` for the keyframe velocity curve (phase 1).
2. `colour-science` for LUT handling and auto white balance (phase 2).
3. `freesound-python` for a CC0 sound-effects library (phase 3).
4. `apscheduler` for scheduled publishing (phase 4).
5. The four-week ordering — waveforms, proxies, keyboard shortcuts, beat detection — which
   matches ours, except proxies and shortcuts are **already shipped** (0.3.8, 0.3.9).

Everything else stands as it is in `docs/CuttingEdge/OSS_SURVEY_0.3.8.md`.
