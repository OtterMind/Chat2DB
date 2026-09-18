# Troubleshooting

Work through these checks with the user instead of guessing. Report the exact error text that `mcp_test_server` or `mcp_add_server` returned.

## The command cannot be started

- Is the executable installed and on `PATH` for the Chat2DB process? A GUI application often has a shorter `PATH` than a terminal. An absolute path or `npx --yes` is more reliable than a bare name.
- Does the command actually speak MCP over stdio? A plain script or a CLI without MCP support answers with text that is not JSON-RPC; the connection then fails immediately.
- Do the `args` match what the server documents? Every argument must be its own array entry.

## The connection is refused or times out

- For HTTP: check the URL, including the trailing path (`/mcp`, `/sse`), and whether the host is reachable from this machine.
- A plain `http://` URL is accepted only for localhost; use `https://` otherwise.
- If the server sits behind a VPN or a corporate proxy, the backend may not reach it even when a browser can.

## Authentication fails

- Confirm the header or environment variable name the server expects; a wrong name is silent until the server returns 401/403.
- Ask the user for a fresh token when the old one expired, and store it with `mcp_update_server` and `secrets`; never print it.
- Servers that need an interactive OAuth login are not supported in this version. Report that limitation instead of trying workarounds.

## The server connects but has no tools

- Some servers expose tools only after a resource or scope is configured (a repository, a directory, a workspace).
- Run `mcp_test_server` again after changing the configuration; the tool list is refreshed on every test.

## The tools do not appear in the conversation

- The tools join the catalogue as soon as the server is connected; they become callable from the next step.
- If the user disabled the server, or the policy is `DENY`, the tools stay hidden by design.
- After adding a server, check `mcp_list_servers` for its `enabled` flag before investigating anything else.

## The user wants to inspect or repair the file by hand

Give them the `configPath` from `mcp_list_servers`, verbatim. The file is plain text; a malformed file is reported as unreadable and is never overwritten by a read.
