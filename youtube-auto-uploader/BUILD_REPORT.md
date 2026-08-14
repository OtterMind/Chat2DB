# Build Report — 1.0.0

Date: 2026-08-14 (UTC)
Host: Linux x64 sandbox, Node 22.22.3
Target: Windows 10/11 x64

## Executive result

The Electron/React application source, strict preload boundary, SQLite migration, OAuth flow, upload/download services, monitoring, Ollama integration, FFmpeg render pipeline, tray, nine routed pages, and Windows packaging configuration were implemented under `youtube-auto-uploader/`.

A production renderer/main/preload build succeeds. A Windows installer was **not produced on this host**. Public binary/native-addon downloads are blocked by the sandbox TLS/proxy (`UNABLE_TO_VERIFY_LEAF_SIGNATURE` / `ECONNRESET`), and Windows installation/signing cannot be validated from Linux. No signing certificate was provided or requested.

## Phase A — static correctness

| Check | Result | Evidence |
|---|---|---|
| Dependency resolution | Partial pass | `npm install --ignore-scripts` exited 0; 645 packages installed. Native postinstall was intentionally run separately and failed due host TLS. |
| TypeScript strict | Pass | `npm run typecheck` exited 0. |
| ESLint | Pass | `npm run lint` exited 0 with zero errors/warnings. |
| Production build | Pass | `npm run build` exited 0; main, preload, and renderer bundles emitted. |
| IPC matching | Pass (static review) | Invoke channels in `electron/preload.ts` have handlers in `electron/ipc/index.ts`; renderer event subscription has a fixed allow-list. |
| SQL migration | Blocked at runtime | Migration is bundled and statically compiled; native `better-sqlite3` binding could not be downloaded/built on this host. |
| Path handling | Pass (static review) | Runtime paths use `path.join`; yt-dlp output uses platform-aware paths. |

The final successful combined command was:

```text
npm run typecheck && npm run lint && npm run build
```

Output artifacts included `out/main/index.js`, `out/preload/index.js`, and renderer assets.

## Phase B — smoke tests

`npm run smoke` ran five tests. Four passed:

- retry succeeds after two transient failures;
- retry stops after five configured failures;
- Ollama unavailable returns `{running:false, models:[]}`;
- Ollama mock server returns its model list.

The DB/settings test was blocked before assertions because the native `better-sqlite3.node` binding was unavailable. `npm rebuild better-sqlite3` was attempted twice and failed while fetching Node headers (`ECONNRESET`). `electron-builder install-app-deps` also failed while fetching Electron/native prebuilds due certificate validation and reset errors. This is an environment/dependency-fetch failure, not reported as a test pass.

FFmpeg and yt-dlp smoke cases could not run because the Windows binaries could not be fetched. Paths and executable existence are validated at runtime by `electron/bin.ts`.

## Phase C — application boot

Not executed. Electron boot requires rebuilt native SQLite/keytar dependencies. Static renderer build passed. Window, OAuth browser invocation, navigation, RTL, theme, and DevTools behavior therefore remain target-runtime checks.

## Phase D — feature integration

Not executed end-to-end on this host. Implemented code paths:

- yt-dlp JSON metadata and playlist enumeration;
- persisted single/batch/auto-sync job records;
- yt-dlp progress to renderer events;
- Google resumable media upload progress;
- first-run monitor baseline (no backfill);
- Ollama strict-JSON highlight selection with validation/retry;
- vertical 1080×1920 FFmpeg rendering and frame extraction;
- pending clips and approval state.

Mocked queue/network integration fixtures beyond the core test suite remain to be added.

## Phase E — packaging

`npm run prebuild:win` was attempted and failed while calling GitHub's release API because the sandbox could not validate the proxy certificate. The script leaves no partial executable and verifies yt-dlp against the official release checksum when networking works.

`npm run dist:win` was not continued after this prerequisite failed. Consequently:

- no NSIS installer exists;
- no portable executable exists;
- no installer size or SHA-256 can be truthfully reported;
- clean Windows installation and shortcut tests remain Windows-only;
- code signing remains unavailable (the project documentation explains SmartScreen for unsigned community builds).

