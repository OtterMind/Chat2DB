# Subscription Chat Stream and Reasoning Effort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: execute inline with `executing-plans`; no subagents, commits, pushes, release actions, or authenticated provider writes are authorized.

**Goal:** Make subscription chat failures visible instead of silently completing, preserve the pinned app-server model reasoning-effort contract end to end, default to High when supported, and pass the verified selection to `turn/start.effort`.

**Architecture:** The pinned Codex app-server catalog remains the authority for supported and default reasoning efforts. Chat2DB stores that non-secret metadata with each durable model snapshot, returns it through the subscription model API, selects a renderer value from the advertised ordered list, and validates it again immediately before a turn. JCEF stream application errors bypass the generic HTTP-style error interceptor so the chat surface can render them, while backend logs contain only stable error codes.

**Tech Stack:** Java 17, Spring Boot, H2/JDBC, Jackson/Fastjson2, React, TypeScript, Zustand, Ant Design, Umi, JCEF IPC, Maven, Yarn/tsx.

## Global Constraints

- Community packaged JCEF only; API-key routing remains byte-for-byte delegated.
- SuperGrok remains non-operable.
- Never log prompts, history, SQL, attachments, tool arguments/results, OAuth material, API keys, or capability tokens.
- Preserve the order of `model/list.supportedReasoningEfforts`; do not infer support from model names.
- Default to `high` only when advertised; otherwise use `defaultReasoningEffort`, then the first advertised effort.
- Reject an effort not present in the current fresh model snapshot before acquiring a provider lease.
- No production/user database inspection or mutation for verification; use fixtures and isolated H2 databases.

---

### Task 1: Stop swallowing application stream errors

**Files:**
- Modify: `chat2db-community-client/src/components/SSERequest/errorPayload.ts`
- Modify: `chat2db-community-client/src/components/SSERequest/sseClientRequest.ts`
- Modify: `chat2db-community-client/src/hooks/useSSERequest.ts`
- Test: `chat2db-community-client/src/blocks/AI/subscription/subscriptionFlows.test.ts`

**Interfaces:**
- Produces: `isApplicationStreamErrorPayload(value: unknown): boolean`.
- Behavior: an SSE payload with `type` or `messageType` equal to `error` is forwarded to `onUpdate` before the request reaches terminal success; generic transport/API errors continue through `handleSSEErrorPayload`.

- [x] Add assertions that an application stream error is classified separately and that `useSSERequest.ts` contains no raw request/chunk console logging.
- [x] Run `yarn test:ai-subscription` and confirm RED.
- [x] Implement the classifier, reorder JCEF handling, and remove raw console logging.
- [x] Rerun the focused renderer test and confirm GREEN.

### Task 2: Preserve model reasoning metadata durably

**Files:**
- Modify: `chat2db-community-domain-api/.../AiModelSnapshot.java`
- Modify: `chat2db-community-start/.../appserver/dto/AppServerModelDescriptor.java`
- Modify: `chat2db-community-start/.../appserver/CodexAppServerSupervisor.java`
- Modify: `chat2db-community-start/.../lifecycle/ChatGptSubscriptionLifecycleService.java`
- Modify: `chat2db-community-storage/.../H2AiSubscriptionStateRepository.java`
- Test: owning app-server, lifecycle, and H2 repository tests.

**Interfaces:**
- `AiModelSnapshot` adds ordered `List<String> supportedReasoningEfforts` and nullable `String defaultReasoningEffort`, retaining a compatibility constructor for existing call sites.
- H2 schema v3 adds `supported_reasoning_efforts` and `default_reasoning_effort` to `ai_model_snapshot` before registering version 3.

- [x] Add fixture assertions for ordered provider capabilities/defaults and H2 restart round-trip; confirm RED.
- [x] Parse `supportedReasoningEfforts[].reasoningEffort` and `defaultReasoningEffort` from pinned model/list.
- [x] Store and restore metadata using validated comma-separated effort identifiers; legacy rows remain readable with an empty list and null default.
- [x] Run focused app-server, lifecycle, and storage tests and confirm GREEN.

### Task 3: Validate and route the chosen effort

**Files:**
- Modify: `chat2db-community-web/.../ChatRequest.java`
- Modify: `chat2db-community-start/.../appserver/CodexAppServerPort.java`
- Modify: `chat2db-community-start/.../appserver/CodexAppServerSupervisor.java`
- Modify: `chat2db-community-start/.../routing/SubscriptionSseChatStreamService.java`
- Test: `AiRouteResolverTest`, `CodexAppServerSupervisorTest`, `SubscriptionSseChatStreamServiceTest`.

**Interfaces:**
- `ChatRequest.reasoningEffort` uses the lowercase app-server wire value.
- `CodexAppServerPort.startTurn(String threadId, String textInput, String reasoningEffort)` sends `effort` only after validation.

