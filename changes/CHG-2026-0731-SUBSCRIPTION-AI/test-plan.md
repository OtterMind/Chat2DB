# Test plan — CHG-2026-0731-SUBSCRIPTION-AI

## Direct acceptance tests

- Community packaged-desktop registration and unsupported-surface absence.
- ChatGPT login/model/status/logout contract through a fake app-server fixture.
- Existing API-key route regression.
- Renderer provider states, model selector, attempts, migration, and legacy sessions.
- Subscription onboarding visibility, recoverable capability-error retry, the actual AI model-selector action, and post-discovery selector guidance.

## Unit tests

- Ledger schema and every state transition.
- JSON-RPC framing, bounded parsing, redaction, capability/version checks.
- snapshot freshness/filtering and request-time invalidation.
- provider lease, call fingerprint, duplicate/unknown tool outcomes.
- RSA-OAEP/AES-GCM import envelope, nonce, expiry, idempotency, and safe errors.

## Integration and contract tests

- Fake app-server subprocess for login, model/list, thread/turn/item, interrupt, logout, restart.
- Dedicated loopback MCP authentication and attempt binding.
- Spring/JCEF readiness and shutdown.
- Backend API contract plus frontend focused tests.

## Adjacent regression

- Existing AI model configuration, streaming, tool, history, and Community isolation tests.
- API-key model selection and custom base URL behavior.
- CLI, browser, Docker, and Enterprise-adjacent registration remains absent.

## Core smoke paths

- Community backend package and loopback smoke when feasible.
- Community frontend build.
- JCEF prepare and workflow/manifest review.

## Security and permission isolation

- Keyring unavailable/file fallback rejection.
- macOS app-server preserves OS-user `HOME` while isolating `CODEX_HOME`.
- macOS Keyring preflight resolves the default user Keychain with a bounded command and fails closed.
- app-server native-tool denial and empty workspace.
- generated app-server MCP `enabled_tools` contains only the seven Chat2DB database tools.
- pinned MCP resource metadata helpers see empty catalogs and cannot reach the database kernel; all foreign or unknown tools remain denied.
- loopback/random-port/capability rejection.
- canary key absent from UI, JCEF generic state, logs, streams, errors, fixtures, and reports across every import failure.

## Migration, rollback, idempotency, concurrency, and recovery

- H2 upgrade/rollback-disable behavior.
- simultaneous sends produce one provider lease.
- crash at every logout saga step.
- ambiguous turn submission and supervisor restart.
- tool crash before/after database execution, duplicate call, and unknown outcome.
- per-item localStorage deletion and retry.

## Evidence matrix

