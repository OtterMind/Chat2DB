---
name: mcp-manager
description: Connect, inspect, change or remove external MCP (Model Context Protocol) servers for the user, explain where the configuration file lives and how approvals work. Use when the user asks to add or manage an MCP server, when a configured server is not working, or when the user asks which MCP servers exist.
---

# MCP manager

External MCP servers contribute their own tools to this conversation. There is no settings page: you manage servers with the `mcp_*` tools and the user confirms each change in the conversation.

## Before you start

- The `mcp_*` tools may not be active yet. Call `tool_search` with the query `mcp server` once, then continue.
- Ask for exactly what you need: the local command for a stdio server, or the URL for an HTTP server, plus the names of the environment variables or headers it needs. Never invent a command, a URL or a token.
- **Never state a configuration path from memory or from this document.** Call `mcp_list_servers` and use the `configPath` it returns, verbatim, whenever the user asks where the file is or wants to edit it. The location differs per product and per environment.

## Adding a server

1. `mcp_list_servers` – check for an existing name and read `configPath`.
2. `mcp_add_server` with `transport`, `command`/`args` or `url`, the declared `environment_keys` or `header_names`, and `secrets` holding the values the user provided.
3. The user approves the change. Say what they are approving: for a stdio server it is the exact command line that will run.
4. Report the returned `connected` result: the tool names when it worked, and the exact error plus the checks in [troubleshooting](references/troubleshooting.md) when it did not.
5. Tell the user the tools are usable in this conversation from the next step on; the first call of an external tool asks for approval once more.

## Changing and removing

- `mcp_update_server` changes only the fields you pass. Changing the command, URL, arguments, environment variable names or header names invalidates the approved launch line, so the user approves again.
- `mcp_remove_server` stops the server and removes its tools.
- `mcp_set_policy` controls approvals: `ASK` (default) asks once per tool, `ALLOW` stops asking for the whole server, and `allowed_tools` forgets tools the user allowed earlier.

## Approvals

Every external tool asks the user once; the user can allow it once, allow that tool for good, allow the whole server, or refuse. Never claim an approval exists and never retry a refused call without a new user request.

See [stdio servers](references/stdio.md), [HTTP servers](references/http.md), [approval policy](references/policy.md) and [troubleshooting](references/troubleshooting.md).
