# Review of the proposed AI feature repositories (2026-08-21)

Checked against the GitHub API, including each project's actual licence text rather
than the label GitHub shows.

## 1. Nine of the eleven links do not exist

| Proposed | Result |
|---|---|
| `antoinelame/eye-contact-cnn` | 404 |
| `guilhermealegre/gaze-correction` | 404 |
| `zhenchengfang/VeraRetouch` | 404 |
| `0x00b1/zeroscratches` | 404 |
| `NVlabs/UniRelight` | 404 |
| `facebookresearch/pixlrelight` | 404 |
| `jonaylor89/LogicCut` | 404 |
| `ipmanlk/Hermes-Video-Editing` | 404 |
| `wladradchenko/Wunjo` | 404 (the real repository is `wladradchenko/wunjo.wladradchenko.ru`) |

## 2. The two that exist

| Project | Stars | Licence | Verdict |
|---|---|---|---|
| `Rudrabha/Wav2Lip` | 13.2k | **none** | ❌ cannot be used. No licence file means all rights reserved, and the README restricts use to non-commercial research. We may not redistribute it or its weights. |
| `OpenTalker/video-retalking` | 7.3k | Apache-2.0 | ⚠️ the code is permissive, but it runs on weights derived from Wav2Lip, so the licence problem simply moves to the model. Unmaintained since 2024, and it pulls in a multi-gigabyte PyTorch stack. |

## 3. Real alternatives, with their real licence terms

| Need | Project | Stars | Actual licence | Usable? |
|---|---|---|---|---|
| Eye contact | `rehg-lab/eye-contact-cnn` | 108 | Georgia Tech — **non-commercial research only** | ❌ |
| Gaze tracking (detection, not correction) | `antoinelame/GazeTracking` | 2.6k | MIT | ✅ but it only *measures* gaze, it cannot redirect it |
| Lip sync | `bytedance/LatentSync` | 6.0k | Apache-2.0 | ⚠️ the only cleanly licensed option; heavy (diffusion + PyTorch) |
| Lip sync | `TMElyralab/MuseTalk` | 6.4k | MIT code, weights under a separate agreement | ⚠️ same pattern: free code, restricted weights |
| Retouch / face restore | `TencentARC/GFPGAN` | 37.7k | Apache-2.0 **plus** non-commercial clauses on some models | ⚠️ |
| Retouch / face restore | `sczhou/CodeFormer` | 18.1k | **S-Lab — non-commercial only** | ❌ |
| Photo restoration | `microsoft/Bringing-Old-Photos-Back-to-Life` | 15.7k | MIT | ✅ scratch and damage removal, not beautification |
| Relight | `lllyasviel/IC-Light` | 8.5k | Apache-2.0 | ✅ genuinely usable, but it is a diffusion model: seconds per frame |
| AI remix | `wladradchenko/wunjo.wladradchenko.ru` | 1.2k | MIT | ✅ a whole application, not a library — worth reading, not embedding |

## 4. The recurring trap

Almost every headline AI feature in this space follows the same pattern: **permissive
code, restricted weights**. A licence badge saying "MIT" on the repository says
nothing about the checkpoint the feature actually needs. Shipping such a model inside
our installer would misrepresent our own licence to every user who redistributes the
app.

There is also a practical cost: each of these brings PyTorch and CUDA. That is
roughly 2–4 GB per feature against an installer that is currently 474 MB, for
features that run at seconds per frame on a CPU.

## 5. What we will do instead — an optional plugin channel

Rather than bundling or rejecting outright:

1. **Nothing AI-heavy ships in the installer.** The base app stays permissively
   licensed and small.
2. **An "AI plugins" screen** lists each feature with its **real licence, size and
   speed**, and installs it on demand into `~/CuttingEdge/plugins/<name>`.
3. **The licence is shown before the download** and must be accepted. Anything
   marked non-commercial is labelled as such, so a user editing client work knows.
4. **Each plugin runs as a separate process** behind a small contract
   (`stdin: job.json → stdout: progress`), so a GPL or research-only component never
   links into our process and cannot contaminate our licence.

That gives the capability to whoever wants it, without lying about what the product
is.

### Recommended first plugins, in order of value per gigabyte

1. **Face restoration / retouch** — `Bringing-Old-Photos-Back-to-Life` (MIT), or
   GFPGAN with its terms displayed.
2. **Relight** — `IC-Light` (Apache-2.0), realistic for still frames and short clips.
3. **Lip sync** — `LatentSync` (Apache-2.0), the only cleanly licensed option.
4. **Eye contact** — postponed: every existing implementation is research-only.
5. **AI remix** — better served by our own pipeline (transcript → LLM → edit model)
   than by importing someone else's application.
