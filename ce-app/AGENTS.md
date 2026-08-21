# Cutting Edge (CE) — scoped agent contract

This file applies to everything under `ce-app/`. CE is a separate product from the
surrounding Chat2DB repository: a Windows desktop video editor built with FastAPI +
SQLite (backend, port 8742), React 18 + Vite + Electron 31 (frontend) and a bundled
FFmpeg.

## Read first

`docs/CuttingEdge/STATE.md` (repository root) is the living handoff document: what
exists, how to rebuild a dev environment after a session wipe, the checks that
protect the product, the bugs already fixed, and the release procedure. Start there
instead of re-deriving the project from the source tree.

Companion documents:

- `docs/CuttingEdge/ROADMAP_EDITOR.md` — path to CapCut/Filmora parity
- `docs/CuttingEdge/OSS_EVALUATION.md` — verdict on every proposed library, with
  licence traps
- `docs/CuttingEdge/DEBUGGING.md` — tooling and the fast local loop
- `docs/CuttingEdge/AUDIT_0.2.2.md` — how a shipped build was audited
- `ce-app/docs/AUTO_UPDATE.md` and `ce-app/docs/BUILD_INSTALLER.md` — packaging

## Rules that are not negotiable

1. **Verify before shipping.** `python -m pytest` in `ce-app/backend` and
   `npm run test:ui` in `ce-app/frontend` both run without Windows. A change to
   packaging must also pass `ce-app/scripts/smoke-test.ps1` in CI.
2. **Never claim a build works because it compiled.** The two bugs that reached
   users (empty backend, black window) both compiled cleanly.
3. **Do not reintroduce the fixed bugs** listed in `STATE.md` section 4.
4. **Licences matter.** No GPL dependency may be linked into the app process; the
   project stays permissively licensed. A repository with no licence file cannot be
   copied from at all.
5. **English is the source language.** UI strings use `t('English', 'فارسی')` from
   `src/i18n`; never hardcode one language.
6. **Long-running work belongs outside the router.** Sockets, timers and uploads
   live in `src/store/runtime.ts` and `src/runtime/RuntimeBridge.tsx` so switching
   tabs cannot cancel them.
7. **The version lives in one place**: `ce-app/frontend/package.json`. Bumping it is
   what triggers a release.
