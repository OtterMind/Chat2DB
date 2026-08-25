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
"""
from __future__ import annotations

#: The tool inventory. `when` is the human rule; `signal` names the measured
#: quantity that decides it. This is the "I know my tools" part.
TOOLS: list[dict] = [
    {"id": "beat_cuts", "en": "cut on the beat", "fa": "برش روی ضرب",
     "when": "there is music with a clear tempo", "signal": "bpm"},
    {"id": "slowmo", "en": "slow-mo the peak", "fa": "اسلوموی لحظه‌ی اوج",
     "when": "there is a sharp action peak (sport)", "signal": "action"},
    {"id": "captions", "en": "burn captions", "fa": "زیرنویس",
     "when": "someone talks", "signal": "speech"},
    {"id": "karaoke", "en": "word-by-word highlight", "fa": "هایلایت کلمه‌به‌کلمه",
     "when": "captions plus music, short-form", "signal": "speech"},
    {"id": "ducking", "en": "duck music under the voice", "fa": "پایین آوردن موسیقی زیر صدا",
     "when": "voice and music together", "signal": "speech"},
    {"id": "reframe", "en": "follow the subject", "fa": "دنبال‌کردن سوژه",
     "when": "the subject moves or the aspect changes", "signal": "presence"},
    {"id": "grade", "en": "match the reference look", "fa": "هم‌رنگ‌کردن با الگو",
     "when": "always — colour is free", "signal": "always"},
    {"id": "transitions", "en": "transitions at junctions", "fa": "ترنزیشن سرِ اتصال‌ها",
     "when": "more than one shot", "signal": "shots"},
    {"id": "hook_first", "en": "put the strongest moment first", "fa": "قوی‌ترین لحظه اول",
     "when": "short-form; you have seconds to hook", "signal": "action"},
    {"id": "denoise", "en": "clean the audio", "fa": "تمیزکردن صدا",
     "when": "the noise floor is high", "signal": "noise"},
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
    kind = intent.get("kind", "")
    sport = kind in ("sport", "gaming") or action > 0.5

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
        d("karaoke", speech > 0.2 and bpm >= 60,
          "captions plus a beat suits word-by-word" if speech > 0.2 and bpm >= 60 else "needs captions plus a beat",
          "زیرنویس به‌همراه ضرب برای کارائوکه لازم است" ),
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
        d("hook_first", sport or kind in ("vlog", "product"),
          "short-form lives or dies in the first seconds" if sport else "not short-form",
          "فرم کوتاه در ثانیه‌های اول جان می‌گیرد" if sport else "فرم کوتاه نیست"),
        d("denoise", False, "noise floor not measured yet", "کف نویز هنوز سنجیده نشده"),
    ]
    return out


def notes(assessment: list[dict], lang: str = "fa") -> list[str]:
    """The editor's notes as readable lines, only for the tools that will be used."""
    chosen = [a for a in assessment if a["use"]]
    return [f"{a['fa']} — {a['reasonFa']}" if lang == "fa"
            else f"{a['en']} — {a['reasonEn']}" for a in chosen]
