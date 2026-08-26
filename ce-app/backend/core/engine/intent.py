"""What the video is *for* — the one question a frame can never answer.

Everything `style.analyse()` measures is a property of the **reference**: its
rhythm, its colour, where its cuts fall, how loud its music is. Nothing in a
frame says whether the user's own footage is a lesson, a product, a wedding or
a rant, and nothing says what "the best moment" means for it.

That gap was measured, not argued about. On 120 s of footage against a 12 s
reference the rebuild touched **17.3 s of the material — 14.4 %** — and the
candidate moments it ranked apart by **0.002** on a 0..1 scale. When a scorer
has no opinion, "best" quietly becomes "earliest", because the sort is stable.
The user's words for the result were "it shortens the first video" and "the
highlight detection is very weak", and both were the same missing input.

This module is that input, as data. It does not measure anything and it invents
nothing: it turns a handful of answers into **weights over signals that are
still measured elsewhere** — speech ranges from silence detection, motion energy
from decoded frames, audio activity from the envelope, shot edges from scene
detection, meaning from the transcript. An answer moves the balance between
measurements; it never replaces one.

Every field is optional and every default is neutral, so a user who answers
nothing gets exactly the behaviour they had before.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field

# ------------------------------------------------------------------ the choices
#
# Each entry is a weight over the four measured signals a candidate moment is
# scored on (see `style._highlights`): how much of the window is speech, how
# much the picture moves, how active the audio is, and how close the window sits
# to a shot change in the user's own footage. The numbers are a starting
# opinion, and they are visible to the user in the result summary — an answer
# that changes nothing is worse than no answer at all.

KINDS: dict[str, dict[str, float]] = {
    # `action` = how burst-like the movement is (a spike, a jump, a rep) rather
    # than how much the frame changes overall (a pan also changes a lot).
    # `presence` = the share of the window where a subject is actually moving in
    # frame, so an empty court or a rest between sets ranks low.
    "talking_head": {"speech": 1.00, "motion": 0.25, "onset": 0.60, "edge": 0.20, "action": 0.10, "presence": 0.30},
    "interview":    {"speech": 1.00, "motion": 0.20, "onset": 0.50, "edge": 0.25, "action": 0.10, "presence": 0.30},
    "tutorial":     {"speech": 0.80, "motion": 0.55, "onset": 0.50, "edge": 0.40, "action": 0.30, "presence": 0.50},
    "vlog":         {"speech": 0.60, "motion": 0.70, "onset": 0.50, "edge": 0.55, "action": 0.40, "presence": 0.50},
    "product":      {"speech": 0.50, "motion": 0.80, "onset": 0.40, "edge": 0.50, "action": 0.40, "presence": 0.50},
    "gaming":       {"speech": 0.40, "motion": 0.90, "onset": 0.80, "edge": 0.60, "action": 0.80, "presence": 0.60},
    "montage":      {"speech": 0.10, "motion": 1.00, "onset": 0.80, "edge": 0.80, "action": 0.80, "presence": 0.50},
    "travel":       {"speech": 0.10, "motion": 0.90, "onset": 0.40, "edge": 0.70, "action": 0.40, "presence": 0.40},
    "sport":        {"speech": 0.20, "motion": 0.90, "onset": 0.80, "edge": 0.70, "action": 1.00, "presence": 0.90},
    "event":        {"speech": 0.60, "motion": 0.60, "onset": 0.60, "edge": 0.50, "action": 0.40, "presence": 0.50},
}

#: Neutral, for an unanswered question — no signal is favoured.
NEUTRAL: dict[str, float] = {"speech": 0.50, "motion": 0.50, "onset": 0.50, "edge": 0.40,
                           "action": 0.30, "presence": 0.30}

#: What the user is *trying to do* → multipliers on `core.brain.objective.WEIGHTS`.
#: These change the judge, not the measurements: a lesson that cuts mid-sentence
#: is worse than a montage that does, so `teach` pays more for speech integrity.
GOALS: dict[str, dict[str, float]] = {
    "hook":      {"highlight_strength": 1.5, "speech_integrity": 1.2},
    "story":     {"variety": 1.3, "shot_length_match": 1.2, "duration_fit": 1.1},
    "teach":     {"speech_integrity": 1.6, "shot_length_match": 1.3},
    "sell":      {"highlight_strength": 1.4, "duration_fit": 1.2},
    "entertain": {"on_beat": 1.6, "variety": 1.2},
    "document":  {"speech_integrity": 1.2, "silence_avoided": 1.2},
}

#: What the camera should be pointing at → further multipliers on the signals.
FOCUS: dict[str, dict[str, float]] = {
    "face":    {"speech": 1.20, "motion": 0.70},
    "hands":   {"motion": 1.10, "speech": 0.90},
    "screen":  {"motion": 1.30, "onset": 1.20, "speech": 0.50},
    "product": {"motion": 1.20, "speech": 0.80},
    "scenery": {"motion": 1.10, "speech": 0.20},
    "action":  {"motion": 1.30, "onset": 1.20},
    "everyone": {},
}

#: Rhythm → a shot-length multiplier and an on-beat multiplier.
ENERGY: dict[str, tuple[float, float]] = {
    "calm": (1.35, 0.6),
    "balanced": (1.0, 1.0),
    "punchy": (0.75, 1.4),
}

#: Where it will be watched → multipliers on the judge.
#: A platform is a set of viewing conditions, and those conditions are
#: measurable proxies: short vertical feeds are watched with the sound off and
#: the thumb moving, so rhythm and on-beat cuts matter more; a long horizontal
#: video is watched deliberately, so cutting through a sentence matters more.
PLATFORMS: dict[str, dict[str, float]] = {
    "instagram_reels": {"on_beat": 1.4, "variety": 1.2},
    "tiktok": {"on_beat": 1.5, "variety": 1.3},
    "youtube_shorts": {"on_beat": 1.4, "variety": 1.2},
    "youtube_long": {"speech_integrity": 1.3, "shot_length_match": 1.2},
    "linkedin": {"speech_integrity": 1.3, "silence_avoided": 1.2},
    "website": {},
}

#: Who is watching → multipliers on the judge.
#: An audience is what "the best moment" is *for*. A customer needs the claim,
#: a student needs the whole sentence, a fan needs the beat.
AUDIENCES: dict[str, dict[str, float]] = {
    "customers": {"highlight_strength": 1.4, "duration_fit": 1.1},
    "students": {"speech_integrity": 1.5, "shot_length_match": 1.2},
    "colleagues": {"speech_integrity": 1.2, "silence_avoided": 1.2},
    "fans": {"on_beat": 1.3, "variety": 1.3},
    "everyone": {},
}

#: What the subtitles should be. `captions.wanted` and the style come from the
#: reference by default; this is the owner overruling them, which is legitimate —
#: the reference's language is not necessarily the audience's.
CAPTION_CHOICES: dict[str, dict] = {
    "fa":        {"language": "fa",   "wanted": True},
    "en":        {"language": "en",   "wanted": True},
    "both":      {"language": "both", "wanted": True},
    "none":      {"language": "",     "wanted": False},
    "reference": {},   # whatever the reference implied
}

#: What must not appear.
#:
#: Each entry says plainly whether this build can check it, and with what. The
#: marker lists are deliberately short and visible: a restriction that silently
#: checks nothing is worse than one that says "not yet", because the user will
#: believe the tick. Anything that needs a pass which is not built (identity,
#: OCR) is reported in `cannot_honour()` rather than accepted.
RESTRICTIONS: dict[str, dict] = {
    "no_swearing": {
        "markers": ("fuck", "shit", "bitch", "asshole", "کیر", "کون", "جنده", "خار", "لعنتی"),
    },
    "no_politics": {
        "markers": ("election", "parliament", "sanction", "regime", "انتخابات", "مجلس",
                    "تحریم", "حکومت", "رئیس‌جمهور", "ریس جمهور"),
    },
    "no_brands": {
        "why": "brand names need an entity pass that is not built; name them "
               "yourself in the avoid field and they will be screened",
    },
    "no_other_people": {"why": "faces are tracked, identities are not"},
    "no_on_screen_text": {"why": "the OCR pass is not built yet"},
}

#: What the soundtrack should be. This decides whether the reference's own bed is
#: used at all — the owner's file, the owner's call (§4.55).
MUSIC_CHOICES: dict[str, str] = {
    "reference": "reference",   # use the template's bed when it kept one
    "mine": "mine",             # only a track the user brings
    "none": "none",             # no music under the voice at all
}

#: The choices, with both labels, so the screen can render them without hard-coding.
OPTIONS: dict[str, list[dict]] = {
    "kind": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("talking_head", "Talking to camera", "صحبت رو به دوربین"),
            ("vlog", "Vlog / day in the life", "ولاگ / روزمره"),
            ("tutorial", "Tutorial / how-to", "آموزشی"),
            ("product", "Product or service", "معرفی محصول یا خدمت"),
            ("interview", "Interview", "مصاحبه"),
            ("gaming", "Gameplay", "گیم‌پلی"),
            ("montage", "Montage / music clip", "مونتاژ / کلیپ"),
            ("travel", "Travel / scenery", "سفر / منظره"),
            ("sport", "Sport / action", "ورزشی / اکشن"),
            ("event", "Event / ceremony", "مراسم / رویداد"),
        )
    ],
    "goal": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("hook", "Stop the scroll in 3 seconds", "در ۳ ثانیه اول مخاطب را بگیر"),
            ("story", "Tell a story", "روایت یک داستان"),
            ("teach", "Teach something", "آموزش یک نکته"),
            ("sell", "Sell / call to action", "فروش / دعوت به اقدام"),
            ("entertain", "Entertain on the beat", "سرگرمی روی ریتم"),
            ("document", "Document it as it happened", "ثبت همان‌طور که بود"),
        )
    ],
    "focus": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("face", "The person's face", "چهره‌ی فرد"),
            ("hands", "Hands at work", "دست‌ها در حال کار"),
            ("screen", "A screen / recording", "صفحه / ضبط نمایشگر"),
            ("product", "The product", "محصول"),
            ("scenery", "The place", "مکان و منظره"),
            ("action", "The action", "حرکت و اکشن"),
            ("everyone", "Everything equally", "همه به یک اندازه"),
        )
    ],
    "energy": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("calm", "Calm, longer shots", "آرام، نماهای بلندتر"),
            ("balanced", "Balanced", "متعادل"),
            ("punchy", "Punchy, fast cuts", "تند و کوبنده"),
        )
    ],
    "platform": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("instagram_reels", "Instagram Reels", "ریلز اینستاگرام"),
            ("tiktok", "TikTok", "تیک‌تاک"),
            ("youtube_shorts", "YouTube Shorts", "یوتیوب شورتس"),
            ("youtube_long", "YouTube (long)", "یوتیوب (ویدیوی بلند)"),
            ("linkedin", "LinkedIn", "لینکدین"),
            ("website", "My own site", "سایت خودم"),
        )
    ],
    "audience": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("customers", "Customers", "مشتری‌ها"),
            ("students", "Students / learners", "دانش‌آموز / یادگیرنده"),
            ("colleagues", "Colleagues", "همکاران"),
            ("fans", "Fans / followers", "طرفدارها / دنبال‌کننده‌ها"),
            ("everyone", "Anyone", "همه"),
        )
    ],
    "captions": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("reference", "Whatever the reference had", "هرچه ویدیوی الگو داشت"),
            ("fa", "Persian", "فارسی"),
            ("en", "English", "انگلیسی"),
            ("both", "Both, stacked", "هر دو، روی هم"),
            ("none", "No captions", "بدون زیرنویس"),
        )
    ],
    "restrictions": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("no_swearing", "No swearing", "بدون ناسزا"),
            ("no_politics", "No politics", "بدون سیاست"),
            ("no_brands", "No brand names", "بدون نام برند"),
            ("no_other_people", "No other people's faces", "چهرهٔ افراد دیگر نباشد"),
            ("no_on_screen_text", "No on-screen text", "نوشتهٔ روی تصویر نباشد"),
        )
    ],
    "music": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("reference", "The reference's own track", "آهنگ خودِ ویدیوی الگو"),
            ("mine", "Only a track I bring", "فقط آهنگی که خودم می‌دهم"),
            ("none", "No music", "بدون موسیقی"),
        )
    ],
    "language": [
        {"id": k, "en": en, "fa": fa} for k, en, fa in (
            ("fa", "Persian", "فارسی"),
            ("en", "English", "انگلیسی"),
            ("both", "Both", "هر دو"),
        )
    ],
}


@dataclass
class Intent:
    """The user's answers, normalised. Every field is optional."""

    kind: str = ""
    goal: str = ""
    focus: str = ""
    energy: str = ""
    platform: str = ""
    audience: str = ""
    captions: str = ""
    restrictions: list[str] = field(default_factory=list)
    music: str = ""
    language: str = ""
    #: Words or phrases that must survive the cut, if they were said.
    keep: list[str] = field(default_factory=list)
    #: Words or phrases that should not carry a clip.
    avoid: list[str] = field(default_factory=list)
    #: The length the finished edit should have, in seconds. 0 = the reference's.
    seconds: float = 0.0
    #: Slow the single best moment to half speed, as a highlight beat.
    slowmo: bool = False
    #: Remove filler words (um / یعنی …) from generated captions.
    clean_fillers: bool = False
    #: Anything else, in the user's own words.
    notes: str = ""

    # ---------------------------------------------------------------- parsing

    @classmethod
    def from_dict(cls, raw: dict | None) -> "Intent":
        """Tolerant by design: this comes from a form and from saved projects."""
        raw = raw or {}

        def words(value) -> list[str]:
            if value is None:
                return []
            if isinstance(value, str):
                # Users type a comma-separated list, and a Persian comma is a
                # comma. Both split; an empty entry is not a keyword.
                chunks = value.replace("،", ",").replace("\n", ",").split(",")
                return [c.strip() for c in chunks if c.strip()]
            if isinstance(value, (list, tuple)):
                return [str(v).strip() for v in value if str(v).strip()]
            return []

        def one(value, allowed: dict) -> str:
            text = str(value or "").strip().lower()
            return text if text in allowed else ""

        try:
            seconds = float(raw.get("seconds") or 0.0)
        except (TypeError, ValueError):
            seconds = 0.0

        return cls(
            kind=one(raw.get("kind"), KINDS),
            goal=one(raw.get("goal"), GOALS),
            focus=one(raw.get("focus"), FOCUS),
            energy=one(raw.get("energy"), ENERGY),
            platform=one(raw.get("platform"), PLATFORMS),
            audience=one(raw.get("audience"), AUDIENCES),
            captions=one(raw.get("captions"), CAPTION_CHOICES),
            restrictions=[str(r).strip().lower() for r in (raw.get("restrictions") or [])
                          if str(r).strip().lower() in RESTRICTIONS],
            music=one(raw.get("music"), MUSIC_CHOICES),
            language=one(raw.get("language"), {o["id"]: o for o in OPTIONS["language"]}),
            keep=words(raw.get("keep")),
            avoid=words(raw.get("avoid")),
            # A target length below one second, or longer than an hour, is a
            # typo rather than a wish — ignore it instead of building to it.
            seconds=round(seconds, 3) if 1.0 <= seconds <= 3600.0 else 0.0,
            slowmo=bool(raw.get("slowmo")),
            clean_fillers=bool(raw.get("clean_fillers")),
            notes=str(raw.get("notes") or "").strip()[:500],
        )

    def as_dict(self) -> dict:
        return asdict(self)

    @property
    def empty(self) -> bool:
        """No answer at all: behave exactly as before this existed."""
        return not any((self.kind, self.goal, self.focus, self.energy, self.platform,
                        self.audience, self.captions, self.restrictions, self.music,
                        self.keep, self.avoid, self.seconds, self.slowmo,
                        self.clean_fillers))

    # --------------------------------------------------------------- effects

    def prefers_speech(self) -> bool | None:
        """True/False when the answer settles it, None when the reference should."""
        weight = self.signal_weights().get("speech", 0.5)
        if weight >= 0.6:
            return True
        if weight <= 0.2:
            return False
        return None

    def signal_weights(self) -> dict[str, float]:
        """kind, then focus, then the free-text notes — multiplied, not replaced."""
        weights = dict(KINDS.get(self.kind) or NEUTRAL)
        for signal, factor in (FOCUS.get(self.focus) or {}).items():
            weights[signal] = weights.get(signal, 0.5) * factor
        return {k: round(v, 4) for k, v in weights.items()}

    def weight_multipliers(self) -> dict[str, float]:
        """Multipliers over `objective.WEIGHTS` — the judge, not the measurement."""
        multipliers: dict[str, float] = {}
        # Goal, platform and audience are three views of the same question — what
        # this edit has to achieve — so they multiply rather than overwrite. A
        # lesson for students on TikTok is genuinely different from any one of
        # the three on its own.
        for source in (GOALS.get(self.goal), PLATFORMS.get(self.platform), AUDIENCES.get(self.audience)):
            for term, factor in (source or {}).items():
                multipliers[term] = round(multipliers.get(term, 1.0) * factor, 4)
        if self.energy:
            _, on_beat = ENERGY[self.energy]
            multipliers["on_beat"] = round(multipliers.get("on_beat", 1.0) * on_beat, 4)
        # Keeping a phrase is the user's own definition of a highlight; the
        # judge should pay for it even when the audio is quiet.
        if self.keep:
            multipliers["highlight_strength"] = round(
                multipliers.get("highlight_strength", 1.0) * 1.3, 4
            )
        return multipliers

    def caption_preference(self) -> dict:
        """The subtitle decision, or `{}` to leave the reference's alone.

        A restriction the owner cannot have is not hidden: it comes back in
        `cannot_honour` so the screen can say "not yet, and here is why" instead
        of ticking a box it will not keep.
        """
        choice = CAPTION_CHOICES.get(self.captions) or {}
        return {"wanted": choice["wanted"], "language": choice["language"]} if choice else {}

    def restriction_markers(self) -> list[str]:
        """The words this build *can* screen the transcript for.

        Used as extra `avoid` phrases, so the mechanism is the one that already
        exists and is already tested — a restriction is not a second system.
        """
        out: list[str] = []
        for name in self.restrictions:
            out.extend(RESTRICTIONS.get(name, {}).get("markers", ()))
        return out

    def cannot_honour(self) -> list[str]:
        """Restrictions this build cannot check, with the reason.

        Two of the five need a pass that is not built (identity, OCR). Saying so
        is the difference between a limit and a lie — the same rule §4.46 applies
        to a term that cannot be measured.
        """
        return [
            f"{name} ({RESTRICTIONS[name]['why']})"
            for name in self.restrictions
            if "markers" not in RESTRICTIONS.get(name, {})
        ]

    def shot_length_factor(self) -> float:
        """Calm holds shots longer; punchy cuts faster. 1.0 when unanswered."""
        if self.energy:
            return ENERGY[self.energy][0]
        return 1.0

    def keyword_score(self, text: str) -> float:
        """+1 for a phrase the user asked to keep, −1 for one they asked to drop.

        Case-folded and substring-based on purpose: the transcript comes from
        Whisper and the user types from memory, so anything stricter than this
        would miss both spellings of the same word.
        """
        if not text:
            return 0.0
        haystack = text.casefold()
        score = 0.0
        for phrase in self.keep:
            if phrase.casefold() in haystack:
                score += 1.0
        for phrase in self.avoid:
            if phrase.casefold() in haystack:
                score -= 1.0
        return max(-1.0, min(1.0, score / max(1, len(self.keep) + len(self.avoid))))

    def describe(self, translate=None, language: str = "en") -> list[str]:
        """What these answers changed, in words the user can read.

        An answer whose effect is invisible is an answer the user will not trust
        twice, so the rebuild reports each one it actually used. `language` picks
        the labels; a Persian answer carrying English labels is the bug the user
        reads twice.
        """
        said = translate or (lambda en, fa: fa if language == "fa" else en)
        lines: list[str] = []
        if self.kind:
            label = next(o for o in OPTIONS["kind"] if o["id"] == self.kind)
            lines.append(said(f"video type: {label['en']}", f"نوع ویدیو: {label['fa']}"))
        if self.goal:
            label = next(o for o in OPTIONS["goal"] if o["id"] == self.goal)
            lines.append(said(f"goal: {label['en']}", f"هدف: {label['fa']}"))
        if self.focus:
            label = next(o for o in OPTIONS["focus"] if o["id"] == self.focus)
            lines.append(said(f"focus: {label['en']}", f"تمرکز: {label['fa']}"))
        if self.platform:
            label = next(o for o in OPTIONS["platform"] if o["id"] == self.platform)
            lines.append(said(f"for: {label['en']}", f"برای: {label['fa']}"))
        if self.audience:
            label = next(o for o in OPTIONS["audience"] if o["id"] == self.audience)
            lines.append(said(f"audience: {label['en']}", f"مخاطب: {label['fa']}"))
        if self.captions:
            label = next(o for o in OPTIONS["captions"] if o["id"] == self.captions)
            lines.append(said(f"captions: {label['en']}", f"زیرنویس: {label['fa']}"))
        if self.music:
            label = next(o for o in OPTIONS["music"] if o["id"] == self.music)
            lines.append(said(f"music: {label['en']}", f"موسیقی: {label['fa']}"))
        if self.restrictions:
            labels = [next(o for o in OPTIONS["restrictions"] if o["id"] == r) for r in self.restrictions]
            lines.append(said(
                "must not appear: " + ", ".join(o["en"] for o in labels),
                "نباید دیده شود: " + "، ".join(o["fa"] for o in labels),
            ))
        if self.energy:
            label = next(o for o in OPTIONS["energy"] if o["id"] == self.energy)
            lines.append(said(f"rhythm: {label['en']}", f"ریتم: {label['fa']}"))
        if self.keep:
            lines.append(said(
                f"kept on purpose: {', '.join(self.keep[:5])}",
                f"مواردی که حتماً می‌مانند: {', '.join(self.keep[:5])}",
            ))
        if self.avoid:
            lines.append(said(
                f"avoided: {', '.join(self.avoid[:5])}",
                f"مواردی که حذف می‌شوند: {', '.join(self.avoid[:5])}",
            ))
        if self.seconds:
            lines.append(said(
                f"target length: {self.seconds:g} s",
                f"طول هدف: {self.seconds:g} ثانیه",
            ))
        return lines


def options() -> dict:
    """The questionnaire itself, for the screen to render from one place."""
    return {"options": OPTIONS}
