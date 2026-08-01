# CHG-2026-0731-SUBSCRIPTION-AI — Subscription-backed AI Access

- Mode: `strict`
- Created: 2026-07-31
- Status: approved for implementation
- Governing Traycer artifacts: `/Users/chenhaijie/.traycer/epics/89195abe-7430-4f79-8948-f7d1f0a3395a/artifacts/subscription-backed-ai-oauth`

## Current behavior

Community stores user-created API model configurations in renderer localStorage and routes AI requests through Spring AI. It has no supported consumer-subscription account connection, backend-authoritative model preference, durable attempt journal, or crash-consistent provider logout. The packaged JCEF frame can start before Spring, and its generic command bridge can retain or log request bodies.

## Desired behavior

Packaged Community JCEF desktop can host an evidence-gated ChatGPT subscription route through a pinned Codex app-server. Chat2DB never extracts OAuth tokens, requires OS Keyring, discovers a fresh availability snapshot, stores new control state transactionally, prevents transparent replay, and coordinates logout as a recoverable saga. Existing API-key routes remain and migrate through a user-confirmed encrypted JCEF import envelope.

## Goals

- Add provider-independent subscription architecture with ChatGPT as the first gated candidate.
- Keep SuperGrok out until xAI supplies a supported consumer-subscription contract.
- Preserve API-key behavior and Community runtime isolation.
- Make model, attempt, tool, migration, and logout state truthful and crash recoverable.
- Preserve automatic DML/DDL tool behavior per the explicit product decision while never claiming exactly-once execution.

## Non-goals

- Private OAuth protocol reproduction or token extraction.
- Browser, Docker, remote-server, CLI, Enterprise, payment, or cloud credential support.
- Runtime binary downloads or plaintext OAuth credential fallback.
- Exact-once target-database side effects.
- Silent provider/model fallback.

## Acceptance criteria

- [ ] Subscription beans and UI register only in packaged Community JCEF desktop and only when provider gates pass.
- [ ] ChatGPT uses a pinned app-server JSON-RPC boundary; Chat2DB never handles provider tokens.
- [ ] OS Keyring is mandatory and file fallback fails closed.
- [ ] H2 ledger atomically stores local connection, snapshot, preference, attempt, tool, saga, and migration facts.
- [ ] One active subscription attempt is enforced; ambiguous submission/tool outcomes never replay automatically.
- [ ] Logout fences first, confirms app-server unauthenticated state, and recovers after crash.
- [ ] Model discovery is fresh, compatibility-filtered, and worded as recently available rather than permanent entitlement.
- [ ] API keys migrate through an ephemeral hybrid-encrypted, dedicated JCEF boundary with canary non-disclosure tests.
- [ ] Existing API-key routes and legacy histories remain usable without rewritten historical identity.
- [x] Packaged Community users can find a direct ChatGPT subscription login entry in settings and the real model selector; recoverable capability failures remain visible with retry, and successful discovery after a connection attempt opens the refreshed selector with a model-count confirmation.
- [ ] Focused frontend/backend tests, Community build/package, diff checks, and final source review pass with nonzero test counts.

## Open questions

None that permit design invention during implementation. Provider live-account and per-platform packaging evidence are release gates; missing evidence keeps the provider disabled rather than changing the design.
