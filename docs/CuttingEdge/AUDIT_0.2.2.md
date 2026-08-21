# Audit of the shipped build (v0.2.2) — 2026-08-21

Full inspection of the installer that is currently on the user's machine, performed
with the tooling added in 0.2.3. Method: the release feed was fetched from GitHub,
the exact commit that produced the build (`05d9a33`) was checked out into a separate
worktree, its renderer was rebuilt and driven through a headless browser across all
seven routes, and the CI log of the build itself was re-read for the packaging
assertions the smoke test now automates.

## Result

| Check | Result | Evidence |
|---|---|---|
| Release feed (`latest.yml`) | ✅ | version 0.2.2, sha512 and size match the asset |
| Blockmap published | ✅ | 497,160 bytes next to the installer |
| Renderer asset paths | ✅ | `./assets/...` — the black-screen cause is gone |
| All routes render | ✅ | 7/7 |
| Overlapping layout boxes | ✅ | 0 across all pages |
| Horizontal overflow | ✅ | none |
| Untranslated backend keys in UI | ✅ | none |
| JavaScript errors | ✅ | none (only the expected noise from no backend in the harness) |
| Portable Python runtime | ✅ | build log: `portable backend runtime ready` |
| ffprobe bundled | ✅ | build log: `bundled ffprobe.exe` |
| **Update button** | ❌ | events never reach the UI (IPC/`window.message` mismatch) |
| **Log file** | ❌ | the installed app writes nothing anywhere |
| Timeline editor | ➖ | not part of 0.2.2 |

Both failures are fixed in 0.2.3; nothing else in the shipped build is broken.

## What could not be verified from here

The sandbox cannot download the 479 MB asset (GitHub's asset CDN is blocked) and
cannot execute Windows binaries, so three runtime facts were verified indirectly
from the build log rather than by running the installed app:

- the packaged backend answers `/api/health`
- `ffmpeg.exe` / `ffprobe.exe` are reachable from the app
- the Python runtime starts on a machine without Python

From 0.2.3 on, `ce-app/scripts/smoke-test.ps1` performs exactly these three checks
**inside CI on the Windows runner**, so they stop being a matter of inference.

## Differential update: what the audit found

Reading `electron-updater` in `node_modules` rather than trusting the docs:

1. `AppUpdater.differentialDownloadInstaller()` compares the new blockmap against a
   previously stored installer at

```
%LOCALAPPDATA%\cutting-edge-frontend-updater\installer.exe
```

2. That file is written **by the NSIS installer itself** on every install
   (`installer.nsh` → `copyFile "$EXEPATH" "$LOCALAPPDATA\${APP_INSTALLER_STORE_FILE}"`).

So the manual installation of 0.2.2 already seeded the delta baseline: the
0.2.2 → 0.2.3 update can be a genuine differential download, not a full one. The
remaining requirement — that the old release and its blockmap stay online — is
satisfied, which is why old releases must never be deleted.
