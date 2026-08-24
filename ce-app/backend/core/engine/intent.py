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
    "talking_head": {"speech": 1.00, "motion": 0.25, "onset": 0.60, "edge": 0.20},
    "interview":    {"speech": 1.00, "motion": 0.20, "onset": 0.50, "edge": 0.25},
    "tutorial":     {"speech": 0.80, "motion": 0.55, "onset": 0.50, "edge": 0.40},
    "vlog":         {"speech": 0.60, "motion": 0.70, "onset": 0.50, "edge": 0.55},
    "product":      {"speech": 0.50, "motion": 0.80, "onset": 0.40, "edge": 0.50},
    "gaming":       {"speech": 0.40, "motion": 0.90, "onset": 0.80, "edge": 0.60},
    "montage":      {"speech": 0.10, "motion": 1.00, "onset": 0.80, "edge": 0.80},
    "travel":       {"speech": 0.10, "motion": 0.90, "onset": 0.40, "edge": 0.70},
    "sport":        {"speech": 0.20, "motion": 0.90, "onset": 0.80, "edge": 0.70},
    "event":        {"speech": 0.60, "motion": 0.60, "onset": 0.60, "edge": 0.50},
}

#: Neutral, for an unanswered question — no signal is favoured.
NEUTRAL: dict[str, float] = {"speech": 0.50, "motion": 0.50, "onset": 0.50, "edge": 0.40}

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
    language: str = ""
    #: Words or phrases that must survive the cut, if they were said.
    keep: list[str] = field(default_factory=list)
    #: Words or phrases that should not carry a clip.
    avoid: list[str] = field(default_factory=list)
    #: The length the finished edit should have, in seconds. 0 = the reference's.
    seconds: float = 0.0
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
            language=one(raw.get("language"), {o["id"]: o for o in OPTIONS["language"]}),
            keep=words(raw.get("keep")),
            avoid=words(raw.get("avoid")),
            # A target length below one second, or longer than an hour, is a
            # typo rather than a wish — ignore it instead of building to it.
            seconds=round(seconds, 3) if 1.0 <= seconds <= 3600.0 else 0.0,
            notes=str(raw.get("notes") or "").strip()[:500],
        )

    def as_dict(self) -> dict:
        return asdict(self)

    @property
    def empty(self) -> bool:
        """No answer at all: behave exactly as before this existed."""
        return not any((self.kind, self.goal, self.focus, self.energy,
                        self.keep, self.avoid, self.seconds))

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
        for term, factor in (GOALS.get(self.goal) or {}).items():
            multipliers[term] = factor
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

    def describe(self, translate=None) -> list[str]:
        """What these answers changed, in words the user can read.

        An answer whose effect is invisible is an answer the user will not trust
        twice, so the rebuild reports each one it actually used.
        """
        said = translate or (lambda en, fa: en)
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
