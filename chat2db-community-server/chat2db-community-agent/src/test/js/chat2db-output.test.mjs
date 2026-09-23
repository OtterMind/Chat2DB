import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, realpathSync, rmSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { executeShell, presentNative, boundedText, checkedMutationPath } from "../../main/resources/agent/chat2db-output.mjs";

const reference = { mode: "file", artifactId: "fixture", path: "/result/output.txt", complete: true };
async function run(chunks, options = {}) {
  let calls = 0;
  let uploaded;
  let publishedComplete;
  const updates = [];
  const result = await executeShell({
    command: "fixture", cwd: process.cwd(), ...options,
    operations: { async exec(_command, _cwd, { onData }) {
      calls++;
      for (const chunk of chunks) onData(Buffer.from(chunk));
      if (options.failure) throw options.failure;
      return { exitCode: options.exitCode ?? 0 };
    } },
    onUpdate: update => updates.push(update),
    async publish(stream, complete) {
      if (options.publishFailure) throw new Error("disk unavailable");
      const bytes = [];
      for await (const chunk of stream) bytes.push(chunk);
      uploaded = Buffer.concat(bytes);
      publishedComplete = complete;
      return { ...reference, complete };
    },
  });
  assert.equal(calls, 1, "Saving output must never repeat execution");
  assert.ok(updates.every(update => Buffer.byteLength(update.content[0].text) <= 8192));
  return { result, uploaded, publishedComplete };
}

const small = await run(["first\n", "第二行\n"]);
assert.equal(small.result.data.text, "first\n第二行\n");
assert.equal(small.uploaded, undefined);
assert.equal(small.result.ok, true);

const payload = Buffer.from("早期唯一结果\n" + "中文 full output\n".repeat(20000) + "最终诊断\n");
const large = await run([payload.subarray(0, 17), payload.subarray(17)]);
assert.deepEqual(large.uploaded, payload);
assert.equal(large.result.output.path, reference.path);
assert.equal(large.result.data.text.endsWith("最终诊断\n"), true);
assert.equal(large.result.data.text.includes("早期唯一结果"), false);
assert.equal(large.result.data.text.includes("�"), false);
assert.equal(large.publishedComplete, true);

const failed = await run([payload], { exitCode: 7 });
assert.equal(failed.result.ok, false);
assert.equal(failed.result.data.exitCode, 7);
assert.deepEqual(failed.uploaded, payload);
assert.equal(failed.publishedComplete, true, "A nonzero exit can still have fully captured output");

for (const [failure, outcome] of [[new Error("aborted"), "cancelled"], [new Error("timeout:2"), "timeout"]]) {
  const stopped = await run([payload], { failure });
  assert.equal(stopped.result.ok, false);
  assert.equal(stopped.result.data.outcome, outcome);
  assert.equal(stopped.publishedComplete, false);
  assert.deepEqual(stopped.uploaded, payload);
}

const limited = await run([payload], { maxCaptureBytes: 48000 });
assert.equal(limited.result.ok, true, "The capture limit does not change successful command execution");
assert.equal(limited.uploaded.length, 48000);
assert.equal(limited.publishedComplete, false);
assert.match(limited.result.warning, /partial/);

const unavailable = await run([payload], { publishFailure: true });
assert.equal(unavailable.result.ok, true);
assert.match(unavailable.result.warning, /do not repeat/);
const escaped = await run(["\0".repeat(20000)]);
assert.equal(escaped.uploaded.length, 20000);
assert.ok(Buffer.byteLength(JSON.stringify(escaped.result.data.text)) <= 8192);
assert.ok(Buffer.byteLength(JSON.stringify(boundedText("\0😀".repeat(10000), 8192, true))) <= 8192);

const original = { content: [{ type: "text", text: "File changed" }], details: { diff: "変更\0".repeat(300000) } };
let nativeUpload;
const native = await presentNative(original, async (chunks, complete, warning, format) => {
  assert.equal(format, "json"); assert.equal(complete, true); assert.equal(warning, null);
  const parts = [];
  for await (const chunk of chunks) { assert.ok(chunk.length <= 48 * 1024); parts.push(chunk); }
  nativeUpload = JSON.parse(Buffer.concat(parts).toString("utf8"));
  return reference;
});
assert.deepEqual(nativeUpload.data, original);
assert.equal(native.ok, true);
assert.ok(Buffer.byteLength(JSON.stringify(native)) < 10 * 1024);
const nativeFailure = await presentNative(original, async () => { throw new Error("disk unavailable"); });
assert.equal(nativeFailure.ok, true);
assert.equal(nativeFailure.output.mode, "unavailable");
const partial = await presentNative({ content: [], details: { truncation: { truncated: true } } }, () => {});
assert.equal(partial.output.complete, false);
assert.match(partial.output.warning, /already truncated/);
const directory = mkdtempSync(join(tmpdir(), "chat2db-output-path-test-"));
try {
  const root = realpathSync(directory);
  const workspace = join(root, "workspace"); mkdirSync(workspace);
  assert.equal(checkedMutationPath(workspace, "new/file.txt"), join(workspace, "new/file.txt"));
  symlinkSync(workspace, join(root, "workspace-alias"));
  assert.equal(checkedMutationPath(workspace, join(root, "workspace-alias/new.txt")), join(workspace, "new.txt"));
  assert.throws(() => checkedMutationPath(workspace, "../outside.txt"), /outside/);
  symlinkSync(root, join(workspace, "link"));
  assert.throws(() => checkedMutationPath(workspace, "link/new.txt"), /changed/);
  symlinkSync(join(root, "not-created.txt"), join(workspace, "dangling"));
  assert.throws(() => checkedMutationPath(workspace, "dangling"));
  const skills = join(root, "用户 skills"); mkdirSync(skills);
  assert.equal(checkedMutationPath(workspace, join(skills, "my-skill/SKILL.md"), skills), join(skills, "my-skill/SKILL.md"));
  assert.throws(() => checkedMutationPath(workspace, join(skills, "my-skill/SKILL.md")), /outside/);
  assert.throws(() => checkedMutationPath(workspace, join(root, "用户 skills-other/file"), skills), /outside/);
  symlinkSync(workspace, join(skills, "escape"));
  assert.throws(() => checkedMutationPath(workspace, join(skills, "escape/file"), skills), /changed/);
} finally { rmSync(directory, { recursive: true, force: true }); }
console.log("Source output capture, UTF-8 previews, failure/cancellation, quotas and publish failure passed");
