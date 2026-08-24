# Making Style Match and the Assistant genuinely strong

Written after the owner's verdict on 0.8.x: *"I still do not feel a strong AI in
Style Match and the Assistant. It has to be far stronger and far more accurate.
The app getting heavier is not a problem."*

That last sentence changes what is on the table. Everything below is costed in
download size and in the one currency that matters more — **whether the result
can be measured**.

Licences were checked against the registries on the day of writing, not
remembered. Anything without a verified permissive licence is not here.

---

## 0. Why it does not feel like AI yet — the honest diagnosis

| What the user sees | What is actually happening |
|---|---|
| "The cuts are weak" | Until 0.8.1 the planner reused one moment; now it spreads, but it still ranks moments by **loudness and length**, with a text score only when captions exist. Nothing *watches* the footage. |
| "It did not detect the text" | True. There is no OCR. The template records "this video has captions" and nothing about what they said or how they looked. |
| "It did not detect the transitions" | We classify one global thing: hard cut vs dissolve. A CapCut zoom-punch, whip-pan, glitch or flash is measured as "cut". |
| "It did not detect the music" | Fixed in 0.8.2 — the reference's soundtrack now comes with the template. |
| "There is no AI in it" | The one model in the loop (Ollama) is **text-only** and only ever sees a list of numbers and, at best, a transcript. It has never seen a single frame of either video. |

The last row is the real answer. A language model that cannot see the video was
the right call when the alternative was letting it invent facts — but the
alternative today is a **vision** model that can actually look.

---

## 1. The proposal, in priority order

### 1.1 Let the model see — Ollama vision (biggest single win)

Ollama serves vision models through the same local API we already use
(`ollama/ollama`, MIT, verified): `qwen2.5vl`, `llava`, `llama3.2-vision`,
`moondream`. They take **images** alongside the prompt.

What that unlocks, concretely:

* a **contact sheet of the reference** — one JPEG per shot, laid out in a grid —
  with the question "describe this edit: what is each shot of, what is the hook,
  what is the pattern?";
* the same for the user's footage, so the planner can *match content to slots*:
  "the template opens on a wide establishing shot; you have one at 00:42";
* reading the **on-screen text** off the frames without a separate OCR stack,
  including its position and style;
* naming and describing the template in a sentence the user recognises later.

Cost: nothing in the installer (the model lives in the user's Ollama), a few
seconds per request on a GPU, a minute or two on a CPU. Risk: hallucination —
handled the way we already handle it, by letting the model *choose among
measured moments* and never invent timings, and by keeping the rule plan in the
race.

**Measurable:** on a reference built to a recipe (a known hook, known shot
subjects), the description has to name them. That is a real test, not a vibe.

### 1.2 Real shot-boundary detection — TransNetV2 (MIT, verified)

`ContentDetector` finds hard cuts and misses gradual ones, which is exactly why
every reference comes back as `"type": "cut"`. TransNetV2 (`soCzech/TransNetV2`,
MIT, 1 k stars) is a small CNN trained for shot boundaries **including
dissolves, wipes and fades**, and it publishes per-frame transition
probabilities — which is what we need to classify the *kind* of transition
rather than guess at it from a difference profile.

Cost: an ONNX model of a few tens of MB and `onnxruntime`, which we already
ship for faster-whisper's VAD.

**Measurable:** build a reference with 8 known dissolves and 8 known hard cuts;
the classifier has to separate them.

### 1.3 Stem separation — Demucs (MIT, verified)

Now that the reference's soundtrack travels with the template, the obvious next
question is: *which part of it is the music?* Demucs
(`facebookresearch/demucs`, MIT, 10 k stars) splits a track into drums, bass,
vocals and other. That gives:

* the reference's **music without its voice** as the bed;
* a much better ducking envelope (measure the voice stem, not the mix);
* a real beat grid, taken from the drum stem rather than from everything at once.

Cost: `torch` — a 2.5 GB installed dependency on Windows. Given "heavier is not
a problem", the right shape is still an **on-demand engine** in the AI runtime
card, so a user who never separates a track never pays for it.

**Measurable:** separate a mix we built ourselves from a known music file and a
known voice file, and compare the recovered stems against the originals in dB.

### 1.4 Content matching — CLIP embeddings (open_clip, verified)

`mlfoundations/open_clip` is 14 k stars but its licence field reads
`NOASSERTION`, so the safe route is OpenAI's own `openai/CLIP` (MIT, verified)
or a CLIP ONNX export. An embedding per shot turns "which of my shots belongs in
this slot?" into arithmetic: cosine similarity between the reference shot and
every candidate of the user's.

This is the piece that would make Style Match feel like it *understood* the
reference rather than counted it.

**Measurable:** with a reference whose shots are labelled (a face, a landscape,
a screen), the chosen candidates have to match the labels better than chance.

### 1.5 On-screen text — OCR (EasyOCR or PaddleOCR, both Apache-2.0, verified)

If 1.1 lands, the vision model reads text for free. If it does not (no Ollama on
the machine), `easyocr` 1.7.2 (Apache-2.0) or `rapidocr-onnxruntime` 1.4.4
(Apache-2.0, and it reuses the `onnxruntime` we already ship) gives the caption
text, its box and therefore its position and rough size.

**Measurable:** burn known text onto a fixture and read it back.

### 1.6 A better search, not just a better model

Half of "stronger AI" is not a model at all. Today the rule planner is greedy:
strongest moment first, one pass. A **beam search** over assignments — keeping
the best few partial edits and extending them — scored by the objective function
we already have, will beat greedy on the same inputs, deterministically, offline
and in milliseconds.

**Measurable:** on the same footage, beam search must score ≥ greedy on every
run, and strictly higher on a fixture built to trap greedy.

### 1.7 The Assistant: plan → critique → revise

One pass of an LLM writing operations is weak. Two are much stronger: the model
proposes, then is shown its own plan plus the validator's complaints and the
measured state, and revises. The dry-run panel already gives the user the last
word, so a second pass costs seconds and risks nothing.

Also worth adding: the assistant currently gets a list of clips. It should get
the **measurements** — shot count, tempo, speech ratio, silence map, face track
— so "cut this on the beat" stops being a guess.

---

## 2. What I would build first, and why

1. **1.1 Ollama vision** — the single change that turns "there is no AI in this"
   into "it looked at my video". No installer cost. The owner already runs Ollama.
2. **1.6 beam search + 1.7 two-pass assistant** — both are free, offline, and
   make the *floor* better rather than the ceiling.
3. **1.2 TransNetV2** — fixes a complaint that was literally reported
   ("transitions were not detected").
4. **1.5 OCR** — fixes the other one ("the text was not detected").
5. **1.4 CLIP**, then **1.3 Demucs** as on-demand engines.

---

## 3. Rejected, with reasons

* `open_clip_torch` — 14 k stars but `NOASSERTION` on GitHub; use `openai/CLIP`
  (MIT) or an ONNX export instead.
* `paddleocr` — Apache-2.0 and excellent, but it drags in PaddlePaddle; prefer
  `rapidocr-onnxruntime`, which reuses the runtime we already ship.
* Anything cloud. Everything here runs on the user's machine, as always.
