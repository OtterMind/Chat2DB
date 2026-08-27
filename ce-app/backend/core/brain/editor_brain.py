"""The professional-editor brain: know every tool, then choose like a human.

A seasoned editor does not apply every effect; they look at the material and the
reference and quietly decide, per tool, "is this the right tool here, and why".
This module makes that explicit. First it *knows* every tool the app owns (a
fixed inventory, so the brain can never reach for a feature that is not there).
Then, for a given reference template + the user's footage signals + the intent, it
considers each tool **separately** and returns a use/skip decision with a reason a
person would give — never a blanket "apply all".

It is deliberately deterministic and measured: every decision keys off a signal
that was actually measured (speech ratio, tempo, motion mix, action peaks,
presence, aspect, length). Nothing is guessed; a tool whose signal is absent is
skipped with the honest reason. The result is shown to the user as the editor's
notes, so the "brain" reads like a professional explaining their cut.

CONVENTION (standing, no prompt needed): this inventory is the app's toolbelt, so
**every new capability gets added here the moment it is built** — a TOOLS entry
plus its `assess()` decision — and therefore surfaces in Style Match
automatically. If a feature is not in TOOLS, it does not exist as far as the
editor is concerned. Keep this list in sync with `core/engine/` and the editor
toolbar; the test suite enforces one decision per tool.
"""
from __future__ import annotations

#: The tool inventory. `when` is the human rule; `signal` names the measured
#: quantity that decides it. This is the "I know my tools" part. Add a row here
#: (and a matching decision in `assess`) for every new feature — see convention.
TOOLS: list[dict] = [
    {"id": "beat_cuts", "en": "cut on the beat", "fa": "برش روی ضرب",
     "when": "there is music with a clear tempo", "signal": "bpm"},
    {"id": "slowmo", "en": "slow-mo the peak", "fa": "اسلوموی لحظه‌ی اوج",
     "when": "there is a sharp action peak (sport)", "signal": "action"},
    {"id": "captions", "en": "burn captions", "fa": "زیرنویس",
     "when": "someone talks", "signal": "speech"},
    {"id": "persian_norm", "en": "clean up the Persian subtitles", "fa": "مرتب‌سازی زیرنویس فارسی",
     "when": "captions are Persian", "signal": "persian"},
    {"id": "karaoke", "en": "word-by-word highlight", "fa": "هایلایت کلمه‌به‌کلمه",
     "when": "captions plus music, short-form", "signal": "speech"},
    {"id": "fillers", "en": "drop the uhs and ums", "fa": "حذف تپق‌ها",
     "when": "unscripted talking with filler words", "signal": "speech"},
    {"id": "ducking", "en": "duck music under the voice", "fa": "پایین آوردن موسیقی زیر صدا",
     "when": "voice and music together", "signal": "speech"},
    {"id": "reframe", "en": "follow the subject", "fa": "دنبال‌کردن سوژه",
     "when": "the subject moves or the aspect changes", "signal": "presence"},
    {"id": "grade", "en": "match the reference look", "fa": "هم‌رنگ‌کردن با الگو",
     "when": "always — colour is free", "signal": "always"},
    {"id": "transitions", "en": "transitions at junctions", "fa": "ترنزیشن سرِ اتصال‌ها",
     "when": "more than one shot", "signal": "shots"},
    {"id": "motion_transition", "en": "interpolated (RIFE) transitions", "fa": "ترنزیشن حرکتی (ریف)",
     "when": "high-motion junctions, RIFE present", "signal": "motion"},
    {"id": "hook_first", "en": "put the strongest moment first", "fa": "قوی‌ترین لحظه اول",
     "when": "short-form; you have seconds to hook", "signal": "action"},
    {"id": "denoise", "en": "clean the audio", "fa": "تمیزکردن صدا",
     "when": "the noise floor is high", "signal": "noise"},
    {"id": "interchange", "en": "hand off to Premiere/Resolve (OTIO)", "fa": "تحویل به پریمیر/داونچی (OTIO)",
     "when": "you want to finish in a pro NLE", "signal": "handoff"},
    {"id": "cut_on_emotion", "en": "cut on the reaction", "fa": "برش روی واکنش",
     "when": "the room reacts — applause, laughter, a roar", "signal": "emotion"},
    {"id": "multicam", "en": "switch between camera angles", "fa": "سوئیچ بین زاویه‌های دوربین",
     "when": "more than one camera recorded the same moment", "signal": "angles"},
    {"id": "providers", "en": "let an installed provider help", "fa": "کمک‌گرفتن از افزونه‌ی نصب‌شده",
     "when": "the user plugged a provider in", "signal": "providers"},
    {"id": "text_based_edit", "en": "edit by deleting transcript words", "fa": "ویرایش با حذف کلمه‌های رونوشت",
     "when": "there is a transcript to treat as the timeline", "signal": "speech"},
    {"id": "jump_cut", "en": "one-click jump cut of fillers and dead air", "fa": "جامپ‌کات یک‌کلیکی تپق‌ها و سکوت‌ها",
     "when": "unscripted talk with pauses worth tightening", "signal": "speech"},
    {"id": "hook_lab", "en": "score and rebuild the first seconds", "fa": "امتیاز و بازسازی ثانیه‌های اول",
     "when": "short-form; the opening decides the watch", "signal": "action"},
    {"id": "batch_clips", "en": "one file into a board of ranked clips", "fa": "یک فایل به تخته‌ی کلیپ‌های رتبه‌بندی‌شده",
     "when": "long footage holds several shorts", "signal": "duration"},
    {"id": "export_pack", "en": "ship a full deliverable folder", "fa": "خروجی یک بسته‌ی کامل انتشار",
     "when": "always — a publish is a package, not a lone file", "signal": "always"},
    {"id": "sports_markers", "en": "mark spikes and reps on the lane", "fa": "نشانکردن اسپایک و تکرار روی خط",
     "when": "sport or gym footage with action peaks", "signal": "action"},
    {"id": "agent_tools", "en": "expose the brain as an agent protocol", "fa": "برنامه‌ریز به‌صورت پروتکل ایجنت",
     "when": "an external agent should drive the real timeline", "signal": "always"},
]



