import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { EventEmitter } from "node:events";
import vm from "node:vm";

const registered = new Map();
let destroyed = false;
const request = new EventEmitter();
request.end = () => {};
// Some runtimes close the HTTP request on abort without emitting an error.
request.destroy = () => { destroyed = true; request.emit("close"); };
const context = vm.createContext({ process: { env: { PI_CODING_AGENT_DIR: "/fixture" }, cwd: () => "/fixture", platform: "linux" }, AbortController });
const imports = {
  "node:fs": { readFileSync: () => JSON.stringify({ baseUrl: "http://127.0.0.1", ticket: "fixture", tools: [{ name: "askUserQuestion" }] }), realpathSync: value => value },
  "node:path": { join: (...parts) => parts.join("/") },
  "node:http": { request: () => request },
  "@earendil-works/pi-coding-agent": Object.fromEntries(["Read", "Edit", "Write", "Grep", "Find", "Ls", "Bash", "PowerShell"].map(name => ["create" + name + "Tool", () => ({ name: name.toLowerCase() })])),
};
const source = readFileSync(new URL("../../main/resources/agent/chat2db-tools.mjs", import.meta.url), "utf8");
const module = new vm.SourceTextModule(source, { context });
await module.link(specifier => {
  const values = imports[specifier]; assert.ok(values, specifier);
  return new vm.SyntheticModule(Object.keys(values), function () {
    for (const [key, value] of Object.entries(values)) this.setExport(key, value);
  }, { context });
});
await module.evaluate();
module.namespace.default({ registerTool: tool => registered.set(tool.name, tool), on: () => {} });
const controller = new AbortController();
const execution = registered.get("askUserQuestion").execute("call", { question: "Choose", options: [] }, controller.signal);
controller.abort(new Error("cancelled by user"));
let timer;
try {
  await assert.rejects(Promise.race([execution, new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error("Question did not respond to cancellation")), 1000);
  })]), /cancelled by user/);
  assert.equal(destroyed, true);
} finally { clearTimeout(timer); }
console.log("Question HTTP transport cancellation passed");
