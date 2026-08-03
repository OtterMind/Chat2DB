# Rollout — CHG-2026-0731-SUBSCRIPTION-AI

## Deployment order

1. Merge disabled infrastructure and migration safety tests.
2. Validate fixed app-server packaging per OS/architecture.
3. Run local live-account and Keyring gates.
4. Enable ChatGPT only for target platforms with complete evidence.
5. Keep SuperGrok disabled until a separate contract revision.

## Feature flags and compatibility window

Provider manifest defaults disabled. API-key migration is independently user-triggered. Existing API-key and history paths remain throughout the compatibility window.

## Observability

Expose only non-secret provider readiness, disabled reason, protocol/binary version, snapshot age, provider lease, attempt/tool terminal state, and logout saga step.

## Rollback triggers

- Any token/auth URL/API key in renderer, log, error, or fixture.
- app-server checksum/protocol/capability mismatch.
- inability to enforce Keyring or deny native tools.
- duplicate or silently replayed database tool execution.
- logout reports success while app-server remains authenticated.

## Rollback and recovery procedure

Disable the provider manifest, stop the supervisor, preserve the H2 ledger for recovery, and leave API-key routes/history untouched. Resume incomplete logout/import recovery before any later re-enable. Never delete or reinterpret unknown database-tool outcomes.