def assess(template: dict, footage: dict, intent: dict | None = None) -> list[dict]:
    """One use/skip decision per tool, with a human-readable reason.

    `template` carries the reference's measured grammar; `footage` carries the
    user's material signals; `intent` is what the user said the video is for.
    """
    intent = intent or {}
    speech = float(footage.get("speech_ratio", template.get("speech_ratio", 0)) or 0)
    bpm = float(template.get("bpm", 0) or 0)
    shots = len(template.get("shots", []) or [])
    action = float(footage.get("action", 0) or 0)
    presence = float(footage.get("presence", 0) or 0)
    motion = float(footage.get("motion", 0) or 0)  # peak optical-flow magnitude (RIFE worth it?)
    duration = float(footage.get("duration", template.get("duration", 0)) or 0)
    lang = (intent.get("language") or template.get("language") or "").lower()
    persian = lang.startswith("fa") or lang.startswith("per")
    handoff = bool(intent.get("finish_elsewhere") or intent.get("handoff"))
    kind = intent.get("kind", "")
    sport = kind in ("sport", "gaming") or action > 0.5
    # Measured reaction of the room (crowd/laughter cues, `core/engine/emotion.py`).
    emotion = float(footage.get("emotion", 0) or 0)
    # How many camera angles recorded this material — 0 or 1 means there is nothing
    # to switch between, and the switcher must not be offered.
    angles = int(footage.get("angles", 0) or 0)
    # Providers the user installed and enabled, as [{id, capabilities}] — never
    # a guess about what is on their machine.
    plugged = footage.get("providers") or []

    def d(tool: str, use: bool, reason_en: str, reason_fa: str) -> dict:
        t = next(x for x in TOOLS if x["id"] == tool)
        return {"tool": tool, "en": t["en"], "fa": t["fa"],
                "use": use, "reasonEn": reason_en, "reasonFa": reason_fa}

    out = [
        d("beat_cuts", bpm >= 60,
          f"tempo {bpm:.0f} BPM is clear" if bpm >= 60 else "no clear tempo",
          f"تمپو {bpm:.0f} واضح است" if bpm >= 60 else "ضرب واضحی نیست"),
        d("slowmo", sport and action > 0.4,
          "sharp action peaks to linger on" if sport and action > 0.4 else "no action peak to linger on",
          "لحظه‌ی اوج تیز برای مکث هست" if sport and action > 0.4 else "اوجی برای مکث نیست"),
        d("captions", speech > 0.2,
          f"{speech:.0%} of the footage is speech" if speech > 0.2 else "little speech",
          f"{speech:.0%} از فوتیج گفتار است" if speech > 0.2 else "گفتار کم است"),
        d("persian_norm", speech > 0.2 and persian,
          "Persian captions need ZWNJ + Yeh/Kaf clean-up" if speech > 0.2 and persian
          else "not Persian captions (or no speech)",
          "زیرنویس فارسی نیم‌فاصله و یکسان‌سازی ی/ک می‌خواهد" if speech > 0.2 and persian
          else "زیرنویس فارسی نیست (یا گفتاری نیست)"),
        d("karaoke", speech > 0.2 and bpm >= 60,
          "captions plus a beat suits word-by-word" if speech > 0.2 and bpm >= 60 else "needs captions plus a beat",
          "زیرنویس به‌همراه ضرب برای کارائوکه لازم است"),
        d("fillers", speech > 0.3 and kind in ("talking_head", "vlog", "tutorial", "podcast"),
          "unscripted talk is full of uhs/ums to trim" if speech > 0.3 else "not unscripted talk",
          "گفتار بداهه پر از «اِم/اِه» است که باید چیده شود" if speech > 0.3 else "گفتار بداهه نیست"),
        d("ducking", speech > 0.2,
          "voice over music needs room" if speech > 0.2 else "no voice to protect",
          "صدا روی موسیقی جا می‌خواهد" if speech > 0.2 else "صدایی برای محافظت نیست"),
        d("reframe", presence > 0.3 or sport,
          "the subject moves; follow it" if presence > 0.3 or sport else "subject is steady",
          "سوژه حرکت می‌کند؛ دنبالش می‌روم" if presence > 0.3 or sport else "سوژه ثابت است"),
        d("grade", True, "colour matching is free and always helps", "هم‌رنگی همیشه کمک می‌کند"),
        d("transitions", shots > 1,
          f"{shots} shots to join" if shots > 1 else "a single shot needs no joins",
          f"{shots} نما برای پیوند" if shots > 1 else "تک‌نما پیوند نمی‌خواهد"),
        d("motion_transition", shots > 1 and motion > 0.3,
          f"motion {motion:.2f} at junctions — interpolated dissolves read as one move"
          if shots > 1 and motion > 0.3 else "junctions too calm for interpolated transitions",
          f"حرکت {motion:.2f} سرِ اتصال‌ها — دیزالوِ اینترپوله یک حرکت پیوسته می‌شود"
          if shots > 1 and motion > 0.3 else "اتصال‌ها آرام‌تر از ترنزیشن اینترپوله‌اند"),
        d("hook_first", sport or kind in ("vlog", "product"),
          "short-form lives or dies in the first seconds" if sport else "not short-form",
          "فرم کوتاه در ثانیه‌های اول جان می‌گیرد" if sport else "فرم کوتاه نیست"),
        d("denoise", any("audio.denoise" in (p.get("capabilities") or []) for p in plugged),
          "a provider offers denoising — the noise floor itself is still unmeasured"
          if any("audio.denoise" in (p.get("capabilities") or []) for p in plugged)
          else "noise floor not measured and no denoiser installed",
          "یک افزونه نویزگیری می‌دهد — خودِ کف نویز هنوز سنجیده نشده"
          if any("audio.denoise" in (p.get("capabilities") or []) for p in plugged)
          else "کف نویز سنجیده نشده و نویزگیری نصب نیست"),
        d("interchange", handoff,
          "handing off to a pro NLE — export OTIO" if handoff else "finishing here; no handoff asked",
          "تحویل به ان‌ال‌ای حرفه‌ای — خروجی OTIO" if handoff else "همین‌جا تمام می‌شود؛ تحویلی خواسته نشده"),
        d("cut_on_emotion", emotion >= 0.15,
          f"the room reacts (measured reaction {emotion:.2f})" if emotion >= 0.15
          else "no measured reaction to cut on",
          f"جمعیت واکنش نشان می‌دهد (واکنش سنجیده‌شده {emotion:.2f})" if emotion >= 0.15
          else "واکنش سنجیده‌شده‌ای برای برش نیست"),
        d("multicam", angles >= 2,
          f"{angles} angles to line up and switch" if angles >= 2
          else "one camera — nothing to switch to",
          f"{angles} زاویه برای هم‌ترازی و سوئیچ" if angles >= 2
          else "یک دوربین — زاویه‌ی دومی برای سوئیچ نیست"),
        d("providers", bool(plugged),
          "installed: " + ", ".join(str(p.get("id")) for p in plugged[:3]) if plugged
          else "no provider installed (~/CuttingEdge/providers)",
          "نصب‌شده: " + "، ".join(str(p.get("id")) for p in plugged[:3]) if plugged
          else "افزونه‌ای نصب نیست (~/CuttingEdge/providers)"),
        d("text_based_edit", speech > 0.2,
          f"{speech:.0%} speech — a transcript exists to edit as the timeline" if speech > 0.2
          else "no transcript to edit",
          f"{speech:.0%} گفتار — رونوشتی هست که بشود مثل تایم‌لاین ویرایشش کرد" if speech > 0.2
          else "رونوشتی برای ویرایش نیست"),
        d("jump_cut", speech > 0.3,
          "talk has fillers and dead air to tighten" if speech > 0.3 else "little talk to tighten",
          "گفتار تپق و سکوت مرده برای فشرده‌شدن دارد" if speech > 0.3 else "گفتار کمی برای فشرده‌شدن"),
        d("hook_lab", sport or kind in ("vlog", "product"),
          "the first seconds decide a short — score and rebuild them" if sport or kind in ("vlog", "product")
          else "not short-form, no hook to lab",
          "ثانیه‌های اول یک شورت را تعیین می‌کنند — بسنج و بازسازی کن" if sport or kind in ("vlog", "product")
          else "فرم کوتاه نیست، هوکی برای آزمایش نیست"),
        d("batch_clips", duration >= 60,
          f"{duration:.0f}s of footage holds several shorts" if duration >= 60
          else "too short to hold more than one clip",
          f"{duration:.0f} ثانیه فوتیج چند شورت در خود دارد" if duration >= 60
          else "کوتاه‌تر از آن که چند کلیپ داشته باشد"),
        d("export_pack", True,
          "a publish is a package: video, captions, thumb, chapters, OTIO",
          "انتشار یک بسته است: ویدیو، زیرنویس، تامبنیل، چپترها، OTIO"),
        d("sports_markers", sport,
          "action peaks to mark as spikes/reps" if sport else "no sport action to mark",
          "اوج‌های حرکت برای نشان‌کردن اسپایک/تکرار" if sport else "حرکت ورزشی برای نشان‌کردن نیست"),
        d("agent_tools", True,
          "the brain's tools are exposed as a protocol an agent can drive",
          "ابزارهای مغز به‌صورت پروتکلی که ایجنت می‌راند در دسترس‌اند"),
    ]
    return out


def notes(assessment: list[dict], lang: str = "fa") -> list[str]:
    """The editor's notes as readable lines, only for the tools that will be used."""
    chosen = [a for a in assessment if a["use"]]
    return [f"{a['fa']} — {a['reasonFa']}" if lang == "fa"
            else f"{a['en']} — {a['reasonEn']}" for a in chosen]
