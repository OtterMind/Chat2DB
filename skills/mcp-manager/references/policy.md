# Approval policy

Every tool of an external server asks the user before it runs, unless the user has already allowed it.

## Answers on the card

| Answer | Effect |
|---|---|
| Allow once | Runs this call only. |
| Always allow this tool | Remembers this tool of this server; the other tools still ask. |
| Always allow this server | Stops asking for every tool of this server. |
| Deny | Refuses this call. The model receives a refusal and must not retry it. |

## Changing the policy

- `mcp_set_policy` with `policy: "ALLOW"` stops asking for the whole server.
- `mcp_set_policy` with `policy: "ASK"` and `allowed_tools: [...]` keeps only the listed tools approved.
- `mcp_set_policy` with `policy: "ASK"` and `allowed_tools: []` asks for every tool again.
- `mcp_update_server` with `enabled: false` keeps the configuration but removes its tools from the conversation.

The remembered answers live in the configuration file (`configPath` from `mcp_list_servers`), so the user can also edit or clear them by hand. The file is plain text and is readable only by the user account that runs Chat2DB.

## What you must not do

- Do not claim a tool is approved when it is not; the card is the only source of truth.
- Do not read secret values back to the user, and do not write them into a summary, a file or a message.
- Do not change a policy to avoid an approval the user would otherwise see.
