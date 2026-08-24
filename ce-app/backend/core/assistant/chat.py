"""A conversation with the editor, not a one-shot command.

The assistant the user had answered one sentence and forgot it. To ask a second
question they had to re-explain the timeline, so it felt like a form rather than
a conversation. This module keeps the shape of a conversation — history in, one
reply out, with the steps it took on the way — while keeping the two guarantees
that made the old one safe:

* **it never edits directly.** An editing request still becomes a plan from the
  fixed whitelist, described in the user's own language, applied only on Apply
  and undoable in one step (`BRAIN_DESIGN.md` §7).
* **it never pretends.** Every answer says where it came from — `ollama:qwen2.5`
  or `offline` — and the steps are what actually happened, not a progress
  animation. With no model connected it answers from what was measured and says
  that plainly, because an assistant that invents an answer is worse than one
  that admits it is a rule.

State lives in the client, which sends the history each time. A backend that
remembers a conversation is a backend that can lose half of one on restart.
"""
from __future__ import annotations

import time

from core.assistant import planner, providers
from core.engine import intent as intent_model

SYSTEM = """You are the editing assistant inside Cutting Edge, a video editor.

You are talking to the person who owns the footage. Answer in {language_name},
in two or three short sentences, and never invent a number: if the timeline does
not say it, say that it does not say it.

{about}
What is on the timeline right now:
{timeline}

You can also *do* things, but only through the editor's own operations, and the
editor always shows the user a preview first. The operations are:
{operations}

When the user asks for an edit, answer with what you would do and why — the
software turns the request into operations itself, so do not write JSON.
When they ask a question, answer the question."""

#: What the offline assistant is allowed to claim about itself, in both
#: languages — a Persian reply that switches to English mid-sentence is the
#: bug the user reads twice.
CAPABILITIES = (
    "cut silence, split on scene changes, trim, split, retime, add transitions, "
    "grade, animate, add captions, reframe and export",
    "سکوت‌ها را ببرم، در تغییر نما برش بزنم، کوتاه کنم، تقسیم کنم، سرعت را عوض کنم، "
    "ترنزیشن بگذارم، رنگ را تنظیم کنم، انیمیشن بدهم، زیرنویس بگذارم، کادر را عوض کنم و خروجی بگیرم",
)


def _timeline_facts(timeline: dict) -> dict:
    clips = [c for c in (timeline or {}).get("clips", []) if isinstance(c, dict)]
    video = [c for c in clips if c.get("trackId", "").startswith("v")]
    end = max((float(c.get("start", 0) or 0) + float(c.get("duration", 0) or 0) for c in clips), default=0.0)
    return {
        "clips": len(clips),
        "video": len(video),
        "tracks": len((timeline or {}).get("tracks", [])),
        "transitions": len((timeline or {}).get("transitions", [])),
        "duration": round(end, 2),
    }


def _about(wanted: intent_model.Intent, language: str = "en") -> str:
    """What the user already said the video is, for the model's system prompt.

    The answers come from the Style Match card. Handing them to the assistant is
    the difference between "which part is strongest?" being answered about a
    generic video and being answered about *this* one — a lesson for students is
    not judged like a music clip.
    """
    if wanted.empty:
        return ""
    return "What the owner said this video is: " + "; ".join(
        wanted.describe(language=language)) + "."


def _step(steps: list[dict], en: str, fa: str, ms: float = 0.0) -> None:
    steps.append({"en": en, "fa": fa, "ms": round(ms * 1000)})


def _plan_reply(plan: planner.Plan, language: str) -> str:
    """What the plan will do, as a sentence a person would say."""
    lines = []
    for index, item in enumerate(planner.describe_ops(plan.ops), start=1):
        lines.append(f"{index}. {item['fa' if language == 'fa' else 'en']}")
    head = (
        f"{len(plan.ops)} کار انجام می‌دهم — هنوز هیچ‌چیز تغییر نکرده:"
        if language == "fa"
        else "Here is what I would do — nothing has changed yet:"
    )
    tail = (
        "«اعمال» را بزن تا یک‌جا و با یک Ctrl+Z قابل برگشت انجام شود."
        if language == "fa"
        else "Press Apply and it happens as one step you can undo with Ctrl+Z."
    )
    return "\n".join([head, *lines, "", tail])


