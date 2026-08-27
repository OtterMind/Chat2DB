"""The brain asks itself — the intake questionnaire, answered by measurement.

The Style Match screen used to ask the user ten questions (what the video is,
what it is for, who watches it). A frame cannot say those things — but the
*measurements* the app already makes can say most of them, and the owner asked
that the brain interrogate **itself**: once when the reference arrives, once
when the footage arrives, show both interrogations on screen, and then offer a
few genuinely different ways to start the edit.

So every question in `core.engine.intent.OPTIONS` (plus a few the screen never
had) is answered here from a measured signal — tempo, shot length, speech
ratio, action peaks, presence, aspect, hook — with the number attached, so the
user reads *why* the brain thinks the footage is a sport clip, not just that it
does. Nothing is invented: a signal that was not measured is said to be
unmeasured (the noise floor), exactly as the tool-belt brain does.

The output of `edit_options` is a short menu of *different* edits — faithful,
punchy, speech-first, calm — each carrying the intent payload the rebuild
already understands, so choosing one is choosing weights over the same
measurements, never a different pipeline.
"""
from __future__ import annotations

from core.engine import intent as intent_model

OPTIONS = intent_model.OPTIONS


def _label(key: str, option_id: str, lang: str) -> str:
    for option in OPTIONS.get(key, []):
        if option["id"] == option_id:
            return option[lang]
    return option_id


def _qa(qid: str, fa_q: str, en_q: str, key: str | None, answer_id: str | None,
        value: str, fa_why: str, en_why: str,
        fa_a: str | None = None, en_a: str | None = None) -> dict:
    return {
        "id": qid,
        "q": {"fa": fa_q, "en": en_q},
        "a": {"fa": fa_a if fa_a is not None else (_label(key, answer_id, "fa") if key and answer_id else value),
              "en": en_a if en_a is not None else (_label(key, answer_id, "en") if key and answer_id else value)},
        "value": value,
        "why": {"fa": fa_why, "en": en_why},
    }


# ------------------------------------------------------------------ reference


def answer_reference(template: dict) -> list[dict]:
    """The questions the *reference* can answer, from what analyse() measured."""
    bpm = float(template.get("bpm", 0) or 0)
    shots = template.get("shots") or []
    median = float(template.get("median_shot", 0) or 0) or (
        sum(float(s.get("duration", 0)) for s in shots) / len(shots) if shots else 0.0)
    speech = float(template.get("speech_ratio", 0) or 0)
    aspect = str(template.get("aspect", "") or "")
    hook = template.get("hook") or {}
    first_cut = float(hook.get("firstCut", 0) or 0)
    soft = sum(1 for s in shots if str(s.get("transition", "") or "") in ("dissolve", "fade"))
    soft_ratio = soft / len(shots) if shots else 0.0
    length = sum(float(s.get("duration", 0)) for s in shots)

    energy = "punchy" if (bpm >= 110 or 0 < median <= 2) else "calm" if median >= 4 else "balanced"
    platform = "youtube_long" if aspect.startswith("16:9") else "instagram_reels"
    captions = "fa" if speech > 0.2 else "none"

    out = [
        _qa("energy", "ریتم الگو تند است یا آرام؟", "Is the reference's rhythm fast or calm?",
            "energy", energy, f"{bpm:.0f} BPM · نماى میانه {median:.1f}s",
            f"تمپو {bpm:.0f} و نماى میانه {median:.1f} ثانیه این ریتم را می‌گوید.",
            f"tempo {bpm:.0f} BPM and a {median:.1f}s median shot say so."),
        _qa("platform", "الگو برای کدام قاب ساخته شده؟", "Which frame was the reference built for?",
            "platform", platform, aspect or "?",
            f"نسبت تصویر الگو {aspect or 'نامعلوم'} است.",
            f"the reference's aspect is {aspect or 'unknown'}."),
        _qa("captions", "الگو چقدر گفتار/زیرنویس دارد؟", "How much speech does the reference carry?",
            "captions", captions, f"{speech:.0%}",
            f"{speech:.0%} از الگو گفتار است." if speech > 0.2 else "الگو تقریباً بی‌گفتار است.",
            f"{speech:.0%} of the reference is speech." if speech > 0.2 else "the reference is mostly speech-free."),
        _qa("beat", "ضرب موسیقی برای برش هست؟", "Is there a musical beat to cut on?",
            None, None, f"{bpm:.0f} BPM" if bpm >= 60 else "بدون ضرب",
            "tempo واضح است؛ برش روی ضرب معنا دارد." if bpm >= 60 else "ضربی سنجیده نشد.",
            "a clear tempo — cuts can ride the beat." if bpm >= 60 else "no tempo was measured."),
        _qa("hook", "الگو چقدر زود اولین برش را می‌زند؟", "How soon does the reference make its first cut?",
            None, None, f"{first_cut:.1f}s",
            "شروع زیر ۱٫۵ ثانیه یعنی قلاب فوری." if 0 < first_cut <= 1.5 else "شروع نگه‌داشته‌شده.",
            "under 1.5 s means an instant hook." if 0 < first_cut <= 1.5 else "a held opening."),
        _qa("join", "اتصال‌های الگو نرم‌اند یا ضربه‌ای؟", "Are the reference's junctions soft or hard?",
            None, None, f"{soft_ratio:.0%} نرم",
            f"{soft} از {len(shots)} نما با اتصال نرم.",
            f"{soft} of {len(shots)} shots join softly."),
        _qa("len", "خودِ الگو چند ثانیه است؟", "How long is the reference itself?",
            None, None, f"{length:.0f}s",
            "طول الگو، کفِ طول هدف است.",
            "the reference length is the floor for the target length."),
    ]
    return out


