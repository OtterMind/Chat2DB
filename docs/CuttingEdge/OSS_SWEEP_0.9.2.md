# Open-source sweep, with a graphics card on the table (0.9.2)

The brief: *search Hugging Face and GitHub carefully, find open source we should
have adopted already and do not have, and anything that will be useful later.
Bring in whatever is good. Heaviness is not a problem.*

Everything below was checked against the **GitHub API** and **PyPI** on the day
of writing. One honest limitation: **`huggingface.co` is not reachable from this
sandbox** (empty responses), so model-card licences there are marked
`verify before adopting` rather than guessed. That matters — this project has
already been bitten once by a repository licence that differed from the shipped
wheel's (`piper` is MIT on GitHub and GPL-3 on PyPI).

---

## 1. Things we should have had already

| What | Licence (verified) | Why it should have been in from the start |
|---|---|---|
| **TransNetV2** `soCzech/TransNetV2` | **MIT**, 1 027★ | Shot-boundary detection that sees *gradual* transitions. Our `ContentDetector` only finds hard cuts, which is precisely why every reference comes back `"type": "cut"` and the user reported "the transitions were not detected". This is the single most direct fix for a complaint we already have. |
| **silero-vad** `snakers4/silero-vad` | **MIT**, 10 038★ | Voice activity detection that is far better than FFmpeg's `silencedetect`, which is the input to *every* highlight decision we make. Ours mistakes a breath for speech and background music for talking. ~2 MB ONNX model, runs on CPU in real time. |
| **pyloudnorm** / **noisereduce** | pyloudnorm: check; noisereduce **MIT** | We hand-rolled loudness and denoise chains in FFmpeg. Not wrong, but unmeasured against a reference implementation. |
| **OpenTimelineIO** | **Apache-2.0** | Project interchange: open our timeline in Resolve/Premiere. Not urgent, but it is the standard and we keep inventing our own. |
| **pysubs2** | check on PyPI (no field) | Subtitle format handling we partly reimplemented in `subtitles.py`. |

## 2. Things worth having now that a GPU is in play

| What | Licence (verified) | What it buys | Cost |
|---|---|---|---|
| **Demucs** `facebookresearch/demucs` | **MIT**, 10 356★ | Splits the reference's soundtrack into music/voice: a bed without the original speaker, an honest ducking envelope, and a beat grid from the drum stem. | `torch` (~2.5 GB) — on-demand engine |
| **whisperX** `m-bain/whisperX` | **BSD-2-Clause**, 23 707★ | Forced alignment for word timings far tighter than Whisper's own, plus diarisation hooks. Word timing is what karaoke captions and speech-integrity scoring stand on. | torch |
| **Real-ESRGAN** `xinntao/Real-ESRGAN` | **BSD-3-Clause**, 36 560★ | Upscaling old or small footage. `realesrgan` on PyPI is BSD-3. | torch |
| **Practical-RIFE** `hzwer/Practical-RIFE` | **MIT**, 998★ | Frame interpolation: real slow motion instead of duplicated frames. | torch |
| **CLIP** `openai/CLIP` | **MIT**, 34 213★ | Content matching between the reference's shots and the user's — the piece that would make Style Match feel like it understood rather than counted. | torch, ~350 MB weights |
| **rembg** `danielgatis/rembg` | **MIT**, 24 395★ | Background removal without a green screen. Runs on `onnxruntime`, which we already ship. | small |
| **rapidocr-onnxruntime** | **Apache-2.0** | Reads the on-screen text of the reference — the other complaint we already have. Reuses our `onnxruntime`. | ~15 MB |
| **onnxruntime-gpu** | **MIT** | Everything ONNX above runs on the GTX 1650 instead of the processor. | ~200 MB |

## 3. Rejected, and why (all verified, not remembered)

| What | Licence | Verdict |
|---|---|---|
| `PeterL1n/RobustVideoMatting` | **GPL-3.0** | Cannot ship. |
| `bbc/audiowaveform` | **GPL-3.0** | Cannot ship. |
| `mifi/lossless-cut` | **GPL-2.0** | Cannot ship (and it is a whole app). |
| `chaiNNer-org/chaiNNer` | **GPL-3.0** | Cannot ship. |
| `TencentARC/GFPGAN` | **NOASSERTION** | No clear licence; face restoration would be nice, but not on an unclear licence. |
| `Rudrabha/Wav2Lip` | **no licence file** | Lip sync — attractive and unusable. |
| `OpenTalker/SadTalker` | **NOASSERTION** | Same. |
| `Rikorose/DeepFilterNet` | **NOASSERTION** on the repo | Previously noted as MIT/Apache; the API now returns `NOASSERTION`, so **re-verify per-directory before adopting**. This is exactly the trap that makes this table necessary. |
| `open-clip-torch` | `MIT` on PyPI, `NOASSERTION` on GitHub | Contradiction between registry and repo → use `openai/CLIP` (MIT on both). |
| `briaai/RMBG-1.4` (HF) | non-commercial (known) | Rejected earlier, still rejected. |
| `pyannote/speaker-diarization-3.1` (HF) | gated, terms required | Cannot be a silent dependency. |
| `nvidia/parakeet` (HF) | verify | Faster than Whisper on NVIDIA, but licence unverified from here. |

## 4. What was adopted in 0.9.2

Only two things, because the rest need weights fetched at runtime and that is a
feature of its own (the AI runtime card), not a one-line dependency:

1. **A curated Ollama catalogue with a download button each** — including the
   **vision** models, which is the change that lets the app look at frames at
   all. The list is filtered against the card in the machine: a 4 GB laptop is
   told `qwen2.5vl:3b` fits and `llama3.2-vision:11b` does not.
2. **The encoder probe was fixed** (see §5) — not open source, but the reason
   this file exists at all was a measurement that turned out to be wrong.

## 5. The measurement that was wrong

The owner's GTX 1650 reported `Hardware encoding is off: Nothing was written
into output file, because at least one of its streams received no packets`.

That is **our probe's fault, not the driver's**. It encoded three frames of a
0.2-second clip into `-f null -`; NVENC buffers several frames internally and
only flushes at end of stream, so the process ended before the encoder produced
anything. x264 emits packets in the same three frames, which is why the shape
was never questioned.

The probe now encodes **1.5 seconds to a real file** and requires the file to be
non-empty. The card should report `h264_nvenc` after this release; if it still
does not, the reason shown will now be a real one.

## 6. Order I would work in next

1. **TransNetV2** — fixes "transitions were not detected", MIT, small.
2. **silero-vad** — improves the input to every editing decision, MIT, 2 MB.
3. **rapidocr** — fixes "the text was not detected", Apache-2.0, reuses onnxruntime.
4. **onnxruntime-gpu** — puts 1–3 on the card.
5. **CLIP** then **Demucs** as on-demand engines.