| Requirement or risk | Test level | Command or case | Result |
| --- | --- | --- | --- |
| Pinned MCP resource-helper compatibility | fake app-server + stream + real HTTP MCP + installed config | emit answer delta, then `list_mcp_resources`, then complete; also deny native/unknown/foreign tools and verify exact generated `enabled_tools` | passed; explicit four-failure RED followed by 32 focused and 110 full start subscription tests with 0 failures/errors/skips; installed config contains only the seven Chat2DB database tools |
| Ledger transitions | backend focused | owning Maven tests with tests explicitly enabled | passed; 21 H2 repository tests including sign-out/tool race, future-schema rejection, and interrupted/uncertain-tool restart convergence, 0 failures/errors/skips |
| app-server contract | backend integration | bounded JSON-RPC and fake subprocess suite | passed; included in 139-test subscription run; official v0.144.6 default-feature table reconciled and macOS arm64 archive/checksum/version staged |
| secret migration | frontend + JCEF + backend | crypto/import/JCEF canary failure matrix plus renderer flows | passed; 20 tools + 6 JCEF tests, file-backed H2 restart coverage, and frontend flow suite |
| Attempt/tool safety | backend focused | routing, lease cleanup, owning-stream fence, double-ledger failure, and tool outcome tests | passed; full runtime + faulting real-H2 double-ledger test proves immediate `TOOL_OUTCOME_UNKNOWN` fencing and restart convergence; slow-tool/logout and late-event races remain deterministic |
| Community frontend | build/lint | task-local Node 18.20.8, focused tests, full lint, Community build | passed |
| Onboarding UX remediation | renderer focused + installed payload | RED/GREEN subscription flow assertions, actual model-selector regression, i18n validation, source/install recursive diff | passed; post-login auto-open is attempt-fenced, recoverable failures remain actionable, and installed `dist` matches the verified build |
| Community backend | focused + package | final 149-test subscription run, 14 adjacent model-config tests, plus clean package | passed; retained evidence is 27 XML reports / 163 tests / 0 failures/errors/skips; the package command skipped tests by design |
| Local macOS preview refresh | package + process | Java 17 package, 98-test start-module subscription run, local payload replacement, code-sign and runtime-property checks | passed for `/Applications/Chat2DB Subscription Preview.app` `5.3.0.731`; local Apple Development signature only, not a signed release installer |
| Packaged macOS manifest path | focused unit + installed runtime | fake `.app/Contents/MacOS` launcher with `user.dir=/`, containment rejection, then installed child-process/runtime-dir check | passed; 8 focused tests and the installed preview launched pinned app-server PID 63976 under preview PID 63919 |
| macOS HOME and Keychain isolation | focused unit + installed runtime | child environment capture, injected preflight outcomes, real-HOME/isolated-`CODEX_HOME` Keychain lookup, installed child launch | passed; 13 focused tests and 102 full start subscription tests, installed preview PID 8691 launched pinned app-server PID 8741, no `auth.json` exists |
| Login completion and persisted status | runtime + lifecycle concurrency | blocking account-read callback, startup Keyring-account reconciliation, full start subscription suite, installed preview restart | code passed; RED timeout followed by 24 focused and 104 full tests with 0 failures/errors/skips; installed App awaits one live Connect to validate provider account/model projection |
| Chat stream error visibility | renderer flow + installed payload | application-error classification, JCEF forwarding, raw-log absence, refreshed renderer build | passed; terminal subscription errors now remain visible in chat instead of completing with zero chunks |
| Reasoning effort discovery and routing | frontend + app-server + H2 + runtime | ordered catalog parsing, schema-v3 round-trip, High default, unsupported rejection, `turn/start.effort`, startup refresh races | passed; 107 start subscription tests plus 21 H2 tests, 0 failures/errors/skips; installed ledger contains live High/xHigh-capable model rows |
| Pinned `thread/start` sandbox contract | fake subprocess + real packaged binary | capture the emitted sandbox value; submit both rejected and corrected values to v0.144.6 | passed; RED caught `readOnly`, GREEN emits `read-only`, and the real binary returned `thread/started` only for the corrected value |
| Restarted subscription model restoration | renderer flow + installed restart | delay surface hydration, assert model-option loading follows it, preserve the cached selection, and restart without OAuth | passed; RED/GREEN covers ordering and unmount cancellation, Node 18 tests/lint/build passed, installed restart restored Keyring plus eight models with zero login-start calls |
| Live subscription text turn | manual installed-App gate | select High/xHigh, send one harmless prompt, observe streamed text and terminal completion | pending user-initiated send in the refreshed preview |
| Safe startup diagnostics | focused unit + log check | stage/reason classifier with sensitive canary exception plus installed startup failure scan | passed; stable codes exclude exception messages and no new disabled-runtime entry appeared after successful launch |
| Default-off runtime | local smoke | Community/OFFLINE/loopback backend, dormant controller probe | passed on `127.0.0.1:18025`; task process stopped |
| Staging contract | script fixture | shell syntax plus checksum/license staging fixture | passed; expected corrupt-checksum case rejected |
| Native packaging | target-platform installer build/run | macOS, Windows, and Linux platform-scoped commands | pending; release gate remains disabled |
| Live ChatGPT | manual local gate | real account, no CI credentials | pending; required before enablement |