# ------------------------------------------------------------------ footage


def measure_footage(path: str) -> dict:
    """The footage's own numbers: length, aspect, speech, action, presence."""
    from core.engine import analyze, compose, style  # noqa: PLC0415

    info = compose.probe_media(path)
    duration = float(info.get("duration", 0) or 0)
    width = int(info.get("width", 0) or 0)
    height = int(info.get("height", 0) or 0)
    aspect = f"{width}x{height}" if width and height else ""

    speech_ratio = 0.0
    if duration > 0:
        try:
            silences = analyze.detect_silence(path)
            silent = sum(max(0.0, r.end - r.start) for r in silences)
            speech_ratio = max(0.0, min(1.0, 1.0 - silent / duration))
        except Exception:  # noqa: BLE001 — a silent-file edge must not kill the brain
            speech_ratio = 0.0
    peak, presence = style._coarse_action(path, duration) if duration > 0 else (0.0, 0.0)
    # How much the room reacts over the whole file — the crowd/laughter cues from
    # `core/engine/emotion.py`. A measurement like the others, and cached with the
    # rest of the audio maths; a file with no audio simply has no reaction.
    reaction = 0.0
    if duration > 0:
        try:
            from core.engine import emotion  # noqa: PLC0415

            cues = emotion.audio_cues(path)
            reaction = float(sum(cues.joy) / len(cues.joy)) if cues.joy else 0.0
        except Exception:  # noqa: BLE001 — no audio is a normal answer
            reaction = 0.0
    return {"duration": duration, "aspect": aspect, "speech_ratio": speech_ratio,
            "action": float(peak), "presence": float(presence), "emotion": round(reaction, 4),
            "vertical": bool(height > width)}


def answer_footage(template: dict, sig: dict) -> list[dict]:
    """The questions the *footage* answers — kind, focus, goal, audience…"""
    speech = float(sig.get("speech_ratio", 0) or 0)
    action = float(sig.get("action", 0) or 0)
    presence = float(sig.get("presence", 0) or 0)
    duration = float(sig.get("duration", 0) or 0)

    if action > 0.5:
        kind = "sport"
    elif speech > 0.5:
        kind = "talking_head"
    elif speech > 0.25:
        kind = "vlog"
    else:
        kind = "montage"
    focus = "action" if action > 0.5 else "face" if speech > 0.5 else "everyone"
    goal = "entertain" if kind in ("sport", "montage") else "teach" if kind == "tutorial" \
        else "hook" if kind == "sport" else "story"
    if kind == "sport":
        goal = "hook"
    audience = "fans" if kind in ("sport", "montage") else "students" if goal == "teach" \
        else "everyone"

    return [
        _qa("kind", "این فوتیج چیست؟", "What is this footage?",
            "kind", kind, f"action {action:.2f} · speech {speech:.0%}",
            f"اوج حرکت {action:.2f} و گفتار {speech:.0%} این جنس را می‌گوید.",
            f"an action peak of {action:.2f} with {speech:.0%} speech says so."),
        _qa("focus", "دوربین باید دنبال چه باشد؟", "What should the camera follow?",
            "focus", focus, f"presence {presence:.0%}",
            "سوژه‌ی متحرک اندازه‌گیری شده است." if presence > 0.3 else "سوژه کم‌جنبش است.",
            "a moving subject was measured." if presence > 0.3 else "the subject is mostly still."),
        _qa("goal", "هدف این ادیت چیست؟", "What is this edit for?",
            "goal", goal, f"{duration:.0f}s footage",
            "جنس فوتیج هدف را پیشنهاد می‌دهد.",
            "the footage's kind suggests the goal."),
        _qa("audience", "تماشاگر کیست؟", "Who is watching?",
            "audience", audience, kind,
            "جنس ویدیو مخاطب را حدس می‌زند.",
            "the kind of video implies the audience."),
        _qa("fspeech", "چقدر از فوتیج گفتار است؟", "How much of the footage is speech?",
            None, None, f"{speech:.0%}",
            "از نقشه‌ی سکوت سنجیده شد.",
            "measured from the silence map."),
        _qa("faction", "حرکت فوتیج انفجاری است یا پیوسته؟", "Is the footage's motion bursty or steady?",
            None, None, f"peak {action:.2f}",
            "اوج تیز یعنی لحظه‌ی اسلومو هست." if action > 0.4 else "اوج تیزی نیست.",
            "a sharp peak means a slow-mo moment exists." if action > 0.4 else "no sharp peak."),
        _qa("faspect", "قاب فوتیج چیست؟", "What frame is the footage?",
            None, None, sig.get("aspect") or "?",
            "قاب عمودی یعنی فرم کوتاه." if sig.get("vertical") else "قاب افقی.",
            "vertical means short-form." if sig.get("vertical") else "a landscape frame."),
        _qa("fnoise", "کف نویز صدا بلند است؟", "Is the noise floor high?",
            None, None, "سنجیده نشده",
            "هنوز سنجه‌ی نویز نداریم؛ صادقانه می‌گویم نمی‌دانم.",
            "no noise measurement yet; honestly, I don't know."),
    ]


