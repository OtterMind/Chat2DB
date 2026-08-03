# ADR — Supported sidecar route for subscription-backed AI

## Status

Accepted for implementation; provider remains disabled until release gates pass.

## Context

Consumer ChatGPT access is managed by Codex app-server, while generic OpenAI API billing is separate. Reusing OAuth tokens in Spring AI would depend on private transport behavior and make tool retry safety unobservable. Chat2DB also needs crash-consistent local state that current independent JSON stores cannot provide.

## Decision

ChatGPT subscription requests use a pinned Codex app-server as a complete sidecar route. OAuth credentials remain in OS Keyring. Chat2DB persists only non-secret facts in an H2 ledger and exposes database tools through a dedicated loopback MCP bridge under a single active attempt lease. API-key models remain on Spring AI.

The app-server MCP configuration uses `enabled_tools` to expose only Chat2DB's seven database tools. The pinned binary's unavoidable, side-effect-free MCP resource metadata helpers are handled as an empty-catalog compatibility surface and are never forwarded to database execution. All other native and unrecognized tool events still interrupt the turn with the existing safe denial.

## Consequences

- Subscription and API-key requests have separate adapters but common backend model/history identities.
- app-server packaging and protocol compatibility become explicit release inputs.
- Provider enablement fails closed.
- Automatic DML/DDL remains a documented residual risk with no exact-once claim.
