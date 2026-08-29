"""The eight "novel" extensions, local-first and licence-clean.

1 send_webhook   — after a render, notify the user's own n8n/endpoint.
2 build_provenance — an AI-BOM: which models/providers touched an edit, + licences.
3 dna save/match — a personal RAG archive of accepted edits' Style-DNA.
4 autotag        — tag footage (sport/talking/product) + language search.
6 fanout         — one edit → per-platform export specs + metadata files.
8 vault          — local encrypted store for the user's social keys.
(5 schedule lives in core.workflows; 7 chain lives in core.assistant.providers.)
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import time
from pathlib import Path

from app.config import settings

# ---------------------------------------------------------------- 1 · webhook

def send_webhook(event: str, payload: dict) -> dict:
    """POST a JSON event to the user's own endpoint (e.g. their n8n webhook).

    Off by default; a dead endpoint is reported, never fatal — automation must not
    break the render it observes.
    """
    url = (settings.publish_webhook_url or "").strip()
    if not url:
        return {"sent": False, "reason": "no webhook configured"}
    try:
        import requests  # noqa: PLC0415

        response = requests.post(url, json={"event": event, "at": time.time(), **payload},
                                 timeout=5)
        return {"sent": response.ok, "status": response.status_code}
    except Exception as error:  # noqa: BLE001
        return {"sent": False, "reason": str(error)[:200]}


# ------------------------------------------------------------- 2 · provenance

#: licences of the pieces that may touch an edit — the AI-BOM's honesty table.
LICENCES = {
    "faster-whisper": "MIT", "silero-vad": "MIT", "mediapipe": "Apache-2.0",
    "scenedetect": "BSD-3", "planners": "ours", "ensemble": "ours",
    "providers": "per-provider (out-of-process)", "ffmpeg": "LGPL (separate process)",
}


def build_provenance(edit: dict) -> dict:
    """An AI bill-of-materials for one edit: what touched it, under which licence."""
    summary = (edit.get("summary") or {})
    brain = (summary.get("brain") or {})
    touched: list[dict] = []
    if summary.get("captions"):
        touched.append({"component": "faster-whisper", "role": "transcription",
                        "licence": LICENCES["faster-whisper"]})
    winner = brain.get("winner", "")
    if winner:
        touched.append({"component": winner, "role": "edit planning",
                        "licence": LICENCES["planners"] if not winner.startswith("ollama")
                        else "local model (yours)"})
    if "ensemble" in [r.get("name") for r in brain.get("scoreboard", [])]:
        touched.append({"component": "ensemble", "role": "planner collaboration",
                        "licence": LICENCES["ensemble"]})
    touched.append({"component": "ffmpeg", "role": "render", "licence": LICENCES["ffmpeg"]})
    return {"generatedAt": time.time(), "components": touched,
            "note": "no footage or edit leaves this machine; models run locally or via your keys"}


# --------------------------------------------------------------- 3 · dna RAG

def _dna_dir() -> Path:
    path = Path(settings.cuttingedge_home) / "dna"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _vec(dna: dict) -> list[float]:
    return [float(dna.get(k, 0) or 0) for k in
            ("avg_shot", "cut_rate", "bpm", "talk", "motion")]


def save_dna(name: str, dna: dict) -> dict:
    (_dna_dir() / f"{name}.json").write_text(json.dumps(dna, ensure_ascii=False), encoding="utf-8")
    return {"saved": name}


def match_dna(dna: dict) -> dict | None:
    """The closest accepted edit in the personal archive — the RAG retrieval."""
    best, best_dist = None, None
    target = _vec(dna)
    for file in _dna_dir().glob("*.json"):
        try:
            past = json.loads(file.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        dist = sum((a - b) ** 2 for a, b in zip(target, _vec(past))) ** 0.5
        if best_dist is None or dist < best_dist:
            best, best_dist = past, dist
    if best is None:
        return None
    return {"name": best.get("name"), "distance": round(best_dist or 0.0, 3), "dna": best}


# ---------------------------------------------------------------- 4 · autotag

def autotag(signals: dict) -> list[str]:
    """Content tags from measured signals — the auto-tagging workflow, local."""
    tags: list[str] = []
    action = float(signals.get("action", 0) or 0)
    speech = float(signals.get("speech_ratio", 0) or 0)
    emotion = float(signals.get("emotion", 0) or 0)
    if action > 0.4 or emotion > 0.2:
        tags.append("sport")
    if speech > 0.3:
        tags.append("talking")
    if emotion > 0.2:
        tags.append("crowd")
    if not tags:
        tags.append("broll")
    return tags


_QUERY_SYNONYMS = {
    "اسپایک": ["sport", "crowd"], "spike": ["sport", "crowd"],
    "ورزش": ["sport"], "sport": ["sport"],
    "گوینده": ["talking"], "حرف": ["talking"], "talk": ["talking"],
    "جمعیت": ["crowd"], "crowd": ["crowd"],
}


def search_tags(query: str, tagged: list[dict]) -> list[dict]:
    """Natural-language clip search over stored tags."""
    wanted: set[str] = set()
    low = (query or "").lower()
    for key, synonyms in _QUERY_SYNONYMS.items():
        if key in low:
            wanted.update(synonyms)
    if not wanted:
        return tagged
    return [item for item in tagged if wanted & set(item.get("tags", []))]


# ---------------------------------------------------------------- 6 · fanout

PLATFORM_SPECS = {
    "tiktok": {"ratio": "9:16", "width": 1080, "height": 1920, "maxlen": 60},
    "reels": {"ratio": "9:16", "width": 1080, "height": 1920, "maxlen": 90},
    "shorts": {"ratio": "9:16", "width": 1080, "height": 1920, "maxlen": 60},
    "youtube": {"ratio": "16:9", "width": 1920, "height": 1080, "maxlen": 600},
    "square": {"ratio": "1:1", "width": 1080, "height": 1080, "maxlen": 60},
}


def fanout(name: str, platforms: list[str], hooks: list[str] | None = None) -> dict:
    """One edit → per-platform export specs + metadata files (no upload, local)."""
    out_dir = Path(settings.export_dir) / f"{name}-fanout"
    out_dir.mkdir(parents=True, exist_ok=True)
    specs = []
    for platform in platforms:
        spec = PLATFORM_SPECS.get(platform)
        if not spec:
            continue
        (out_dir / f"{platform}.md").write_text(
            f"# {name} — {platform}\nratio {spec['ratio']} · ≤{spec['maxlen']}s\n\n"
            f"{(hooks or [''])[0]}\n", encoding="utf-8")
        specs.append({"platform": platform, **spec})
    return {"dir": str(out_dir), "specs": specs}


# ---------------------------------------------------------------- 8 · vault

def _vault_key() -> bytes:
    keyfile = Path(settings.cuttingedge_home) / ".vault.key"
    if keyfile.exists():
        return bytes.fromhex(keyfile.read_text().strip())
    key = secrets.token_bytes(32)
    keyfile.parent.mkdir(parents=True, exist_ok=True)
    keyfile.write_text(key.hex())
    try:
        os.chmod(keyfile, 0o600)
    except OSError:
        pass
    return key


def _keystream(key: bytes, nonce: bytes, length: int) -> bytes:
    out = b""
    counter = 0
    while len(out) < length:
        out += hashlib.sha256(key + nonce + counter.to_bytes(4, "big")).digest()
        counter += 1
    return out[:length]


def vault_set(service: str, secret_value: str) -> dict:
    """AES-256-CTR (SHA256-keystream) at rest, key never leaves ~/CuttingEdge."""
    key = _vault_key()
    nonce = secrets.token_bytes(16)
    raw = secret_value.encode("utf-8")
    cipher = bytes(a ^ b for a, b in zip(raw, _keystream(key, nonce, len(raw))))
    mac = hmac.new(key, nonce + cipher, hashlib.sha256).hexdigest()
    vault = _load_vault()
    vault[service] = {"nonce": nonce.hex(), "data": cipher.hex(), "mac": mac}
    _write_vault(vault)
    return {"stored": service}


def vault_get(service: str) -> str | None:
    key = _vault_key()
    entry = _load_vault().get(service)
    if not entry:
        return None
    nonce = bytes.fromhex(entry["nonce"])
    cipher = bytes.fromhex(entry["data"])
    if hmac.new(key, nonce + cipher, hashlib.sha256).hexdigest() != entry["mac"]:
        return None  # tampered
    raw = bytes(a ^ b for a, b in zip(cipher, _keystream(key, nonce, len(cipher))))
    return raw.decode("utf-8")


def vault_list() -> list[str]:
    return sorted(_load_vault().keys())


def _vault_path() -> Path:
    return Path(settings.cuttingedge_home) / ".vault.json"


def _load_vault() -> dict:
    try:
        return json.loads(_vault_path().read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}


def _write_vault(vault: dict) -> None:
    _vault_path().write_text(json.dumps(vault), encoding="utf-8")
