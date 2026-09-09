import { readFileSync, realpathSync } from "node:fs";
import { createReadTool, createEditTool, createWriteTool, createGrepTool, createFindTool, createLsTool,
  createBashTool, createPowerShellTool } from "@earendil-works/pi-coding-agent";
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
      promptSnippet: tool.promptSnippet,
      promptGuidelines: tool.promptGuidelines,
      async execute(toolCallId, args, signal) {
        const response = await request("/execute", {
          method: "POST",
          body: JSON.stringify({ toolCallId, toolName: tool.name, arguments: args }),
          signal,
        });
        const result = response.data;
        return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
      },
    });
  }

  const databaseTools = new Set(access.tools.map(tool => tool.name));
  pi.on("tool_result", event => {
    if (databaseTools.has(event.toolName) && typeof event.details?.ok === "boolean") {
      return { isError: !event.details.ok };
    }
  });

  const factories = { read: createReadTool, edit: createEditTool, write: createWriteTool,
    grep: createGrepTool, find: createFindTool, ls: createLsTool,
    ...(process.platform === "win32" ? { powershell: createPowerShellTool } : { bash: createBashTool }) };
  for (const [name, createTool] of Object.entries(factories)) {
    const definition = createTool(process.cwd());
    const executions = new Map();
    pi.on("before_agent_start", () => executions.clear());
    pi.registerTool({
      ...definition,
      async execute(toolCallId, args, signal, onUpdate) {
        const serialized = JSON.stringify(args);
        const previous = executions.get(toolCallId);
        if (previous) {
          if (previous.args !== serialized) throw new Error("Tool call arguments have changed");
          return previous.result;
        }
        const result = (async () => {
          const { workingDirectory } = await request("/prepare-native", {
            method: "POST", body: JSON.stringify({ toolCallId, toolName: name, arguments: args }), signal,
          });
          signal?.throwIfAborted();
          if (realpathSync(workingDirectory) !== workingDirectory) {
            throw new Error("The working directory changed after authorization");
          }
          const native = createTool(workingDirectory);
          const output = await native.execute(toolCallId, args, signal, onUpdate);
          return { ...output, details: { ...output.details, workingDirectory } };
        })();
        executions.set(toolCallId, { args: serialized, result });
        return result;
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