## Phase F — final pass

Not run because Phase E did not produce a runnable Windows artifact. Memory soak, real-Ollama E2E, Windows toast, tray, and clean shutdown checks remain Windows-target verification.

## Security review

- Renderer has `nodeIntegration:false`, `contextIsolation:true`, and sandboxing enabled.
- Preload exposes a fixed method/event allow-list.
- OAuth credentials are not embedded and are stored via keytar; OS-encrypted `safeStorage` is the fallback.
- OAuth callback binds only to `127.0.0.1` on an ephemeral port.
- Ollama defaults to loopback.
- No `eval` or renderer-provided command execution is used.
- Heavy filesystem/network/process work resides in the main process.
- The app displays an explicit copyright/responsibility gate.

## Known limitations versus the full specification

1. The clipper now analyzes scene changes, silence and RMS, scores transcript/keyword candidates, burns five-word caption cues when YouTube captions exist, supports smart zoom/blur/aspect options, mixes generated royalty-free background audio, and creates Sharp-composited thumbnails. A bundled whisper.cpp transcription fallback is still not included for local files or videos without captions.
2. UploadQueue now reconstructs interrupted jobs from persisted JSON, retries retryable work, tracks quota, supports pending approval/rejection/retry, and uploads approved clips. It still uses one aggregate worker rather than separate configurable download/upload pools; pausing does not terminate an already-running child process, and Google API media upload relies on the library's resumable transport rather than application-managed 10 MB chunk checkpoints.
3. Auto-sync honors per-channel hour intervals and first-run no-backfill behavior, but uses yt-dlp channel enumeration rather than YouTube API ETag requests and does not yet implement custom cron or persisted per-channel exponential-backoff timestamps.
4. OAuth has no live Google-account integration test in this environment.
5. Batch list virtualization, inline per-item metadata overrides, failure CSV, CSV history export, auto-updater, uninstall data-deletion prompt, and complete Persian string coverage remain incomplete. Four-step onboarding and custom thumbnail selection are implemented.
6. The bundled font files are DejaVu compatibility fonts under the requested filenames, not the official Inter/Vazirmatn distributions. The verified Windows prebuild step generates and bundles a 30-second original ambient background loop; music remains opt-in.
7. The initial full dependency audit reported 25 transitive advisories. Axios, React Router DOM, and Sharp were updated; the final production-only audit reports seven moderate transitive advisories through React Router 6, googleapis/gaxios, and node-cron/uuid. The full tree still includes dev/build-tool advisories tied largely to Electron 28/electron-builder-era dependencies. These need review before distribution.

## GitHub Actions release build

A Windows Server 2022 workflow now exists at `.github/workflows/build-youtube-uploader.yml`. It installs Node dependencies without scripts, builds the Node ABI SQLite binding for smoke tests, runs typecheck/lint/core tests, downloads and checksum-verifies FFmpeg and yt-dlp, performs binary synthetic-video tests, rebuilds native modules against Electron, builds NSIS and Portable targets, enforces the 250 MB installer limit, writes SHA-256 sums, and uploads all three files as one GitHub artifact. This workflow has not been dispatched because committing/pushing and workflow execution are external actions requiring explicit authorization.

A browser-only mock preview was also added for UI inspection; it is excluded from production behavior whenever Electron preload is present.

## Required release-host steps

On a network-enabled Windows x64 build machine:

```text
npm ci
npm run typecheck
npm run lint
npm run smoke
npm run dist:win
```

Then run all Phase C–F checks, install both artifacts on clean Windows 10 and 11 VMs, and record:

```powershell
Get-FileHash "dist\YouTube Auto-Uploader 1.0.0 x64.exe" -Algorithm SHA256
Get-FileHash "dist\YouTube Auto-Uploader 1.0.0 Portable.exe" -Algorithm SHA256
```

Do not call the output “signed” unless an Authenticode certificate is configured and `Get-AuthenticodeSignature` returns `Valid`.
