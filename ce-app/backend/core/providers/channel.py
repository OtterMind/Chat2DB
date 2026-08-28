"""The provider channel — other people's code, out of our process (advisors' B8).

Some of the best tools for this job can never be imported: `piper` is MIT on
GitHub and **GPL-3.0-or-later on PyPI** (§5b), DeepFilterNet's wheel carries
`NOASSERTION`, and a model like `vit-fer` has no PyPI release whose METADATA
could be read at all. Refusing them costs the user a feature; importing them
costs the app its licence. The third option — the one VS Code, OBS and every
browser took — is a **separate process with a contract**, and that is what this
module is.

A provider is a folder:

    ~/CuttingEdge/providers/<name>/
        provider.json     the manifest — read below
        run.py            anything that speaks the protocol

The protocol is one JSON object per line on stdin, one per line on stdout:

    → {"op": "init", "appVersion": "0.9.37", "capabilities": [...]}
    ← {"ok": true, "capabilities": ["emotion.score"]}
    → {"op": "emotion.score", "payload": {"path": "...", "times": [1.0, 2.5]}}
    ← {"ok": true, "result": {"scores": {"1.0": 0.8}}}
    → {"op": "shutdown"}

Nothing here imports provider code, so a copyleft provider cannot touch the
app's licence — but the manifest still has to *say* what it is, and the licence
is shown next to the name in Settings, because "installed by me" is not the same
as "reviewed by me". Every call is bounded by a timeout, every failure is
recorded and returned as a status line rather than raised, and a provider that
is absent, disabled, slow or lying changes nothing about the edit.
"""
from __future__ import annotations

import json
import subprocess
import sys
import threading
from pathlib import Path

from app import __version__
from app.config import settings

#: What a provider may be asked to do. Each capability is batch-shaped on
#: purpose: one process start per edit, never one per caption.
CAPABILITIES: dict[str, str] = {
    "emotion.score": "score moments 0..1 for emotional strength — {path, times} → {scores}",
    "captions.polish": "clean caption text after the built-in pass — {items} → {items}",
    "audio.denoise": "write a cleaned copy of an audio file — {path, out} → {path}",
    "media.analyse": "extra measured signals for a file — {path} → {signals}",
    "tts.synthesize": "text → spoken audio file (dub/voice-over) — {text, lang} → {path}",
}

#: Copyleft licences are fine *here* — that is the whole point of the separate
#: process — but they are refused for the one runtime that would link them in.
COPYLEFT = ("GPL", "AGPL", "LGPL", "SSPL", "EUPL", "MPL")

#: A call may never hold the editor hostage.
DEFAULT_TIMEOUT = 15.0
INIT_TIMEOUT = 8.0

_lock = threading.Lock()
#: Last known status per provider id, for the Settings card: {"ok": bool, "note": str}
STATUS: dict[str, dict] = {}


def providers_dir() -> Path:
    path = Path(settings.cuttingedge_home) / "providers"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _state_path() -> Path:
    return providers_dir() / "state.json"


