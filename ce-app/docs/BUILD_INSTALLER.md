# Cutting Edge (CE) — Building the Windows Installer

This document describes how `Cutting-Edge-Setup-0.2.0.exe` is produced, both in CI
and on a local Windows machine, and how to debug a failing build.

---

## 1. What ends up inside the installer

| Component | Source | Where it lands in the installed app |
|---|---|---|
| Renderer (React + Ant Design) | `ce-app/frontend/src` → `vite build` → `dist/` | `resources/app.asar/dist` |
| Electron main / preload / updater | `ce-app/frontend/electron/*.ts` → esbuild → `dist-electron/` | `resources/app.asar/dist-electron` |
| Backend (FastAPI, port **8742**) | `ce-app/backend/{app,core,uploaders,run_backend.py}` | `resources/backend` |
| Python runtime | embeddable CPython **3.11.9** + `requirements.txt` | `resources/backend/python` |
| FFmpeg | `ffmpeg.exe` + `ffprobe.exe` (gyan.dev release build) | `resources/ffmpeg` |

At startup `electron/main.ts` looks for `resources/backend/python/python.exe`,
launches `run_backend.py`, and prepends `resources/ffmpeg` to `PATH`
(`CE_FFMPEG_DIR` is exported as well). Set `CE_MANUAL_BACKEND=1` to stop Electron
from spawning the backend (useful when you run `uvicorn` yourself).

---

## 2. Building in CI (the normal path)

Workflow: **`.github/workflows/ce.yml`** — *🎬 Build Cutting Edge*, `workflow_dispatch` only.

1. GitHub → **Actions** → *🎬 Build Cutting Edge* → **Run workflow**
2. **Pick the branch that actually contains `ce-app/`** — the workflow file lives on
   several branches, but a run on a branch without `ce-app/` dies at the first
   `cd ce-app\backend`.
3. When the run is green, download the **`Cutting-Edge-Windows-Setup`** artifact.

Steps performed: FFmpeg download → `python -m venv` + `pip install -r requirements.txt`
→ copy backend sources → `npm install` + `vite build` → `electron-builder --win nsis`
→ upload artifact. Expect **15–25 minutes**, dominated by the Python dependency
install (mediapipe, opencv, ctranslate2).

### The `beforePack` hook — why it exists

`ce-app/frontend/scripts/before-pack.js` runs inside `electron-builder`, immediately
before the app is packed. The CI job alone does **not** produce a shippable backend,
so the hook repairs three things:

1. **Virtualenv → portable runtime.** A `venv` resolves its standard library through
   `pyvenv.cfg` → the *build machine's* Python. On a user's PC that path does not
   exist and the backend never starts. The hook downloads the official
   **embeddable** CPython 3.11.9 distribution, enables `site` in `python311._pth`,
   and moves the CI-installed `site-packages` into it.
2. **Missing dependencies.** The CI step ignores `pip` failures (its last command is
   a `copy`, so a red `pip` still shows a green step). If the runtime cannot
   `import app.main` (the application itself, not a list of packages that goes
   stale), the hook bootstraps
   `pip` inside the embeddable runtime, installs `requirements.txt` (minus test
   tooling), and **fails the build loudly** if it is still incomplete.
3. **`ffprobe.exe`.** The workflow only copies `ffmpeg.exe`, but
   `core/engine/ingest.py` shells out to `ffprobe`. The hook fetches the official
   `ffmpeg-release-essentials.zip` and bundles whatever is missing.

The hook is a no-op on non-Windows hosts and when the runtime is already portable.

---

## 3. Building locally on Windows

```powershell
# from the repository root
$ErrorActionPreference = 'Stop'

# 1. FFmpeg + ffprobe  (or let beforePack fetch them)
New-Item -ItemType Directory -Force build\ffmpeg

# 2. Backend runtime
python -m venv build\backend\python
build\backend\python\Scripts\pip install -r ce-app\backend\requirements.txt
Copy-Item ce-app\backend\app,ce-app\backend\core,ce-app\backend\uploaders build\backend -Recurse -Force
Copy-Item ce-app\backend\run_backend.py build\backend

# 3. Frontend + installer
cd ce-app\frontend
npm install
npm run build            # vite build + esbuild electron bundles
npx electron-builder --win nsis --publish never
```

Output: `ce-app/frontend/release/Cutting-Edge-Setup-<version>.exe`
(plus `latest.yml` and a `.blockmap`, used by the auto-updater).

`ce-app/scripts/build-installer-local.ps1` wraps the same steps.

---

## 4. Auto-update

`electron/updater.ts` uses `electron-updater`; the `build.publish` block in
`package.json` points at `yousefghorbanian98-create/Chat2DB` releases. For the
in-app updater to see a new version you must attach **`latest.yml` and the `.exe`**
to a GitHub Release whose tag matches the new `version` in `package.json`.
IPC channels: `update:check`, `update:download`, `update:install`; renderer events:
`update:available`, `update:progress`, `update:downloaded`, `update:error`.

---

## 5. Troubleshooting

| Symptom in the log | Cause | Fix |
|---|---|---|
| `cd ce-app\backend : path does not exist` | run started on a branch without `ce-app/` | re-run on the branch that has it |
| `No matching distribution found for X` | a bad pin in `requirements.txt` (this killed run #5: the PyPI project is `scenedetect`, not `PySceneDetect`; `pexels-api` stops at 1.0.1) | fix the pin — CI hides the error behind a green step |
| `ModuleNotFoundError: No module named 'fastapi'` during Package | the runtime was packed empty because `pip` failed earlier | now self-healed by `beforePack`; check the pip output above it |
| `Application entry file dist-electron/main.js does not exist` | Electron bundles were not built | run `npm run build`, not just `vite build` (the committed `dist-electron/` covers CI) |
| `cannot find icon` | `build.win.icon` path wrong | icon lives at `ce-app/frontend/build-assets/icon.ico` (256→16 px, CE monogram) |
| Installer runs but the app shows a backend error | non-portable Python got packed | verify `resources/backend/python/python311._pth` exists in the installed app |
| Installed app opens a **black/empty window** | Vite emitted absolute asset URLs (`/assets/…`), which resolve to the filesystem root under `file://` | fixed in 0.2.1 via `base: './'`; keep it that way |
| UI loads but every request fails | `baseURL: '/api'` becomes `file:///api` when packaged | fixed in 0.2.1 by `src/api/runtime.ts` (targets `http://127.0.0.1:8742` under `file://`) |

Set the environment variable **`CE_DEBUG=1`** before launching the installed app to
open developer tools; load failures are also rendered as a readable error page
instead of an empty window.
