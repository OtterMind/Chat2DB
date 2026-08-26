"""LLM spelling polish + translation for captions — guarded, local-first.

A local Ollama model is a brilliant proof-reader and a shaky witness: it can
fix «من رفتم ب بازار» into «من رفتم به بازار», but it can also "improve" a
sentence into one that was never said. So the contract here is strict:

* timings never move — only the text of each cue;
* the model must return exactly one line per cue, in order;
* a corrected line is accepted only when it stays close to the original
  (word count within ±2 and character-level similarity ≥ 0.55); anything else
  keeps the recogniser's text — a miss is safer than a fiction.

No Ollama → `provider: None` and the cues pass through untouched.
"""
from __future__ import annotations

import difflib
import json
import re

OLLAMA_URL = "http://127.0.0.1:11434"


def _ask(model: str, prompt: str, timeout: float = 120.0) -> list[str] | None:
    try:
        import requests  # noqa: PLC0415

        response = requests.post(
            f"{OLLAMA_URL}/api/generate",
            json={"model": model, "prompt": prompt, "stream": False, "format": "json"},
            timeout=timeout,
        )
        raw = response.json().get("response", "")
        data = json.loads(raw)
        lines = data.get("lines")
        return [str(x) for x in lines] if isinstance(lines, list) else None
    except Exception:  # noqa: BLE001 — a dead model is a normal state
        return None


def _close(original: str, fixed: str) -> bool:
    a, b = original.split(), fixed.split()
    if abs(len(a) - len(b)) > 2:
        return False
    ratio = difflib.SequenceMatcher(None, original, fixed).ratio()
    return ratio >= 0.55


def _numbered(cues: list[dict]) -> str:
    return "\n".join(f"{i + 1}. {c.get('text', '')}" for i, c in enumerate(cues))


def refine_cues(cues: list[dict], model: str | None = None) -> dict:
    """Fix spelling/grammar/punctuation without touching timings."""
    from core.brain.planners import ollama_available  # noqa: PLC0415

    chosen = ollama_available(model)
    if chosen is None or not cues:
        return {"cues": cues, "changed": 0, "provider": None}
    prompt = (
        "You are a caption proof-reader. Fix spelling, grammar and punctuation "
        "of each line. Do not reword, do not translate, do not add or remove "
        "facts. Reply with JSON {\"lines\": [one corrected string per input "
        "line, same order, same count]}.\n" + _numbered(cues)
    )
    lines = _ask(chosen, prompt)
    if lines is None or len(lines) != len(cues):
        return {"cues": cues, "changed": 0, "provider": chosen}
    out, changed = [], 0
    for cue, fixed in zip(cues, lines):
        fixed = re.sub(r"\s+", " ", fixed).strip()
        if fixed and fixed != cue.get("text", "") and _close(cue.get("text", ""), fixed):
            out.append({**cue, "text": fixed})
            changed += 1
        else:
            out.append(cue)
    return {"cues": out, "changed": changed, "provider": chosen}


def translate_cues(cues: list[dict], target: str, model: str | None = None) -> dict:
    """Translate cue text; timings and count are immutable by construction."""
    from core.brain.planners import ollama_available  # noqa: PLC0415

    chosen = ollama_available(model)
    if chosen is None or not cues:
        return {"cues": cues, "provider": None}
    prompt = (
        f"Translate each caption line to {target}. Keep it short and spoken, "
        "one line per input line. Reply with JSON {\"lines\": [...]}.\n"
        + _numbered(cues)
    )
    lines = _ask(chosen, prompt)
    if lines is None or len(lines) != len(cues):
        return {"cues": cues, "provider": chosen}
    out = [{**c, "text": re.sub(r"\s+", " ", t).strip() or c.get("text", "")}
           for c, t in zip(cues, lines)]
    return {"cues": out, "provider": chosen}


def hook_title(cues: list[dict], model: str | None = None) -> dict:
    """One short hook title for the opening seconds — guarded.

    The model may only style what the transcript already says: the fallback is
    the first cue's own words, and an over-long or hallucinated answer (longer
    than 14 words / 80 chars, or empty) is thrown away.
    """
    from core.brain.planners import ollama_available  # noqa: PLC0415

    fallback = re.sub(r"\s+", " ", cues[0].get("text", "")).strip()[:60] if cues else ""
    body = " ".join(c.get("text", "") for c in cues[:6]).strip()
    chosen = ollama_available(model)
    if chosen is None or not body:
        return {"title": fallback, "provider": None}
    prompt = (
        "Write ONE hook title of at most 8 words for a short video whose opening "
        f"lines are: {body[:400]}. Do not invent facts. "
        'Reply with JSON {"title": "..."}'
    )
    try:
        import json as _json  # noqa: PLC0415
        import requests  # noqa: PLC0415

        response = requests.post(
            f"{OLLAMA_URL}/api/generate",
            json={"model": chosen, "prompt": prompt, "stream": False, "format": "json"},
            timeout=60,
        )
        data = _json.loads(response.json().get("response", "{}"))
        title = str(data.get("title", "")).strip()
    except Exception:  # noqa: BLE001
        title = None
    if not title or len(title) > 80 or len(title.split()) > 14:
        return {"title": fallback, "provider": chosen}
    return {"title": title, "provider": chosen}
