# HTTP servers

An HTTP server is a remote endpoint that speaks MCP over streamable HTTP. Chat2DB connects to it directly, so the address must be one the backend can reach.

## Fields

| Field | Meaning |
|---|---|
| `transport` | `http` |
| `url` | The MCP endpoint, for example `https://example.com/mcp`. |
| `header_names` | Names of the HTTP headers the server needs, for example `Authorization`. |
| `secrets` | Values for those header names, keyed by the same names. Never echo them back. |

## Rules

- HTTPS is required unless the host is `localhost`, `127.0.0.1` or `::1`; a plain-HTTP local server is a normal setup.
- Cloud metadata and link-local addresses are refused by the backend.
- URLs must not carry credentials in the user-info part; pass a token as a header instead.

## Example

```json
{
  "transport": "http",
  "name": "internal-docs",
  "url": "https://mcp.example.com/mcp",
  "header_names": ["Authorization"],
  "secrets": { "Authorization": "Bearer <the token the user pasted>" }
}
```

## Calling the tools

As with stdio servers, each tool is callable as `mcp__<server>__<tool>`; use the `callName` reported
by the tools rather than the server's own label.

## Authentication that is not supported yet

Servers that require an interactive OAuth login (a browser round trip, a device code, or dynamic client registration) cannot be connected in this version. Tell the user that plainly and offer a token- or API-key-based server instead; do not try to build the OAuth flow yourself.
