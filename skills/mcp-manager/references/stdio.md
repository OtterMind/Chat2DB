# stdio servers

A stdio server is a local command that speaks MCP over its standard input and output. Chat2DB starts it on demand and stops it when the server is disabled or removed.

## Fields

| Field | Meaning |
|---|---|
| `transport` | `stdio` |
| `command` | The executable, for example `npx`, `uvx`, `python3` or an absolute path. |
| `args` | One entry per argument. Do not merge arguments into a single string. |
| `environment_keys` | Names of the environment variables the command needs. |
| `secrets` | Values for those names, keyed by the same names. Never echo them back to the user. |

## Example

```json
{
  "transport": "stdio",
  "name": "filesystem",
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me/Documents"],
  "environment_keys": [],
  "secrets": {}
}
```

A server that needs a token:

```json
{
  "transport": "stdio",
  "name": "github",
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-github"],
  "environment_keys": ["GITHUB_PERSONAL_ACCESS_TOKEN"],
  "secrets": { "GITHUB_PERSONAL_ACCESS_TOKEN": "<the token the user pasted>" }
}
```

## What the user approves

The first time the server runs, the user sees the exact command line (`command` plus `args`) and the environment variable names. Values are never shown. An approved command stays approved until the command, the arguments or the variable names change.
