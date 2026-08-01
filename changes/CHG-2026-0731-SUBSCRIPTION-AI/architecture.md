# Architecture — CHG-2026-0731-SUBSCRIPTION-AI

## Existing constraints

- Community runtime identity and offline-first packaged desktop boundaries must remain intact.
- Current API-key requests use Spring AI with internal tool execution.
- JCEF can exist before Spring readiness.
- Current AI model/history persistence is independent JSON; it cannot coordinate a multi-step logout.
- Provider tokens must never enter renderer or generic JCEF/controller/logging paths.

## Decision

Use dual AI routes. Existing API-key models stay on Spring AI. ChatGPT subscription turns use a supervised, pinned Codex app-server end to end. New control facts live in a non-secret H2 ledger. OAuth credentials stay in OS Keyring under app-server ownership. A dedicated loopback MCP bridge reuses the existing Chat2DB tools under one active provider lease. Legacy API keys cross JCEF only as a hybrid-encrypted envelope.

The generated app-server MCP registration exposes exactly the seven Chat2DB database tools through its stable `enabled_tools` contract. Pinned Codex v0.144.6 still injects three built-in MCP resource metadata helpers whenever a direct MCP server is configured. Chat2DB instructs the model not to call them, advertises empty resource catalogs, and treats only those side-effect-free metadata events for the isolated Chat2DB server as non-terminal. They never reach the database tool kernel; every other native, unknown, or foreign-server tool remains fail-closed.

## Alternatives considered

- App-server login plus token extraction into Spring AI: rejected because it restores the unsupported private transport and token/replay blocker.
- Private ChatGPT/SuperGrok adapters: rejected without a provider contract.
- Multiple JSON files with ordered writes: rejected because ordering is not a transaction.
- Keyring with file fallback: rejected because refresh tokens would become readable same-user files.
- Read-only or confirmed database tools: rejected by explicit user decision; automatic DML/DDL remains with disclosed unknown-outcome risk.

## Consequences and risks

- Two AI execution paths must share a backend route/history contract.
- Provider enablement depends on binary, protocol, capability, Keyring, packaging, and live-account gates.
- One active ChatGPT attempt limits concurrency in the first release.
- Target-database effects cannot be exactly once across a crash; unknown outcomes are terminal and require manual reconciliation.
- H2 becomes a new internal control ledger but does not replace existing Community data/history stores.

## ADR requirement

See `adr.md`. The decision is architecture-significant and security-sensitive.

## Integration order

1. Contracts and H2 ledger.
2. app-server protocol client/supervisor and gates.
3. model/account/logout services and JCEF lifecycle.
4. subscription route, attempt journal, MCP bridge.
5. API-key migration boundary.
6. renderer states, selector, history, migration UI.
7. packaging, recovery, security, and full regression.