# ------------------------------------------------------------------ the menu


def edit_options(template: dict, sig: dict | None) -> list[dict]:
    """A few genuinely different ways to start, each with its intent payload."""
    sig = sig or {}
    bpm = float(template.get("bpm", 0) or 0)
    median = float(template.get("median_shot", 0) or 0) or 2.5
    speech = float(sig.get("speech_ratio", template.get("speech_ratio", 0) or 0) or 0)
    action = float(sig.get("action", 0) or 0)
    vertical = bool(sig.get("vertical", str(template.get("aspect", "")).startswith("9:16")))
    ref_len = sum(float(s.get("duration", 0)) for s in (template.get("shots") or []))
    short = 30 if vertical else 60

    def shots_in(seconds: float, mult: float) -> int:
        return max(1, round(seconds / max(0.5, median * mult)))

    sport = action > 0.5
    options = [
        {"id": "faithful",
         "title": {"fa": "وفادار به الگو", "en": "Faithful to the reference"},
         "intent": {"energy": "balanced", "goal": "story"},
         "traits": {"fa": [f"حدود {shots_in(ref_len or short, 1.0)} نما", "هم‌رنگ با الگو",
                           "زیرنویس اگر گفتار هست"],
                    "en": [f"about {shots_in(ref_len or short, 1.0)} shots", "graded like the reference",
                           "captions where there is speech"]},
         "why": {"fa": "ریتم و رنگ الگو، بدون ریسک.", "en": "the reference's rhythm and colour, no risk."}},
        {"id": "punchy",
         "title": {"fa": "کوتاه و کوبنده", "en": "Short & punchy"},
         "intent": {"energy": "punchy", "goal": "hook", "seconds": short,
                    "platform": "tiktok" if vertical else "youtube_shorts"},
         "traits": {"fa": [f"{short} ثانیه", f"حدود {shots_in(short, 0.75)} برش تند",
                           "برش روی ضرب" if bpm >= 60 else "بدون ضرب"],
                    "en": [f"{short} seconds", f"about {shots_in(short, 0.75)} fast cuts",
                           "cut on the beat" if bpm >= 60 else "no beat"]},
         "why": {"fa": "فرم کوتاه با قلاب فوری.", "en": "short-form with an instant hook."}},
        {"id": "speech" if speech > 0.2 else "motion",
         "title": {"fa": "روایت گفتارمحور", "en": "Speech-first story"} if speech > 0.2
                 else {"fa": "مونتاژ حرکت", "en": "Motion montage"},
         "intent": ({"goal": "teach", "captions": "fa", "energy": "balanced"}
                    if speech > 0.2 else {"goal": "entertain", "energy": "punchy"}),
         "traits": {"fa": (["جمله‌ها نیمه‌نماند", "زیرنویس فارسی", "اسلوموی اوج" if sport else "ریتم متوازن"]
                           if speech > 0.2 else ["حرکت محور", "برش‌های پرانرژی"]),
                    "en": (["no sentence cut mid-way", "Persian captions",
                            "slow-mo the peak" if sport else "balanced rhythm"]
                           if speech > 0.2 else ["motion-led", "energetic cuts"])},
         "why": {"fa": f"گفتار {speech:.0%} — حرف، محور است." if speech > 0.2
                      else "گفتار کم — حرکت محور است.",
                 "en": f"{speech:.0%} speech — the words lead." if speech > 0.2
                       else "little speech — motion leads."}},
        {"id": "calm",
         "title": {"fa": "آرام و سینمایی", "en": "Calm & cinematic"},
         "intent": {"energy": "calm", "goal": "story", "platform": "youtube_long"},
         "traits": {"fa": [f"حدود {shots_in(max(ref_len, short), 1.35)} نمای بلند", "اتصال‌های نرم"],
                    "en": [f"about {shots_in(max(ref_len, short), 1.35)} long shots", "soft junctions"]},
         "why": {"fa": "برای تماشای عمدی، نه اسکرول.", "en": "for deliberate watching, not scrolling."}},
    ]
    return options
