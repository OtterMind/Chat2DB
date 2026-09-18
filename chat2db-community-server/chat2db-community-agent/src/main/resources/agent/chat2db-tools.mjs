import { readFileSync, realpathSync } from "node:fs";
import { createReadTool, createEditTool, createWriteTool, createGrepTool, createFindTool, createLsTool,
  createBashTool, createPowerShellTool, createLocalBashOperations, createLocalPowerShellOperations } from "@earendil-works/pi-coding-agent";
import { join } from "node:path";
import { request as httpRequest } from "node:http";
import { executeShell, presentNative, checkedMutationPath, cleanupOutputSpools } from "./chat2db-output.mjs";

export default function (pi) {
  cleanupOutputSpools();
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
      const active = await request("/catalog", { signal: AbortSignal.timeout(10000) });
      pi.setActiveTools(active);
    },
  });
  const accessFile = join(process.env.PI_CODING_AGENT_DIR, "tools.json");
  const readAccess = () => JSON.parse(readFileSync(accessFile, "utf8"));
  const access = readAccess();
  const skillsFile = join(process.env.PI_CODING_AGENT_DIR, "skills.json");
  pi.on("resources_discover", () => ({
    skillPaths: JSON.parse(readFileSync(skillsFile, "utf8")).map(skill => skill.entryPath),
    promptPaths: [], themePaths: [],
  }));
  pi.registerCommand("chat2db-reload-skills", {
    description: "Reload skills between Chat2DB runs.",
    async handler(_args, ctx) {
      await ctx.reload();
      return;
    },
  });

  // A user decision can outlast fetch's transport timeout. Cancellation still uses the tool signal.
  function waitForUser(path, options) {
    const access = readAccess();
    const headers = { Authorization: `Bearer ${access.ticket}`, "Content-Type": "application/json" };
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
    const access = readAccess();
    const headers = { Authorization: `Bearer ${access.ticket}`, "Content-Type": "application/json" };
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

  pi.on("tool_result", event => {
    if (typeof event.details?.ok === "boolean") {
      return { isError: !event.details.ok };
    }
  });

  const factories = { read: createReadTool, edit: createEditTool, write: createWriteTool,
    grep: createGrepTool, find: createFindTool, ls: createLsTool,
    ...(process.platform === "win32" ? { powershell: createPowerShellTool } : { bash: createBashTool }) };
  for (const [name, createTool] of Object.entries(factories)) {
    const definition = createTool(process.cwd());
    const fileReader = name === "read" || name === "grep";
    const fileListing = name === "ls" || name === "find";
    const parameters = fileReader ? {
      ...definition.parameters,
      properties: { ...definition.parameters.properties,
        cursor: { type: "string", description: "Continue from nextCursor returned by the preceding read or search." } },
    } : definition.parameters;
    const executions = new Map();
    pi.on("before_agent_start", () => executions.clear());
    const skillFiles = access.userSkillDirectory && !["bash", "powershell"].includes(name)
      ? ` User skill source directory: ${access.userSkillDirectory}. File tools may read and edit user skills there without enabling workspace access. Bundled skills live outside that directory and are read-only. Other user paths require workspace permission.` : "";
    pi.registerTool({
      ...definition,
      description: definition.description + skillFiles,
      ...(fileReader ? {
        description: "Read or search a UTF-8 file in bounded pages. System tool-result and loaded skill files are always readable; other user files require the corresponding tool permission. Use the exact output.path and nextCursor from results." + skillFiles,
        promptSnippet: name === "read" ? "Read a file in bounded pages" : "Search file contents in bounded pages",
        promptGuidelines: ["Read/search output.path when a result is previewTruncated. Reuse nextCursor to continue; do not rerun a command merely to recover its full output."],
      } : fileListing ? {
        description: `${name === "ls" ? "List directory entries" : "Find entries by glob pattern"} within the permitted user directory, without following symlinks. Explicit limit bounds entries and returns hasMore; otherwise large listings are saved as JSONL with a preview and output.path.` + skillFiles,
        promptSnippet: name === "ls" ? "List directory entries" : "Find entries by glob pattern",
      } : name === "bash" || name === "powershell" ? {
        description: `Execute a ${name} command in the configured working directory only when the user has enabled this tool, and after user approval. Large stdout/stderr is saved with output.path and a bounded preview, including failed commands. Read or grep the saved file for more output.`,
      } : {}),
      parameters: withCallDescription(parameters),
      async execute(toolCallId, args, signal, onUpdate) {
        const nativeArgs = toolArguments(args);
        const serialized = JSON.stringify(nativeArgs);
        const previous = executions.get(toolCallId);
        if (previous) {
          if (previous.args !== serialized) throw new Error("Tool call arguments have changed");
          return previous.result;
        }
        const result = (async () => {
          let preparationId;
          const invoke = async (path, arguments_, requestSignal = signal) => {
            const response = await waitForUser(path, {
              method: "POST", body: JSON.stringify({ toolCallId, toolName: name,
                arguments: path === "/output" ? { ...arguments_, preparationId } : arguments_ }), signal: requestSignal,
            });
            return response.data;
          };
          const render = output => ({ content: [{ type: "text", text: JSON.stringify(output) }], details: output });
          if (fileReader || fileListing) return render(await invoke("/execute", nativeArgs));
          const prepared = await waitForUser("/prepare-native", {
            method: "POST", body: JSON.stringify({ toolCallId, toolName: name, arguments: toolArguments(args) }), signal,
          });
          const { workingDirectory } = prepared;
          preparationId = prepared.preparationId;
          signal?.throwIfAborted();
          if (realpathSync(workingDirectory) !== workingDirectory) {
            throw new Error("The working directory changed after authorization");
          }
          const publish = async (chunks, complete, warning, format = "text") => {
            // Finishing cancelled commands must not reuse the already-aborted execution signal.
            const finishSignal = AbortSignal.timeout(60000);
            const upload = await invoke("/output", { action: "begin", format }, finishSignal);
            try {
              for await (const chunk of chunks) {
                await invoke("/output", { action: "append", uploadId: upload.uploadId, content: chunk.toString("base64") }, finishSignal);
              }
            } catch {
              complete = false;
              warning = "Uploading output was interrupted; this file contains only the captured prefix.";
            }
            return invoke("/output", { action: "finish", uploadId: upload.uploadId, complete, warning }, AbortSignal.timeout(10000));
          };
          let output;
          if (name === "bash" || name === "powershell") {
            output = await executeShell({
              operations: name === "bash" ? createLocalBashOperations() : createLocalPowerShellOperations(),
              command: nativeArgs.command, timeout: nativeArgs.timeout, cwd: workingDirectory, signal, onUpdate, publish,
            });
          } else {
            nativeArgs.path = checkedMutationPath(workingDirectory, nativeArgs.path, prepared.allowedRoot || workingDirectory);
            const native = createTool(workingDirectory);
            let nativeOutput;
            let ok = true;
            try { nativeOutput = await native.execute(toolCallId, nativeArgs, signal); }
            catch (error) {
              ok = false;
              nativeOutput = { content: [{ type: "text", text: error instanceof Error ? error.message : String(error) }] };
            }
            output = await presentNative(nativeOutput, publish, ok);
          }
          try {
            return render(await invoke("/output", { action: "present", result: output }, AbortSignal.timeout(10000)));
          } catch {
            // A stopped runtime may revoke its ticket while output finishes. Preserve the execution outcome.
            output.warning = "Output finalization was unavailable; do not repeat the operation just to recover its output.";
            return render(output);
          }
        })();
        executions.set(toolCallId, { args: serialized, result });
        return result;
      },
    });
  }

}
