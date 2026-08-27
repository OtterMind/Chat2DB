# Providers — other people's code, out of our process

> Status: **shipped, verified** (STATE §123). This is the contract, not a pitch.
> A provider is read from `~/CuttingEdge/providers/<name>/`; the app never
> installs one and never imports one.

## Why a separate process

Some of the best tools for this job can never be linked into the app: `piper`
is MIT on GitHub and **GPL-3.0-or-later on PyPI**, DeepFilterNet's wheel says
`NOASSERTION`, and a model like `vit-fer` has **no PyPI release at all**, so
there is no licence metadata to read. Refusing them costs a feature; importing
them costs the app its licence. A provider runs as its own subprocess, so it may
be GPL — or licence-less — and still be useful, because nothing of it is linked
into this codebase.

## The folder

```
~/CuttingEdge/providers/<name>/
    provider.json     manifest (below)
    run.py            anything that speaks the protocol
```

## provider.json

```json
{
  "id": "my-emotion",
  "name": "My emotion model",
  "version": "0.1.0",
  "entry": "run.py",
  "capabilities": ["emotion.score"],
  "licence": "MIT",
  "description": "scores moments for emotional strength",
  "runtime": "process"
}
```

* `licence` is **required and is shown next to the name** in Settings. A folder
  without it is reported as *not a provider* and never started.
* `runtime` must be `"process"`. `"inprocess"` is refused outright — that is the
  whole point of the channel.
* `capabilities` must be a non-empty subset of the catalogue below; unknown
  capabilities are reported and the folder is not started.

## The protocol

One JSON object per line on stdin, one per line on stdout. Every call is killed
after 15 s; a provider that is slow, lies, or exits non-zero is reported in
Settings and ignored — the edit proceeds without it.

```
→ {"op": "init", "appVersion": "0.9.36", "capabilities": [...]}
← {"op": "init", "ok": true, "capabilities": ["emotion.score"]}
→ {"op": "emotion.score", "payload": {"path": "...", "times": [1.0, 2.5]}}
← {"op": "emotion.score", "ok": true, "result": {"scores": {"1.0": 0.8}}}
→ {"op": "shutdown"}
```

## Capability catalogue

| capability | request → result | used where |
|---|---|---|
| `emotion.score` | `{path, times}` → `{scores: {t: 0..1}}` | one capped vote in the highlight scorer |
| `captions.polish` | `{items:[{text, lang}]}` → `{items:[{text}]}` | one batched pass after the built-in clean |
| `audio.denoise` | `{path, out}` → `{path}` | offered by the brain's *denoise* tool |
| `media.analyse` | `{path}` → `{signals}` | extra measured signals |

Both wired consumers are defensive: a `captions.polish` answer that returns a
missing, empty, or wildly-longer line keeps the built-in result (a "polish" that
triples a caption has changed what was said, and the timings no longer match the
words on screen).

## A full working example

```python
# run.py — upper-cases captions and calls every moment joyful, to show the shape
import json, sys

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    request = json.loads(line)
    op = request.get("op")
    if op == "init":
        print(json.dumps({"op": op, "ok": True,
                          "capabilities": ["emotion.score", "captions.polish"]}), flush=True)
    elif op == "emotion.score":
        scores = {str(t): 0.9 for t in request["payload"]["times"]}
        print(json.dumps({"op": op, "ok": True, "result": {"scores": scores}}), flush=True)
    elif op == "captions.polish":
        items = [{"text": (i.get("text") or "").upper()} for i in request["payload"]["items"]]
        print(json.dumps({"op": op, "ok": True, "result": {"items": items}}), flush=True)
    elif op == "selftest":
        print(json.dumps({"op": op, "ok": True, "result": {"note": "alive"}}), flush=True)
    elif op == "shutdown":
        break
```

Drop the folder in, open **Settings → Providers (plugins)**, press *Rescan*, then
*Test*. The card shows the licence, the capabilities, and exactly what came back
when the process was started.

## Where the code lives

* `core/providers/channel.py` — discovery, validation, the bounded subprocess
  conversation, and the batched `hook()` used by the consumers.
* `app/routers/providers.py` — `GET /api/providers`, `POST /enable`, `POST /test`.
* Consumers: `core/engine/text_polish.py::polish_lines` and
  `core/engine/emotion.py::_provider_scores`.