def _offline_reply(facts: dict, language: str, asked: str,
                   wanted: intent_model.Intent | None = None) -> str:
    """No model connected: answer from the measurements, and say so."""
    known_fa = "؛ ".join(wanted.describe(language="fa")) if wanted and not wanted.empty else ""
    known_en = "; ".join(wanted.describe()) if wanted and not wanted.empty else ""
    if language == "fa":
        lines = [
            f"تایم‌لاین تو: {facts['clips']} کلیپ ({facts['video']} ویدیو) روی {facts['tracks']} ترک، "
            f"{facts['transitions']} ترنزیشن، {facts['duration']} ثانیه.",
            f"می‌توانم {CAPABILITIES[1]} — کافی است در یک جمله بگویی تا قبل از انجام، نقشه‌اش را نشان دهم.",
            "الان هیچ مدل زبانی وصل نیست، پس از روی اندازه‌گیری‌ها جواب می‌دهم نه از روی فهم متن. "
            "در Settings → موتورهای AI می‌توانی Ollama، OpenAI، Gemini یا Claude را وصل کنی.",
            f"(سؤال تو: «{asked[:120]}»)",
        ]
        if known_fa:
            lines.insert(1, f"از کارتِ «این ویدیو چیست؟» می‌دانم: {known_fa}.")
        return "\n".join(lines)
    lines = [
        f"Your timeline: {facts['clips']} clips ({facts['video']} video) on {facts['tracks']} tracks, "
        f"{facts['transitions']} transitions, {facts['duration']} s long.",
        f"I can {CAPABILITIES[0]} — say it in one sentence and I will show you the plan before touching anything.",
        "No language model is connected right now, so I am answering from what is measured rather than "
        "from reading your sentence. Settings → AI engines connects Ollama, OpenAI, Gemini or Claude.",
        f"(You asked: “{asked[:120]}”)",
    ]
    if known_en:
        lines.insert(1, f"From your Style Match answers I know: {known_en}.")
    return "\n".join(lines)


def reply(
    messages: list[dict],
    timeline: dict,
    selected_clip_id: str | None = None,
    language: str = "en",
    provider: str = "auto",
    intent: dict | intent_model.Intent | None = None,
) -> dict:
    """One turn of the conversation.

    Returns the reply, an optional dry-run plan, where the answer came from, and
    the steps taken — which is the honest version of "it feels alive".
    """
    began = time.perf_counter()
    steps: list[dict] = []
    history = [m for m in (messages or []) if isinstance(m, dict) and m.get("content")]
    asked = str(history[-1]["content"]).strip() if history else ""
    facts = _timeline_facts(timeline)
    wanted = (intent if isinstance(intent, intent_model.Intent)
              else intent_model.Intent.from_dict(intent))
    if not wanted.empty:
        _step(steps,
              "I know what this video is for: " + "; ".join(wanted.describe()),
              "می‌دانم این ویدیو برای چیست: " + "؛ ".join(wanted.describe(language="fa")),
              0.0)

    mark = time.perf_counter()
    _step(steps,
          f"Read the timeline: {facts['clips']} clips, {facts['duration']} s",
          f"تایم‌لاین را خواندم: {facts['clips']} کلیپ، {facts['duration']} ثانیه",
          time.perf_counter() - mark)

    # ---- is this a request to edit, or a question? -------------------------
    mark = time.perf_counter()
    plan = planner.make_plan(asked, timeline or {}, prefer_llm=provider != "off")
    for op in plan.ops:
        op.setdefault("clipId", selected_clip_id)

    if plan.ops:
        _step(steps,
              f"It is an editing request: {len(plan.ops)} operations from the whitelist",
              f"درخواست تدوین است: {len(plan.ops)} عملیات از فهرست مجاز",
              time.perf_counter() - mark)
        payload = plan.as_dict()
        payload["preview"] = planner.describe_ops(plan.ops)
        return {
            "reply": _plan_reply(plan, language),
            "plan": payload,
            "provider": "offline" if plan.source == "rules" else plan.source,
            "steps": steps,
            "seconds": round(time.perf_counter() - began, 2),
        }

    _step(steps,
          "It is a question, not an edit",
          "سؤال است، نه درخواست تدوین",
          time.perf_counter() - mark)

    # ---- answer it ---------------------------------------------------------
    mark = time.perf_counter()
    system = SYSTEM.format(
        language_name="Persian (فارسی)" if language == "fa" else "English",
        about=_about(wanted, language),
        timeline=planner.describe_timeline(timeline or {}),
        operations="\n".join(f"- {name}: {what}" for name, what in planner.OPERATIONS.items()),
    )
    turns = [{"role": "system", "content": system}]
    # Keep the window small and recent: a local 7B model on a CPU is the common
    # case here, and a long history is the difference between an answer and a
    # three-minute wait.
    turns.extend({"role": m["role"], "content": str(m["content"])} for m in history[-8:])

    answer = providers.chat(turns, choice=provider, timeout=120.0)
    if answer is not None:
        _step(steps,
              f"Asked {answer.label} and waited {answer.seconds:g} s",
              f"از {answer.label} پرسیدم و {answer.seconds:g} ثانیه صبر کردم",
              time.perf_counter() - mark)
        return {
            "reply": answer.text,
            "plan": None,
            "provider": answer.label,
            "steps": steps,
            "seconds": round(time.perf_counter() - began, 2),
        }

    _step(steps,
          "No model is connected, so I answered from the measurements",
          "مدلی وصل نیست، پس از روی اندازه‌گیری‌ها جواب دادم",
          time.perf_counter() - mark)
    return {
        "reply": _offline_reply(facts, language, asked, wanted),
        "plan": None,
        "provider": "offline",
        "steps": steps,
        "seconds": round(time.perf_counter() - began, 2),
    }


