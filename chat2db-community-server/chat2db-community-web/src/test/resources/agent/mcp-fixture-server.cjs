// Minimal MCP stdio server used by SdkMcpToolDiscoveryTest: one echo tool over JSON-RPC lines.
const readline = require("node:readline");

const tools = [{
  name: "echo",
  description: "Echo the provided text back to the caller.",
  inputSchema: {
    type: "object",
    properties: { text: { type: "string", description: "Text to echo." } },
    required: ["text"],
    additionalProperties: false,
  },
  annotations: { readOnlyHint: true, destructiveHint: false },
}];

const reply = message => process.stdout.write(JSON.stringify(message) + "\n");

readline.createInterface({ input: process.stdin }).on("line", line => {
  if (!line.trim()) return;
  let request;
  try {
    request = JSON.parse(line);
  } catch {
    return;
  }
  if (request.method === "initialize") {
    // The client must offer the newest revision it can speak; this server only accepts 2025-06-18.
    if (request.params?.protocolVersion !== "2025-06-18") {
      reply({ jsonrpc: "2.0", id: request.id, error: { code: -32602,
        message: "Unsupported protocol version from the client: " + String(request.params?.protocolVersion) } });
      return;
    }
    reply({ jsonrpc: "2.0", id: request.id, result: {
      protocolVersion: "2025-06-18",
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "chat2db-fixture", version: "1.0.0" },
    } });
    return;
  }
  if (request.method === "tools/list") {
    reply({ jsonrpc: "2.0", id: request.id, result: { tools } });
    return;
  }
  if (request.method === "tools/call") {
    const text = String(request.params?.arguments?.text ?? "");
    if (request.params?.name !== "echo") {
      reply({ jsonrpc: "2.0", id: request.id, result: {
        content: [{ type: "text", text: "Unknown tool" }], isError: true,
      } });
      return;
    }
    reply({ jsonrpc: "2.0", id: request.id, result: {
      content: [{ type: "text", text: "echo:" + text }], isError: false,
    } });
    return;
  }
  if (request.id === undefined) return; // A notification needs no response.
  reply({ jsonrpc: "2.0", id: request.id, error: { code: -32601, message: "Method not found" } });
});
