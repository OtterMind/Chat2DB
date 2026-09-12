import { readFileSync, realpathSync } from "node:fs";
import { createReadTool, createEditTool, createWriteTool, createGrepTool, createFindTool, createLsTool,
  createBashTool, createPowerShellTool } from "@earendil-works/pi-coding-agent";
import { join } from "node:path";
import { request as httpRequest } from "node:http";

export default function (pi) {
  const callDescription = {
    type: "string", minLength: 1, maxLength: 240,
    description: "Briefly explain what you are doing with this tool and what the result will provide to the user.",
  };
  const withCallDescription = parameters => parameters?.type !== "object" ? parameters : ({
    ...parameters,
    properties: { ...parameters.properties, description: callDescription },
    required: [...new Set([...(parameters.required || []), "description"])],
  });
  const toolArguments = args => {
    const toolArgs = { ...(args || {}) };
    delete toolArgs.description;
    return toolArgs;
  };
  pi.registerCommand("chat2db-refresh-model", {
    description: "Reload the model configuration selected by Chat2DB for the next message.",
    async handler(_args, ctx) {
      await ctx.modelRegistry.refresh(AbortSignal.timeout(10000));
    },
  });
  const access = JSON.parse(readFileSync(join(process.env.PI_CODING_AGENT_DIR, "tools.json"), "utf8"));
  const headers = { Authorization: `Bearer ${access.ticket}`, "Content-Type": "application/json" };

  // A user decision can outlast fetch's transport timeout. Cancellation still uses the tool signal.
  function waitForUser(path, options) {
    return new Promise((resolve, reject) => {
      const cleanup = () => options.signal?.removeEventListener("abort", abort);
      const fail = error => { cleanup(); reject(error); };
      const request = httpRequest(access.baseUrl + path, {
        method: options.method, headers, timeout: 0,
      }, response => {
        let text = "";
        response.setEncoding("utf8");
        response.on("data", chunk => { text += chunk; });
        response.on("error", fail);
        response.on("aborted", () => fail(new Error("Question connection closed")));
        response.on("end", () => {
          cleanup();
          try {
            const body = JSON.parse(text);
            if (response.statusCode === 403 && String(body.errorMessage || "").toLowerCase().includes("ticket")) {
              setTimeout(() => process.exit(86), 0);
            }
            if (response.statusCode >= 400 || body.success === false) {
              throw new Error(body.errorMessage || `Tool request failed (${response.statusCode})`);
            }
            resolve(body);
          } catch (error) { reject(error); }
        });
      });
      const abort = () => {
        fail(options.signal.reason || new Error("Question cancelled"));
        request.destroy();
      };
      request.on("error", fail);
      if (options.signal?.aborted) { abort(); return; }
      options.signal?.addEventListener("abort", abort, { once: true });
      request.end(options.body);
    });
  }

  async function request(path, options = {}) {
    const response = await fetch(access.baseUrl + path, { ...options, headers });
    const body = await response.json();
    if (response.status === 403 && String(body.errorMessage || "").toLowerCase().includes("ticket")) {
      setTimeout(() => process.exit(86), 0);
    }
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
      parameters: withCallDescription(tool.parameters),
      promptSnippet: tool.promptSnippet,
      promptGuidelines: tool.promptGuidelines,
      async execute(toolCallId, args, signal) {
        const execute = waitForUser;
        const response = await execute("/execute", {
          method: "POST",
          body: JSON.stringify({ toolCallId, toolName: tool.name, arguments: toolArguments(args) }),
          signal,
        });
        const result = response.data;
        return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
      },
    });
  }

  const hostTools = new Set(access.tools.map(tool => tool.name));
  pi.on("tool_result", event => {
    if (hostTools.has(event.toolName) && typeof event.details?.ok === "boolean") {
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
      parameters: withCallDescription(definition.parameters),
      async execute(toolCallId, args, signal, onUpdate) {
        const nativeArgs = toolArguments(args);
        const serialized = JSON.stringify(nativeArgs);
        const previous = executions.get(toolCallId);
        if (previous) {
          if (previous.args !== serialized) throw new Error("Tool call arguments have changed");
          return previous.result;
        }
        const result = (async () => {
          const { workingDirectory } = await waitForUser("/prepare-native", {
            method: "POST", body: JSON.stringify({ toolCallId, toolName: name, arguments: toolArguments(args) }), signal,
          });
          signal?.throwIfAborted();
          if (realpathSync(workingDirectory) !== workingDirectory) {
            throw new Error("The working directory changed after authorization");
          }
          const native = createTool(workingDirectory);
          const output = await native.execute(toolCallId, nativeArgs, signal, onUpdate);
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