def _event_step(en: str, fa: str, seconds: float) -> dict:
    return {"kind": "step", "en": en, "fa": fa, "ms": round(seconds * 1000)}


def reply_stream(
    messages: list[dict],
    timeline: dict,
    selected_clip_id: str | None = None,
    language: str = "en",
    provider: str = "auto",
    intent: dict | intent_model.Intent | None = None,
):
    """The same turn as `reply()`, as a sequence of events.

    Three event kinds, so the screen never has to guess what it is looking at:

    * `step` — something happened, with how long it took. Shown as it happens,
      which is the whole point: a bouncing dot is not evidence of work.
    * `delta` — more of the answer. Only a model produces these; our own offline
      answer arrives in one piece, because slowing down an instant answer to look
      thoughtful would be theatre.
    * `done` — the final reply, the plan if there is one, the provider, the total.

    The guarantees are the same as the non-streaming turn: nothing is applied,
    and the source is always named.
    """
    began = time.perf_counter()
    history = [m for m in (messages or []) if isinstance(m, dict) and m.get("content")]
    asked = str(history[-1]["content"]).strip() if history else ""
    facts = _timeline_facts(timeline)
    wanted = (intent if isinstance(intent, intent_model.Intent)
              else intent_model.Intent.from_dict(intent))
    if not wanted.empty:
        yield _event_step(
            "I know what this video is for: " + "; ".join(wanted.describe()),
            "می‌دانم این ویدیو برای چیست: " + "؛ ".join(wanted.describe(language="fa")),
            0.0,
        )

    yield _event_step(
        f"Read the timeline: {facts['clips']} clips, {facts['duration']} s",
        f"تایم‌لاین را خواندم: {facts['clips']} کلیپ، {facts['duration']} ثانیه",
        time.perf_counter() - began,
    )

    mark = time.perf_counter()
    plan = planner.make_plan(asked, timeline or {}, prefer_llm=provider != "off")
    for op in plan.ops:
        op.setdefault("clipId", selected_clip_id)

    if plan.ops:
        yield _event_step(
            f"It is an editing request: {len(plan.ops)} operations from the whitelist",
            f"درخواست تدوین است: {len(plan.ops)} عملیات از فهرست مجاز",
            time.perf_counter() - mark,
        )
        payload = plan.as_dict()
        payload["preview"] = planner.describe_ops(plan.ops)
        source = "offline" if plan.source == "rules" else plan.source
        yield {
            "kind": "done",
            "reply": _plan_reply(plan, language),
            "plan": payload,
            "provider": source,
            "seconds": round(time.perf_counter() - began, 2),
        }
        return

    yield _event_step(
        "It is a question, not an edit",
        "سؤال است، نه درخواست تدوین",
        time.perf_counter() - mark,
    )

    mark = time.perf_counter()
    system = SYSTEM.format(
        language_name="Persian (فارسی)" if language == "fa" else "English",
        about=_about(wanted, language),
        timeline=planner.describe_timeline(timeline or {}),
        operations="\n".join(f"- {name}: {what}" for name, what in planner.OPERATIONS.items()),
    )
    turns = [{"role": "system", "content": system}]
    turns.extend({"role": m["role"], "content": str(m["content"])} for m in history[-8:])

    stream = providers.chat_stream(turns, choice=provider, timeout=180.0)
    if stream is not None:
        config = providers.configured(provider)
        label = f"{config[0]}:{config[2]}" if config else "model"
        text = ""
        for piece in stream:
            text += piece
            yield {"kind": "delta", "text": piece}
        if text.strip():
            yield _event_step(
                f"Asked {label} and it answered as it wrote",
                f"از {label} پرسیدم و هم‌زمان که می‌نوشت جواب داد",
                time.perf_counter() - mark,
            )
            yield {
                "kind": "done",
                "reply": text,
                "plan": None,
                "provider": label,
                "seconds": round(time.perf_counter() - began, 2),
            }
            return
        # A stream that opened and said nothing is still worth reporting: the
        # fallback is an answer, and the user is told why it happened.
        yield _event_step(
            f"{label} answered nothing, so I fell back to the measurements",
            f"{label} هیچ جوابی نداد، پس به اندازه‌گیری‌ها برگشتم",
            time.perf_counter() - mark,
        )
    else:
        yield _event_step(
            "No model is connected, so I answered from the measurements",
            "مدلی وصل نیست، پس از روی اندازه‌گیری‌ها جواب دادم",
            time.perf_counter() - mark,
        )

    offline = _offline_reply(facts, language, asked, wanted)
    yield {"kind": "delta", "text": offline}
    yield {
        "kind": "done",
        "reply": offline,
        "plan": None,
        "provider": "offline",
        "seconds": round(time.perf_counter() - began, 2),
    }
