# Impact analysis — CHG-2026-0731-SUBSCRIPTION-AI

## Affected capabilities and users

Personal users of packaged Community JCEF desktop gain gated subscription account management, dynamic model availability, per-conversation model identity, attempt recovery, and explicit API-key migration. Other Community surfaces and existing API-key users must remain behaviorally compatible.

## Modules and expected file sets

| Area | Expected ownership |
| --- | --- |
| Domain contracts/state | `chat2db-community-domain-api`, `chat2db-community-domain-core` |
| Transactional persistence | `chat2db-community-storage` or a domain-facing provider implementation |
| Account/model/route adapters | `chat2db-community-web` |
| Process startup/packaging gates | `chat2db-community-start`, `script/package`, `jpackage`, workflows |
| Readiness/open-browser/import bridge | `chat2db-community-jcef`, `chat2db-community-tools` bridge contracts |
| Settings/model/history/migration UI | `chat2db-community-client/src/blocks/AI`, `blocks/Setting`, services, stores, i18n |
| Verification | focused backend/frontend/JCEF tests and packaging fixtures |

## APIs and downstream consumers

Add backend DTOs for provider status, model descriptors, preferences, attempts, account actions, and migration envelopes. Existing AI request contracts gain backend-issued `modelRef`, stable message/attempt identifiers, and safe status fields while preserving compatibility for existing API-key payloads.

## Data and migrations

Create an additive H2 AI ledger with schema versioning. Existing JSON history remains. Legacy messages are represented as unknown model. API-key localStorage deletion is per-item and only after encrypted backend write/readback acknowledgement.

## Security, privacy, and permissions

Mandatory OS Keyring, no token exposure, provider/binary gating, empty app-server workspace, denied native tools, loopback-only MCP capability, encrypted secret import, redacted errors, and canary log scans. Whole-profile/same-user compromise remains outside the AES file-store threat model.

## Operations and observability

Use non-secret structured status/error codes, supervisor health, protocol version, ledger schema version, provider fence, snapshot age, attempt/tool terminal state, and saga step. Never log prompts, SQL, tokens, API keys, auth URLs, callback queries, or encrypted envelopes at info level.

## Compatibility and documentation

Preserve Community ports, runtime flags, API-key models, histories, Docker/browser absence, and packaging identity. Update public configuration and release documentation when the provider is actually enabled.

## Parallelization candidates

No concurrent write streams are authorized in this run. Shared contracts, ledger, route, and migration boundaries are highly coupled and will be implemented sequentially in the integration worktree.
