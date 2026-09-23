// Minimal MCP server over streamable HTTP for SdkMcpToolDiscoveryTest and local acceptance runs.
// Usage: node mcp-http-fixture-server.cjs [port]
const http = require("node:http");
const crypto = require("node:crypto");

const port = Number(process.argv[2] || 3117);
const tools = [
  {
    name: "echo",
    description: "Echo the provided text back to the caller.",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", description: "Text to echo." } },
      required: ["text"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, destructiveHint: false },
  },
  {
    name: "big",
    description: "Return a large text payload, for testing result handling.",
    inputSchema: {
      type: "object",
      properties: { kilobytes: { type: "integer", minimum: 1, maximum: 1024 } },
      required: ["kilobytes"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, destructiveHint: false },
  },
];

const call = params => {
  if (params?.name === "echo") {
    return { content: [{ type: "text", text: "echo:" + String(params.arguments?.text ?? "") }], isError: false };
  }
  if (params?.name === "big") {
    const kilobytes = Math.min(Math.max(Number(params.arguments?.kilobytes ?? 64), 1), 1024);
    const line = "0123456789".repeat(10); // 100 characters per line
    const lines = Math.ceil((kilobytes * 1024) / (line.length + 1));
    return { content: [{ type: "text", text: Array.from({ length: lines }, () => line).join("\n") }], isError: false };
  }
  return { content: [{ type: "text", text: "Unknown tool" }], isError: true };
};

http
  .createServer((request, response) => {
    if (request.method === "POST" && request.url === "/mcp") {
      let body = "";
      request.on("data", chunk => {
        body += chunk;
      });
      request.on("end", () => {
        let message;
        try {
          message = JSON.parse(body);
        } catch {
          response.writeHead(400).end();
          return;
        }
        if (message.method === "notifications/initialized" || message.id === undefined) {
          response.writeHead(202).end();
          return;
        }
        const headers = { "Content-Type": "application/json", "Mcp-Session-Id": request.headers["mcp-session-id"] || crypto.randomUUID() };
        const send = payload => {
          response.writeHead(200, headers);
          response.end(JSON.stringify({ jsonrpc: "2.0", id: message.id, ...payload }));
        };
        if (message.method === "initialize") {
          send({ result: {
            protocolVersion: String(message.params?.protocolVersion ?? "2025-06-18"),
            capabilities: { tools: { listChanged: false } },
            serverInfo: { name: "chat2db-http-fixture", version: "1.0.0" },
          } });
          return;
        }
        if (message.method === "tools/list") {
          send({ result: { tools } });
          return;
        }
        if (message.method === "tools/call") {
          send({ result: call(message.params) });
          return;
        }
        send({ error: { code: -32601, message: "Method not found" } });
      });
      return;
    }
    if (request.method === "GET" && request.url === "/mcp") {
      // The optional server-to-client stream stays open without events.
      response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive" });
      return;
    }
    if (request.method === "DELETE" && request.url === "/mcp") {
      response.writeHead(204).end();
      return;
    }
    response.writeHead(404).end();
  })
  .listen(port, "127.0.0.1", () => process.stdout.write("mcp-http-fixture listening on 127.0.0.1:" + port + "\n"));