- [x] Add RED tests proving High reaches `turn/start.effort`, unsupported values stop before lease acquisition, and API-key requests remain unchanged.
- [x] Validate the requested value against the current fresh snapshot; when omitted, resolve High if supported, else provider default, else first supported, else null for legacy catalogs.
- [x] Add a stable safe terminal-code log and pass the resolved effort to the app-server.
- [x] Run focused routing/app-server tests and confirm GREEN.

### Task 4: Expose and select effort in the renderer

**Files:**
- Modify: `chat2db-community-start/.../controller/AiSubscriptionController.java`
- Modify: `chat2db-community-client/src/typings/aiSubscription.ts`
- Modify: `chat2db-community-client/src/service/aiStream.ts`
- Modify: `chat2db-community-client/src/service/aiModelConfig.ts`
- Modify: `chat2db-community-client/src/blocks/AI/subscription/modelSnapshot.ts`
- Modify: `chat2db-community-client/src/blocks/AI/components/AIChatInput/index.tsx`
- Modify: `chat2db-community-client/src/blocks/AI/index.tsx`
- Modify: five locale `ai.ts` files.
- Test: controller tests plus `subscriptionFlows.test.ts`.

**Interfaces:**
- Model snapshot DTO adds ordered `supportedReasoningEfforts` and `defaultReasoningEffort`.
- `resolveReasoningEffortSelection(snapshot, previous)` returns `{ value, options }`, preferring previous, then High, provider default, first advertised.
- Subscription chat payload adds `reasoningEffort`; API-key payload omits it.

- [x] Add RED pure-flow and controller DTO assertions.
- [x] Add a compact reasoning-effort selector adjacent to the model selector, visible only for subscription models with advertised efforts.
- [x] Preserve the current choice while supported and reset deterministically on model change.
- [x] Add the localized reasoning selector label and make safe application-stream failures persist in the chat surface.
- [x] Run focused frontend/controller tests and confirm GREEN.

### Task 5: Integrated verification and preview refresh

**Files:**
- Modify: `changes/CHG-2026-0731-SUBSCRIPTION-AI/{test-plan.md,status.md}`.

- [x] Run the complete subscription/secret-import backend test set with tests explicitly enabled and verify a nonzero count with zero failures/errors/skips.
- [x] Run focused renderer tests, ESLint/Stylelint, and the Community build under Node 18.20.8.
- [x] Run the Java 17 backend package; record that package skips tests and use the separate test run as evidence.
- [x] Run `git diff --check`, inspect the focused diff, and scan reports/build output for prompt/API-key canaries.
- [x] Stop only the task-owned preview process, back up the previous installed payload, replace renderer/JAR/libs, deep-sign locally, reopen, and verify hashes, process ancestry, loopback listener, and pinned app-server version.
- [x] Reproduce the live `thread/start` failure against pinned v0.144.6, correct the wire sandbox enum from rejected `readOnly` to accepted `read-only`, and lock it with a fake-process regression plus a real-binary protocol probe.
- [ ] Do not claim live provider success until a user-initiated message produces `thread/start`, `turn/start`, streamed output, and a terminal completion in the refreshed App.

### Task 6: Restore persisted subscription selection without re-login

**Files:**
- Add: `chat2db-community-client/src/blocks/AI/subscription/startupHydration.ts`
- Modify: `chat2db-community-client/src/blocks/AI/index.tsx`
- Test: `chat2db-community-client/src/blocks/AI/subscription/subscriptionFlows.test.ts`

**Interfaces:**
- `hydrateSubscriptionModelOptions(refreshSurface, loadModelOptions, isActive)` serializes ordinary startup restoration: first restore capability/provider/model snapshots, then rebuild the AI model option map while the component remains mounted.
- Login state remains owned by the backend Keyring/app-server reconciliation; the renderer must never start OAuth merely to repair an empty startup model map.

- [x] Add a RED test proving model options cannot load before subscription surface hydration finishes and unmounted instances do not apply a late load.
- [x] Replace the parallel mount calls with the serialized, cancellation-aware helper.
- [x] Run focused renderer tests and touched-file lint.
- [x] Restart the installed Preview without OAuth and prove the persisted selection is present after authenticated model discovery.

## Self-review

- Coverage: stream error visibility, safe logging, model truth, High default, request validation, app-server effort, UI display, compatibility, migration, and packaging each have an owning task.
- Type consistency: all layers use lowercase effort wire values and ordered string lists; the renderer never invents an unsupported option.
- Scope: no thread persistence, provider fallback, SuperGrok enablement, service-tier UI, or unrelated AI refactor is included.
- Commit steps are intentionally omitted because this worktree is an uncommitted user-reviewed delivery and no commit authorization exists.
