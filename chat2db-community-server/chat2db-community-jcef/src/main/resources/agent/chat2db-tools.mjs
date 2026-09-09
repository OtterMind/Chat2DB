import { readFileSync } from "node:fs";
import { join } from "node:path";

export default function (pi) {
  const access = JSON.parse(readFileSync(join(process.env.PI_CODING_AGENT_DIR, "tools.json"), "utf8"));
  const headers = { Authorization: `Bearer ${access.ticket}`, "Content-Type": "application/json" };

  async function request(path, options = {}) {
    const response = await fetch(access.baseUrl + path, { ...options, headers });
    const body = await response.json();
    if (!response.ok || body.success === false) {
      throw new Error(body.errorMessage || `Tool request failed (${response.status})`);
    }
    return body;
  }

  for (const tool of access.tools) {
    pi.registerTool({
      name: tool.name,
      label: tool.name,
      description: tool.description,
      parameters: tool.parameters,
      async execute(toolCallId, args, signal) {
        const result = await request("/execute", {
          method: "POST",
          body: JSON.stringify({ toolCallId, toolName: tool.name, arguments: args }),
          signal,
        });
        return { content: [{ type: "text", text: result.content }], details: {} };
      },
    });
  }

  const refreshTools = async () => {
    const active = await request("/catalog");
    pi.setActiveTools(active);
  };
  pi.on("session_start", refreshTools);
  pi.on("before_agent_start", refreshTools);
}