def _state() -> dict:
    try:
        return json.loads(_state_path().read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001 — no state file means "everything enabled"
        return {"enabled": {}}


def set_enabled(provider_id: str, enabled: bool) -> dict:
    state = _state()
    state.setdefault("enabled", {})[provider_id] = bool(enabled)
    _state_path().write_text(json.dumps(state, indent=2), encoding="utf-8")
    return {"id": provider_id, "enabled": bool(enabled)}


def is_enabled(provider_id: str) -> bool:
    return bool(_state().get("enabled", {}).get(provider_id, True))


def validate(manifest: dict, folder: Path) -> list[str]:
    """Why a folder is not a provider. Every reason a reviewer would give."""
    problems: list[str] = []
    for key in ("id", "name", "version", "entry", "capabilities"):
        if not manifest.get(key):
            problems.append(f"manifest is missing `{key}`")
    capabilities = manifest.get("capabilities") or []
    if not isinstance(capabilities, list) or not capabilities:
        problems.append("`capabilities` must be a non-empty list")
    else:
        unknown = [c for c in capabilities if c not in CAPABILITIES]
        if unknown:
            problems.append(f"unknown capabilities: {', '.join(unknown)}")
    entry = manifest.get("entry")
    if entry and not (folder / str(entry)).exists():
        problems.append(f"entry `{entry}` does not exist in the folder")
    licence = str(manifest.get("licence") or "").strip()
    if not licence:
        problems.append("manifest must declare a `licence`")
    runtime = str(manifest.get("runtime") or "process")
    if runtime != "process":
        problems.append(f"runtime `{runtime}` is not supported — providers run out of process")
    if runtime != "process" and any(tag in licence.upper() for tag in COPYLEFT):
        problems.append(f"{licence} code cannot be loaded into the app process")
    return problems


def discover() -> list[dict]:
    """Every provider folder, with its manifest, its problems and its last status."""
    out: list[dict] = []
    for folder in sorted(p for p in providers_dir().iterdir() if p.is_dir()):
        manifest_file = folder / "provider.json"
        row: dict = {"folder": str(folder), "id": folder.name, "name": folder.name,
                     "problems": [], "enabled": is_enabled(folder.name)}
        if not manifest_file.exists():
            row["problems"] = ["no provider.json in the folder"]
            out.append(row)
            continue
        try:
            manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001 — a broken manifest is reported, not fatal
            row["problems"] = [f"provider.json is not valid JSON ({exc})"]
            out.append(row)
            continue
        row.update({
            "id": str(manifest.get("id") or folder.name),
            "name": str(manifest.get("name") or folder.name),
            "version": str(manifest.get("version") or "0"),
            "entry": str(manifest.get("entry") or ""),
            "capabilities": list(manifest.get("capabilities") or []),
            "licence": str(manifest.get("licence") or ""),
            "description": str(manifest.get("description") or ""),
            "runtime": str(manifest.get("runtime") or "process"),
            "manifest": manifest,
            "dir": str(folder),
            "enabled": is_enabled(str(manifest.get("id") or folder.name)),
        })
        row["problems"] = validate(manifest, folder)
        row["status"] = STATUS.get(row["id"])
        out.append(row)
    return out


def _by_id(provider_id: str) -> dict | None:
    return next((p for p in discover() if p["id"] == provider_id), None)


def hook_providers(capability: str) -> list[dict]:
    """Enabled, valid providers that declare this capability."""
    return [
        p for p in discover()
        if p.get("enabled") and not p.get("problems") and capability in (p.get("capabilities") or [])
    ]


def _command(provider: dict) -> list[str]:
    entry = Path(provider["dir"]) / provider["entry"]
    if entry.suffix == ".py":
        return [sys.executable, str(entry)]
    if entry.suffix in (".cmd", ".bat"):
        return ["cmd", "/c", str(entry)]
    return [str(entry)]


def call(provider_id: str, op: str, payload: dict, timeout: float = DEFAULT_TIMEOUT) -> dict | None:
    """One bounded conversation with one provider. Never raises into the caller."""
    provider = _by_id(provider_id)
    if provider is None:
        return None
    if provider.get("problems"):
        _note(provider_id, False, "; ".join(provider["problems"]))
        return None
    if not provider.get("enabled"):
        return None
    script = "\n".join([
        json.dumps({"op": "init", "appVersion": __version__, "capabilities": list(CAPABILITIES)}),
        json.dumps({"op": op, "payload": payload}),
        json.dumps({"op": "shutdown"}),
    ]) + "\n"
    try:
        with _lock:  # one provider conversation at a time: they are processes, not threads
            run = subprocess.run(
                _command(provider), input=script.encode("utf-8"),
                capture_output=True, timeout=timeout, cwd=provider["dir"],
            )
    except subprocess.TimeoutExpired:
        _note(provider_id, False, f"timed out after {timeout:.0f}s")
        return None
    except Exception as exc:  # noqa: BLE001 — a provider that cannot start is a status line
        _note(provider_id, False, f"could not start ({exc})")
        return None

    if run.returncode != 0:
        _note(provider_id, False, f"exited {run.returncode}: {run.stderr.decode('utf-8', 'replace')[:200]}")
        return None
    answer = _pick(run.stdout.decode("utf-8", "replace"), op)
    if answer is None:
        _note(provider_id, False, "answered with no JSON line for the request")
        return None
    if not answer.get("ok"):
        _note(provider_id, False, str(answer.get("error") or "the provider refused")[:200])
        return None
    _note(provider_id, True, f"{op} answered in time")
    return answer.get("result")


def _pick(stdout: str, op: str) -> dict | None:
    """The reply to `op`, ignoring whatever else the provider printed."""
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            doc = json.loads(line)
        except ValueError:
            continue
        if doc.get("op") in (None, op) and ("ok" in doc or "result" in doc):
            return doc
    return None


def _note(provider_id: str, ok: bool, note: str) -> None:
    STATUS[provider_id] = {"ok": bool(ok), "note": note}


def hook(capability: str, payload: dict, timeout: float = DEFAULT_TIMEOUT) -> list[dict]:
    """Every capable provider's answer to one request, best effort."""
    answers: list[dict] = []
    for provider in hook_providers(capability):
        result = call(provider["id"], capability, payload, timeout=timeout)
        if isinstance(result, dict):
            answers.append(result)
    return answers


def selftest(provider_id: str) -> dict:
    """The Settings *Test* button: does it start, and what does it claim?"""
    provider = _by_id(provider_id)
    if provider is None:
        return {"ok": False, "note": "no such provider"}
    if provider.get("problems"):
        return {"ok": False, "note": "; ".join(provider["problems"])}
    result = call(provider_id, "selftest", {}, timeout=INIT_TIMEOUT)
    if result is None:
        return {"ok": False, "note": STATUS.get(provider_id, {}).get("note", "no answer")}
    return {"ok": True, "note": str(result.get("note") or "answered"), "result": result}


def catalogue() -> dict:
    """What a provider can be asked to do — the contract, in one place."""
    return {
        "dir": str(providers_dir()),
        "appVersion": __version__,
        "capabilities": [{"id": key, "contract": value} for key, value in CAPABILITIES.items()],
        "protocol": [
            "one JSON object per line on stdin and on stdout",
            "init → {ok, capabilities}; <capability> → {ok, result}; shutdown → exit",
            f"every call is killed after {DEFAULT_TIMEOUT:.0f}s",
        ],
        "licenceRule": "the manifest must declare a licence; copyleft code runs out of "
                       "process only, and the licence is shown next to the name",
    }
