# Debugging & build-speed tooling

Everything below was checked on GitHub (stars, licence, last push) before being
recommended, and the three highest-leverage items are already implemented.

---

## What actually slowed us down

Looking back at the failures in this project, the cost was never the fix — it was
finding out what broke:

| Incident | How long the feedback loop was | Root cause of the delay |
|---|---|---|
| pip aborted on a bad pin, backend shipped empty | one full build + a manual install | CI step swallowed the error, nothing validated the artifact |
| black window after install | one build + install + user report | no logs from the installed app, no UI check in CI |
| “check for updates” did nothing | would never have surfaced | events never reached the UI, and nothing logged it |

So the tooling that pays off is: **logs from the installed app**, **an artifact that
validates itself**, and **a faster build**.

## Implemented now

### 1. electron-log — the installed app finally talks

`megahertz/electron-log` ⭐1.5k · MIT · active

- main process, renderer errors, unhandled rejections **and the Python backend's
  stdout/stderr** all stream into one file
- location on Windows:

```
%APPDATA%\Cutting Edge\logs\main.log
```

```
%APPDATA%\Cutting Edge\logs\backend.log
```

- Diagnostics screen shows the path and has a «باز کردن پوشه گزارش» button, so a bug
  report is now “send me this file” instead of “describe what you saw”
- `log.errorHandler.startCatching()` records crashes that used to vanish silently

### 2. A smoke test that refuses to ship a broken installer

```
ce-app/scripts/smoke-test.ps1
```

Runs against the unpacked build in CI and checks:

- `app.asar` exists and contains `dist-electron/main.js`
- `index.html` does **not** use absolute `/assets/...` paths — the exact cause of the
  black window
- `ffmpeg.exe` **and** `ffprobe.exe` are bundled
- the Python runtime is an embeddable distribution, not a virtualenv that only works
  on the build machine
- the packaged backend really starts and answers `/api/health`

Both bugs that reached you would have failed this test before the release existed.

### 3. uv — the slowest build step, cut down

`astral-sh/uv` ⭐89k · Apache-2.0 · pushed today

Installing mediapipe, opencv and ctranslate2 with pip dominated the ~7 minute build.
`uv` resolves and installs the same wheels far faster and caches them across runs.
Combined with an Electron binary cache, the build should land in the 3–4 minute
range.

### 4. tmate — an SSH shell into a failing runner

`mxschmitt/action-tmate` ⭐3.6k · MIT

Wired to run **only** when a manually dispatched build fails, restricted to you.
Instead of guessing from logs and pushing “try again” commits, you get a live shell
on the Windows runner for 15 minutes.

## Worth adding next

| Tool | Stars / licence | What it buys us |
|---|---|---|
| **Playwright** `microsoft/playwright` | ⭐95k · Apache-2.0 | drives the real Electron binary in CI: launch, click every tab, assert nothing overlaps, screenshot on failure. This is the automated version of the manual pass I run in the sandbox |
| **act** `nektos/act` | ⭐72k · MIT | run the GitHub workflow locally in Docker; debug the YAML without burning a 7-minute cloud build per attempt (Windows jobs still need the cloud, but the Linux `decide` job and script logic can be iterated instantly) |
| **sentry-electron** `getsentry/sentry-electron` | ⭐260 · MIT | crash/error reporting from real users’ machines; can point at a self-hosted server so nothing leaves your infrastructure |
| **electron-unhandled** `sindresorhus/electron-unhandled` | ⭐466 · MIT | a friendly dialog with a “report” button instead of a silent failure |

Not recommended: GlitchTip's GitHub mirror is stale (last push 2022, no licence) —
use its GitLab source or Sentry directly if self-hosting matters.

## Faster local loop (no CI at all)

For UI work you never need a Windows build:

```
cd ce-app/frontend && npm run dev
```

and for the backend:

```
cd ce-app/backend && python run_backend.py
```

The Vite dev server proxies `/api` and `/ws` to port 8742, so the whole interface —
including the timeline — is testable in a browser in under two seconds per change.
Only packaging-level behaviour (auto-update, portable runtime, file:// paths) needs
the real installer, and that is exactly what the smoke test now guards.

## Testing playback headlessly

The transport bugs of 0.3.3 (dead playhead, no roll-on to the next clip) could not
be caught by TypeScript or by the route audit — they need a real browser playing
real media. `ce-app/frontend/scripts/playback-test.mjs` does that.

Two test files with picture and sound, in a codec plain Chromium can decode:

```
for i in 1 2; do
  ffmpeg -y -f lavfi -i "testsrc=size=320x240:rate=25:duration=3" \
         -f lavfi -i "sine=frequency=$((300*i)):duration=3" \
         -c:v libvpx -c:a libvorbis -shortest /tmp/media/clip$i.webm
done
```

H.264/AAC is deliberately avoided: an unbranded Chromium build has no proprietary
codecs and every check would fail for the wrong reason.

A Chromium for the sandbox:

```
npm i @sparticuz/chromium@131.0.1 puppeteer-core@23.9.0
node -e "require('@sparticuz/chromium').executablePath()"      # unpacks /tmp/chromium
node -e "const z=require('zlib'),f=require('fs');f.writeFileSync('/tmp/al2023.tar',
  z.brotliDecompressSync(f.readFileSync('node_modules/@sparticuz/chromium/bin/al2023.tar.br')))"
mkdir -p /tmp/chromium-libs && tar xf /tmp/al2023.tar -C /tmp/chromium-libs
```

Then, with the backend on 8742 and Vite on 5173:

```
LD_LIBRARY_PATH=/tmp/chromium-libs/lib:/tmp/chromium-libs CHROME_PATH=/tmp/chromium \
  npm run test:playback -- --a /tmp/media/clip1.webm --b /tmp/media/clip2.webm
```

The test drives the timeline through `window.__ceEditor`, a handle exported only
when `import.meta.env.DEV` is true, so nothing is exposed in the shipped app.

Watch out for one trap that already produced a false failure: creating a transition
ripples the second clip earlier, so any expected source time must be derived from
the clip state, never hard-coded.
