"""Taste memory: what the user approved or rejected becomes a *prior*, not a fact.

The advisors' ChromaDB long-term-memory idea, rebuilt on the standard library
because a single-user desktop app does not need a vector database to remember
"a few hundred decisions" — a JSON file beside the projects does, with zero new
wheels and zero licence surface. The discipline is the point:

* an approved edit nudges the terms that were strong in it slightly **up**;
* a rejected edit nudges them slightly **down**;
* `prior()` returns multipliers clamped to a tight band, so taste rebalances the
  judge but can never out-vote a measurement, and an empty memory returns 1.0
  everywhere (the memory-less brain is exactly the brain that shipped before).

Signals are stored with the decision; nothing leaves the machine.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

#: How many recent decisions the prior listens to.
WINDOW = 50
#: A single decision may move a term this far; the band stays tight.
STEP = 0.02
MIN_PRIOR, MAX_PRIOR = 0.75, 1.33


def _path() -> Path:
    from app.config import settings  # noqa: PLC0415

    folder = Path(settings.cuttingedge_home)
    folder.mkdir(parents=True, exist_ok=True)
    return folder / "taste-memory.json"


def _load() -> list[dict]:
    try:
        return json.loads(_path().read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001 — a missing or broken memory is an empty memory
        return []


def record(outcome: str, terms: dict | None = None) -> None:
    """Store one decision. `terms` is the winning plan's term breakdown."""
    if outcome not in ("accepted", "rejected"):
        return
    entries = _load()
    entries.append({"t": time.time(), "outcome": outcome,
                    "terms": {k: float(v) for k, v in (terms or {}).items()}})
    _path().write_text(json.dumps(entries[-500:], ensure_ascii=False), encoding="utf-8")


def prior() -> dict[str, float]:
    """Multipliers over objective terms, learned from recent decisions.

    1.0 everywhere when there is no memory: the prior must be silent, not
    opinionated, on a fresh machine.
    """
    entries = _load()[-WINDOW:]
    if not entries:
        return {}
    acc: dict[str, list[float]] = {}
    rej: dict[str, list[float]] = {}
    for entry in entries:
        bucket = acc if entry["outcome"] == "accepted" else rej
        for term, value in (entry.get("terms") or {}).items():
            bucket.setdefault(term, []).append(value)
    out: dict[str, float] = {}
    for term in {t for d in (acc, rej) for t in d}:
        a = sum(acc.get(term, [])) / len(acc[term]) if acc.get(term) else None
        r = sum(rej.get(term, [])) / len(rej[term]) if rej.get(term) else None
        if a is None or r is None:
            continue  # a term needs both outcomes to teach us anything
        delta = (a - r) * STEP * 10
        out[term] = round(max(MIN_PRIOR, min(MAX_PRIOR, 1.0 + delta)), 3)
    return out
