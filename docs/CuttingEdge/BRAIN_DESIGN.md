# The brain: where Whisper and Ollama belong (and where they must not)

The user asked: they run Ollama locally and Whisper is free and offline — should those two
be the brain that manages the data and produces a better result, and can we run both and
keep whichever output is better?

Yes to both, but only with a division of labour that respects what each thing can actually
do. Getting this boundary wrong is how an editor starts inventing facts about the footage.

---

## 1. What each part is allowed to say

| Question | Who answers | Why |
|---|---|---|
| How many shots, how long, where are the cuts? | signal processing (`scenedetect`, frame differences) | Measurable. An LLM cannot see the video; asked anyway, it will produce a confident number that is fiction. |
| What is the tempo, where are the beats? | our detector (spectral flux + autocorrelation) | Measured against synthesised click tracks; the error is known. |
| How does the camera move? | frame correlation | Same. |
| What is said, and exactly when? | **Whisper** (`faster-whisper`, word timings, VAD) | This is its job, and it returns per-segment confidence we can use. |
| Which moments are worth keeping, in what order, and what should the hook say? | **Ollama** | This is judgement over text, which is what a language model is for. |
| Is the resulting edit any good? | arithmetic (see §3) | Never a second opinion from another model. |

The rule: **the LLM never measures, and the measurements never argue.**

## 2. Running both, honestly

### Whisper: two passes, on a condition

`faster-whisper` already reports `avg_logprob` per segment. So:

* default pass with a small/medium model;
* if the mean confidence is below a threshold (or the user picks "Accurate"), run again with
  a larger model and keep, **per segment**, whichever has the better confidence.

That is a real comparison with a real criterion. The cost is honest too: the second pass
roughly doubles the transcription time, so it is a switch — Fast / Accurate / Both — not a
silent default.

### Ollama: a race the rules always enter

Two (or three) planners produce a candidate edit from the same measured data:

1. the **rule planner** — deterministic, offline, always available;
2. the **Ollama planner** — the transcript, the measured highlights and the template summary
   go in; an ordered plan comes back as JSON against a fixed schema;
3. optionally a second Ollama model, if the user has one.

Every candidate is scored by the same function and the winner is applied. Because the rule
plan is always in the race, a bad or slow LLM answer can never make the output worse than
the offline result — it can only win by being better on the score.

## 3. The judge: a score, not a vibe

A candidate plan is a list of picks (source ranges) placed on the template's rhythm. It is
scored on things that can be counted:

| Term | Meaning | Weight |
|---|---|---|
| duration fit | how close the total is to the template's length | ×3 |
| speech integrity | no cut lands inside a spoken word | ×3 |
| on-beat cuts | fraction of cuts within 120 ms of a beat, compared with the template's own ratio | ×2 |
| silence avoided | share of the result that is not silence | ×2 |
| highlight strength | sum of the measured scores of the chosen moments | ×2 |
| variety | penalty for reusing the same source range twice | ×1 |
| shot-length match | difference between the plan's shot lengths and the template's | ×1 |

The score is logged next to each candidate, so the choice is inspectable: "rule plan 0.71,
llama3 0.83 — used llama3". That line is also the honest answer to "did the AI help?".

## 4. What this costs, said out loud

* An Ollama model is 4–8 GB on disk and slow on a CPU-only machine. It is therefore
  **optional**, runs with a timeout, and every feature works without it.
* Nothing is uploaded: Ollama is local, Whisper is local. That is the whole point.
* The LLM sees **text**, never frames: transcript, timings, measured statistics. A model that
  cannot see the picture must not be asked about the picture.

## 5. Where the LLM genuinely earns its place

* choosing the eight moments that tell a story out of forty that are merely loud;
* ordering them so the hook lands first;
* writing the on-screen caption text (short, in the speaker's language, Persian included);
* naming the template and describing it in a sentence the user can recognise later;
* matching content to slots: "this template opens on a wide shot — you have one at 00:42".

Everything else in this feature is measurement, and measurement is not a language problem.

## 6. Already in place

* `core/assistant/planner.py` — the LLM-proposes/whitelist-validates pattern, with Ollama
  support and an offline rule planner, plus `applyPlan.ts` which clamps every value before
  it reaches the timeline.
* `core/engine/transcribe.py` — `faster-whisper` with word timestamps and VAD.
* `core/engine/style.py` — the measurements (shots, rhythm, beats, motion, colour).

So the brain does not need new infrastructure: it needs the score function of §3, an Ollama
planner that emits the same plan schema, and a race between them.


---

## 7. One brain, two doors

The user asked whether this same machinery can also power the **Assistant** button in the
editor, so any prompt is analysed and carried out. Yes — and it should, because the two
features are the same pipeline with different inputs:

```
                     measurements (shots, beats, silence, motion, colour, transcript)
                                        │
        prompt ──────────┐              │              ┌────────── template
                         ▼              ▼              ▼
                    ┌───────────────────────────────────────┐
                    │  planners: rules · Ollama · (model 2)  │   → candidate plans
                    └───────────────────────────────────────┘
                                        │
                            validator (whitelist + clamps)
                                        │
                    judge ── objective score, when there is a target
                                        │
                         one undoable step on the timeline
```

Shared by both doors:

* the **operation vocabulary** — the 26 whitelisted operations in `core/assistant/planner.py`
  (`splitAt`, `setFilter`, `addTransition`, `generateCaptions`, …). Style matching adds a few
  (`useHighlights`, `applyLook`, `setAspect`, `setKeyframes`, `applyTemplate`) and the
  assistant gets them for free — "make this look like my gym template" becomes one operation.
* the **validator** — every value clamped, every id checked, unknown operations dropped.
* **undo** — the whole plan is applied as a single step, so the worst outcome is `Ctrl+Z`.
* the **measurements** — this is the real upgrade for the assistant: it can now be told
  "the clip has 12 shots, 118 BPM, speech in 61 % of it", so "cut this on the beat" or
  "keep only the talking parts" stop being guesses.

Where they differ, and this matters:

| | Style match | Free-form prompt |
|---|---|---|
| Goal | defined by the template (length, rhythm, look) | defined by a sentence |
| Judge | the score of §3 — candidates race, best wins | **there is no objective score.** "Did it do what I meant?" cannot be measured |
| Safety | score + validation | validation + a preview of the operations before they run |

So for a free prompt the honest design is not a scored race but: *plan → show the user the
list of operations in their own language → apply on confirmation → undoable*. Pretending a
number can decide whether a sentence was understood would be theatre.

## 8. What that adds to the assistant, concretely

1. Measurements are attached to the prompt context, so the LLM stops inventing timings.
2. New operations let one sentence do real work: apply a template, keep the highlights, cut
   on the beat, duck the music.
3. A dry-run panel: "I will split at 0:12, drop three silent gaps, add a fade between shots
   2 and 3, and set the cinematic look — apply?"
4. The offline rule planner keeps covering the common intents, so the button works with no
   model installed, exactly as it does today.
