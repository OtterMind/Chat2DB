# ChatGPT Subscription Onboarding UX Implementation Plan

> **For agentic workers:** execute inline with `executing-plans`; no subagents, commits, pushes, or release actions are authorized.

**Goal:** Make ChatGPT subscription login discoverable, actionable on recoverable bridge failures, and self-explanatory through model discovery.

**Architecture:** Keep backend capability authoritative for operability and preserve the default-off release gate. Add pure renderer presentation helpers for management visibility, quick-connect action selection, runtime errors, and newly discovered models; wire those helpers into Community settings, the real AI model selector, and the existing subscription store without storing credentials.

**Tech Stack:** React, TypeScript, Zustand, Ant Design, Umi, existing JCEF `javaQuery` bridge and i18n.

## Global Constraints

- Only packaged Community JCEF may expose the onboarding UI.
- An explicit backend `FEATURE_DISABLED` result stays hidden; a recoverable capability/bridge fetch failure stays visible with retry.
- Existing API-key models remain selectable and are never replaced or forced into subscription login.
- SuperGrok remains ineligible and must not gain an operable login action.
- No OAuth URL, token, API key, or secret-bearing error may enter renderer state or logs.
- OAuth authorization remains a user handoff; this change only clarifies and launches the existing flow.

### Task 1: Pure onboarding decisions

**Files:**
- Modify: `chat2db-community-client/src/blocks/AI/subscription/capability.ts`
- Modify: `chat2db-community-client/src/blocks/AI/subscription/modelSelectGroups.ts`
- Modify: `chat2db-community-client/src/blocks/AI/subscription/subscriptionFlows.test.ts`

- [x] Add failing tests for management visibility after capability fetch failure, fixed ChatGPT quick-connect/manage actions, safe error copy selection, and newly discovered selectable model detection.
- [x] Run `yarn test:ai-subscription` and confirm the new assertions fail for missing behavior.
- [x] Add the minimal pure helpers and rerun until the focused suite passes.

### Task 2: Settings entry and actionable failure state

**Files:**
- Modify: `chat2db-community-client/src/blocks/Setting/CommunitySetting.tsx`
- Modify: `chat2db-community-client/src/blocks/AI/subscription/SubscriptionAccountPanel/index.tsx`
- Modify: `chat2db-community-client/src/blocks/AI/subscription/SubscriptionAccountPanel/style.ts`

- [x] Keep the settings entry visible only when the pure management-visibility decision permits it.
- [x] Render direct ChatGPT subscription wording, three login steps, a safe localized error, and a retry action.
- [x] Keep explicit feature-disabled and unsupported surfaces hidden.

### Task 3: Real model-selector quick connect and post-login guidance

**Files:**
- Modify: `chat2db-community-client/src/blocks/AI/components/AIModelSelect/index.tsx`
- Modify: `chat2db-community-client/src/blocks/AI/components/AIChatInput/index.tsx`
- Modify: `chat2db-community-client/src/blocks/AI/index.tsx`

- [x] Prepend the ChatGPT subscription action to the actual selector used by `AIChatInput`.
- [x] Directly start login when an eligible disconnected provider is known; otherwise open Subscription settings for visible recovery.
- [x] When new selectable subscription snapshots appear after a renderer-observed connection attempt, reload model options, show a localized success message, and open the selector once without triggering on ordinary startup.

### Task 4: Localization and regression

**Files:**
- Modify: `chat2db-community-client/src/i18n/{en-US,zh-CN,ja-JP,es-ES,ko-KR}/ai.ts`
- Modify: `changes/CHG-2026-0731-SUBSCRIPTION-AI/{proposal.md,test-plan.md,status.md}`

- [x] Add matching localized keys for direct navigation, steps, retryable errors, selector actions, and models-ready guidance.
- [x] Run focused subscription/model-select tests, i18n validation, full lint, and Community build with Node 18.
- [x] Record exact verification and remaining OAuth/platform gates in the change pack.

### Task 5: Rebuild and reinstall preview

**Files:** generated outputs only under `target/`, `dist/`, `jpackage/`, and `/Applications/Chat2DB Subscription Preview.app`.

- [x] Build Community frontend and backend package using Java 17 and the existing task-local Node 18 toolchain.
- [x] Stop only the preview process, replace its renderer/Jar/lib payload, and re-sign with the existing local Apple Development identity.
- [x] Verify source/install hashes, deep signature, Community/OFFLINE/loopback/subscription flags, JCEF helpers, and installed onboarding bundle content.
- [x] Leave `/Applications/Chat2DB Community.app`, Git index, remotes, tags, workflows, and releases untouched.
