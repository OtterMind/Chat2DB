# Motion packages — the app's motion language as data

**Read this next to `HYBRID_DESIGN_SYSTEM.md`.** The hybrid look (90 % Minimal
Pro · 9 % Cyberpunk · 1 % Glass) is fixed; how fast and how eagerly that look
*moves* is a package. A package is four numbers, and every one of them is read by
something real.

---

## 1. Why a switcher and not four hard-coded styles

The desktop app updates through a **differential (blockmap) update**, which can
only patch files the already-installed app references. A new *animation file*
would therefore never reach an installed app. The consequence is the whole
design:

* **the switcher ships in the base app** — the loader, the endpoints, the CSS
  variables;
* **the packages are data** — read at runtime from the built-ins and from
  `~/CuttingEdge/motion/*.json`;
* so a new package can be dropped in (or shipped as data) **without a
  reinstall**, and the feature can grow through a differential update.

## 2. The four parameters, and what reads each one

| Parameter | Read by | Effect |
|---|---|---|
| `duration` | `--m-speed` (CSS) + `LiveGlobe.speed` | multiplier on every animation/transition length; the globe spins and its pulses travel at `1 / duration` |
| `stagger` | `--m-stagger` (CSS) | gap between siblings in a staggered rise (`.ce-tile`, `.sm-opt`, `.sm-studio__col`, `.sm-loop--bars`) |
| `ease` | `--m-ease` (CSS) + `LiveGlobe.makeEase` | the timing curve of those animations; the same curve is solved numerically so a globe pulse eases along its arc |
| `particles` | `LiveGlobe` draw range | how many motes the globe field draws (allocated once, drawn range switched — no geometry rebuild) |

The variables are written by `Layout` the moment `/api/motion/params` answers,
and again on the `ce:motion-change` event, so a switch is instant and reversible.

**The contract is tested, not assumed.** `tests/test_motion.py` fails if a
variable the app writes is not read by any CSS rule — that is exactly the quiet
bug (a switch that flips and changes nothing) this design is exposed to.

## 3. The built-in packages

`Cinematic` is the *reference*: its numbers are the CSS defaults, so an app that
never reaches the backend animates exactly as it did before the switcher existed.

| id | particles | stagger | duration | ease |
|---|---|---|---|---|
| `cinematic` | 8 | 50 ms | ×1.0 | `cubic-bezier(.22,.61,.36,1)` — the `--ce-ease` token |
| `energetic` | 20 | 30 ms | ×0.8 | `cubic-bezier(.34,1.56,.64,1)` — slight overshoot |
| `calm` | 4 | 90 ms | ×1.4 | `ease-in-out` |
| `celebration` | 28 | 25 ms | ×0.9 | `cubic-bezier(.34,1.56,.64,1)` |

## 4. Drop-in packages

```json
{ "id": "neon", "en": "Neon", "fa": "نئون",
  "params": { "particles": 30, "stagger": 0.04, "duration": 0.7, "ease": "ease-out" } }
```

Saved as `~/CuttingEdge/motion/neon.json` it appears in Settings → *Motion
package* on the next `/api/motion/list` — no restart, no reinstall.

A drop-in is **user input**, so it is treated like input
(`core/motion_packages._sanitise` / `_safe_ease`):

* unknown keys are dropped;
* `particles` 0–64, `stagger` 0–0.6 s, `duration` ×0.4–×2.5 (clamped, so a
  `"particles": 999999` cannot hang the renderer);
* `ease` is **parsed and rebuilt from its own numbers** — a keyword from a fixed
  list or `cubic-bezier(a,b,c,d)` with `0 ≤ a,c ≤ 1`; anything else falls back to
  the built-in curve, so a package cannot inject CSS into the DOM;
* a file that does not parse is skipped, not fatal.

## 5. The brain picks a package like it picks a tool

Standing convention: every capability is registered in the brain. So
`core/brain/editor_brain.py` owns a `motion_package` tool whose decision carries
the recommendation, and Style Match shows it with its reason. The rule is
measured, never a matter of taste (`motion_packages.recommend`):

| measured signal | package |
|---|---|
| nothing measured | `cinematic` — and it says so |
| reaction ≥ 0.25, or ≥ 128 BPM with action ≥ 0.6 | `celebration` |
| action ≥ 0.45 or ≥ 110 BPM | `energetic` |
| ≥ 45 % speech | `calm` |
| otherwise | `cinematic` |

Style Match writes the pick into `summary.motionPackage` and the renderer keeps
it, so Settings can offer **Apply it** in one click. With no measurement there is
no badge — an unmeasured recommendation would be a guess.

## 6. Endpoints

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/motion/list` | every package (built-ins + drop-ins) with `active` |
| `GET` | `/api/motion/params` | the active package's parameters |
| `POST` | `/api/motion/set` `{id}` | the new active package; persists to `~/CuttingEdge/config.json`; `400` on an unknown id |
| `POST` | `/api/motion/recommend` `{bpm,action,emotion,speech_ratio}` | the recommended package, its reason, and whether it is already applied |

## 7. Files

| File | Role |
|---|---|
| `ce-app/backend/core/motion_packages.py` | built-ins, drop-in loader, clamping, `recommend()` |
| `ce-app/backend/app/routers/motion.py` | the four endpoints |
| `ce-app/frontend/src/components/Layout/index.tsx` | writes `--m-speed/--m-stagger/--m-ease`, exposes `window.__ceMotion` |
| `ce-app/frontend/src/styles/global.css` | declares the variables and every rule that reads them |
| `ce-app/frontend/src/components/LiveGlobe.tsx` | `particles` → draw range, `duration` → speed, `ease` → eased pulses |
| `ce-app/frontend/src/pages/Settings.tsx` | the switcher, the live parameter read-out, the brain's suggestion |
| `ce-app/backend/tests/test_motion.py` | the contract, the clamping, the brain decision |
