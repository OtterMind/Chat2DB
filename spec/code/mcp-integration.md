# External MCP servers

Pi V2 sessions can use tools from external Model Context Protocol servers. The user configures
them from the conversation; there is no settings page.

## Surfaces

```text
Pi runtime
 ├─ tool_search (extension, always active) ── activates a group on request
 ├─ mcp_manage group (6 tools, inactive by default) ── AgentMcpToolRegistry
 └─ mcp__<server>__<tool> (one group per server, active while the server is enabled)
        │
        └─ Pi extension `chat2db-tools.mjs` → loopback AgentGatewayServer → AgentToolGatewayService
                                                                              ├─ IMcpServerService (configuration)
                                                                              └─ IMcpToolDiscovery (MCP Java SDK)
                                                                                     ├─ stdio child process
                                                                                     └─ streamable HTTP endpoint
```

The catalogue the backend hands to a session (`tools.json`) tags every tool with `group` and
`defaultActive`. The extension registers everything and activates only `defaultActive` tools plus
`tool_search`; a model that needs the management tools calls `tool_search` once, and the added
definitions reach the next model request. Tools are only added, never removed mid-session, except
when a server is disabled or removed and the backend catalogue no longer lists them; the extension
re-reads the catalogue at the end of every run.

## Configuration

`McpServerStorageImpl` keeps one plain-text file in the agent data directory
(`<env base path>/storage/agent-v2/mcp/servers.json`, next to the skill directory, owner-only
permissions where the platform supports it). Nothing in a
skill or a prompt may hardcode that location: `IMcpServerService.configPath()` is reported by
`mcp_list_servers` and the `mcp-manager` skill tells the model to use it verbatim.

Per server: `name`, `transport` (`stdio`/`http`), `command`, `args`, `url`, `headerNames`,
`environmentKeys`, `secrets`, `enabled`, `policy` (`ASK`/`ALLOW`), `allowedTools`, cached `tools`,
`approvedCommandHash`, `updatedAt`. Values in `secrets` are stored as plain text and are never
returned by a tool, an event or a log line.

Validation: names are `[a-z0-9][a-z0-9_-]{0,31}`; an HTTP endpoint must use HTTPS unless it points at
loopback, must not carry user-info, and must not target link-local or cloud-metadata addresses
(`McpEndpointPolicy`); a stdio server needs a command.

## Approvals

`AgentApprovalDecision` carries the answer: `ALLOW_ONCE`, `ALLOW_TOOL`, `ALLOW_SERVER` or `DENY`.
Shell commands keep using `ALLOW_ONCE`/`DENY`.

- The six management tools ask for every call except `mcp_list_servers`; `mcp_test_server` asks only
  when it would start a stdio command whose launch line is not approved yet.
- An approved `mcp_add_server`/`mcp_update_server` records the launch line it just approved.
- A not-yet-remembered external tool asks once. `ALLOW_TOOL` adds the tool to `allowedTools`,
  `ALLOW_SERVER` sets `policy: ALLOW`; both are visible in the configuration file and can be revoked
  with `mcp_set_policy`.
- Changing the command, arguments, URL, environment variable names or header names clears
  `approvedCommandHash` and the cached tool list, so the user approves the new launch line.

## Connections

`SdkMcpToolDiscovery` uses the MCP Java SDK the project already ships. Per server it keeps one
connection, opened lazily on discovery or on the first call, and dropped when the configuration
changes, the server is disabled or removed, or a call fails. Transports offer every protocol revision
the SDK implements, ordered oldest to newest, because the SDK requests the last entry of that list
and accepts any of them; the default (2024-11-05 only) is rejected by current servers.

stdio children inherit the parent environment plus the configured variables; stderr is consumed by the
transport; the process is closed with its connection.

## Limits

- A server that needs an interactive OAuth login cannot be connected yet; the tools report that
  plainly.
- Only `tools/list` and `tools/call` are used. Resources, prompts, sampling, elicitation and the
  experimental task API are not surfaced.
- Tool results are text: non-text content blocks are noted and omitted, and the combined text is
  capped before it reaches the model. Large results still pass through the ordinary output spooling
  of the tool gateway.
- Tool names longer than 100 characters are truncated with a short digest suffix, because the runtime
  rejects longer names.
