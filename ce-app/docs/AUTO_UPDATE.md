# Auto-update & differential (delta) patches

Short answer to “can updates ship as small patches instead of a 494 MB installer?”
**Yes.** electron-updater supports block-level differential downloads for the NSIS
target, and this project is now configured for it. Below is how it works, what was
missing, and what each release will realistically cost the user in bandwidth.

---

## 1. How the delta actually works

electron-builder writes three files per release:

| File | Purpose |
|---|---|
| `Cutting-Edge-Setup-<version>.exe` | the full installer |
| `Cutting-Edge-Setup-<version>.exe.blockmap` | map of the installer split into content-defined chunks, with a hash per chunk |
| `latest.yml` | version, file name, sha512 — the feed electron-updater polls |

On “بررسی به‌روزرسانی” the app fetches `latest.yml`. If a newer version exists it
downloads the **new blockmap**, compares it with the blockmap of the installer it
already has, and then issues HTTP **range requests** for only the chunks whose hash
changed. Unchanged chunks are copied out of the old file on disk. GitHub Releases
supports range requests, so this works out of the box.

If anything is missing (no blockmap, no range support, corrupted local file), the
updater silently falls back to a full download — the user still gets the update.

## 2. What was broken (and is now fixed)

| Problem | Effect | Fix |
|---|---|---|
| The CI artifact contained **only `*.exe`** | `latest.yml` never reached the feed → “check for updates” could never find anything, delta impossible | artifact/release now include `latest.yml` and `*.blockmap` (see `ce-app/ci/ce-workflow.yml`) |
| Nothing was published to **GitHub Releases** | electron-updater reads a release feed, not CI artifacts | `--publish always` with `GITHUB_TOKEN` |
| Payload was **not reproducible**: `.pyc` caches and fresh mtimes on every build | almost every chunk of the 494 MB payload changed each release → a “delta” the size of a full download | `before-pack.js` strips `__pycache__`/`*.pyc` and pins all timestamps to a fixed epoch |
| `compression: maximum` | solid LZMA blocks shift on any change, destroying chunk reuse | `compression: normal` |
| `differentialPackage` not requested | — | enabled in `build.nsis` |

## 3. Expected patch sizes

The installer is roughly:

| Part | Size | Changes when… |
|---|---|---|
| Python runtime + site-packages (mediapipe, opencv, ctranslate2, …) | ~380 MB | dependencies are bumped — rarely |
| FFmpeg + ffprobe | ~90 MB | almost never |
| Electron runtime | ~110 MB (compressed less) | Electron major upgrade |
| Our own code (renderer + backend sources) | ~2 MB | every release |

So a **UI-only release like 0.2.1 → 0.2.2 should download in the order of a few
tens of MB, not 494 MB** — the exact figure depends on how many compressed blocks
shift around our changed files. The determinism work above is what keeps that number
low; without it the delta collapses back to a full download.

## 4. If we want *really* small patches later

Block-level delta is the cheap win. The structural win is to stop shipping the heavy,
never-changing parts inside the installer at all:

1. **On-demand runtime**: ship the app (~120 MB with Electron) and fetch the Python
   runtime + FFmpeg into `~/CuttingEdge/runtime` on first launch, versioned
   independently. A UI release then patches ~2–5 MB.
2. **On-demand models**: Whisper / MediaPipe / Real-ESRGAN weights already belong in
   `~/CuttingEdge/models`, downloaded when a feature is first used.
3. **Renderer hot-patch**: the UI is a static bundle; a signed `dist.asar` swap could
   update the interface without touching the installer at all.

These are tracked in `docs/CuttingEdge/ROADMAP_EDITOR.md` under packaging.

## 5. Publishing a release correctly

```powershell
# version must be bumped in ce-app/frontend/package.json first
cd ce-app\frontend
npm run release          # build + electron-builder --publish always
```

Or run the workflow with **publish_release = true**. The release must contain all
three files; if you upload only the `.exe`, auto-update silently stops working.

**Rule of thumb:** never delete old releases. The updater needs the *previous*
installer's blockmap to be reachable for users who are one or more versions behind.
