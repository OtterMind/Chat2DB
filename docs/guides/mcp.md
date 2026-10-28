# MCP

Chat2DB Community exposes its database tools through a local MCP HTTP server.
The server uses Streamable HTTP at `/mcp`.

## Enable MCP in the desktop app

1. Open **Settings > MCP Service**.
2. Enable the service and restart Chat2DB when prompted.
3. Copy the token shown in **MCP Token**. Treat it like a password.
4. In the datasource tree, open the context menu and choose **Copy MCP config**.
   Paste the copied JSON into your MCP client.

The copied configuration already contains the local endpoint and the desktop
authentication header. For a client that does not support importing a config,
the endpoint is normally `http://127.0.0.1:10825/mcp`.

## Configure an MCP client manually

Use a configuration with the following shape and replace `<token>` with the
token copied from Chat2DB:

```json
{
  "mcpServers": {
    "chat2db": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:10825/mcp",
      "headers": {
        "X-Chat2DB-MCP-Token": "<token>"
      }
    }
  }
}
```

Do not commit the token or share the generated configuration publicly.

## Available tools

MCP clients discover these tools with `tools/list`:

| Tool | Purpose |
| --- | --- |
| `list_all_datasources` | List configured Chat2DB datasources. |
| `list_all_databases` | List databases for a datasource. |
| `list_all_schemas` | List schemas for a database. |
| `list_all_tables` | List tables for a database and optional schema. |
| `get_tables_schema` | Return table DDL or structured column details. |
| `execute_sql` | Execute SQL and return query rows or an update count. |
| `text2sql` | Convert a natural-language question into SQL. |

For datasource-scoped tools, call `list_all_datasources` first and pass the
returned `dataSourceId` as a JSON number. Then use `list_all_databases` and,
when needed, `list_all_schemas` before requesting tables or executing SQL.

## Test the connection with curl

Set the token in your shell first:

```bash
export MCP_TOKEN='<token copied from Chat2DB>'
```

Initialize an MCP session:

```bash
curl --request POST 'http://127.0.0.1:10825/mcp' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --header "X-Chat2DB-MCP-Token: ${MCP_TOKEN}" \
  --data '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-06-18",
      "capabilities": {},
      "clientInfo": {"name": "curl-client", "version": "1.0.0"}
    }
  }'
```

The response includes an `Mcp-Session-Id` header when the server creates a
stateful session. Pass that header on later requests when your MCP client
requires a stateful session. For a stateless request, call a tool with the MCP
JSON-RPC method `tools/call`:

```bash
curl --request POST 'http://127.0.0.1:10825/mcp' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --header "X-Chat2DB-MCP-Token: ${MCP_TOKEN}" \
  --data '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "text2sql",
      "arguments": {
        "question": "查询 saledetail 表总共有多少条数据",
        "dataSourceId": 1,
        "databaseName": "yida23"
      }
    }
  }'
```

`ai/textToSql` is an HTTP API operation name, not an MCP JSON-RPC method.
When calling MCP directly, use `tools/call` and put the Chat2DB tool name in
`params.name`. A `dataSourceId` such as `1` must be a JSON number, not the
string `"1"`.

## Troubleshooting

- `401 Unauthorized`: enable MCP, restart the desktop app, and copy the
  current token again. Resetting the token invalidates the previous one.
- Connection refused: confirm Chat2DB is running with MCP enabled and that
  the client is using the configured port.
- A tool reports missing datasource context: call `list_all_datasources`
  first, then pass `dataSourceId` and `databaseName` explicitly.
- The response is an event stream: keep `Accept: application/json,
  text/event-stream`; MCP clients normally parse the event stream for you.
