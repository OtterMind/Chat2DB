import { closeSync, mkdtempSync, openSync, rmSync, writeSync, createReadStream, lstatSync, realpathSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, dirname, relative, isAbsolute } from "node:path";
import { StringDecoder } from "node:string_decoder";

const INLINE_BYTES = 32 * 1024;
const PREVIEW_BYTES = 8 * 1024;
const MAX_CAPTURE_BYTES = 256 * 1024 * 1024;

export function cleanupOutputSpools(directory = process.env.PI_CODING_AGENT_DIR) {
  if (!directory) return;
  let entries;
  try { entries = readdirSync(directory); } catch { return; }
  for (const name of entries) {
    const match = /^\.output-spool-(\d+)-[A-Za-z0-9]+$/.exec(name);
    if (!match) continue;
    try { process.kill(Number(match[1]), 0); }
    catch (error) {
      if (error.code === "ESRCH") {
        try { rmSync(join(directory, name), { recursive: true, force: true }); } catch { /* Retry on a later launch. */ }
      }
    }
  }
}

export function checkedMutationPath(cwd, value, allowedRoot = cwd) {
  if (typeof value !== "string" || !value) throw new Error("File path is required");
  let path = resolve(cwd, value);
  for (let ancestor = path; ancestor; ancestor = dirname(ancestor)) {
    try {
      if (realpathSync(ancestor) === allowedRoot) { path = resolve(allowedRoot, relative(ancestor, path)); break; }
    } catch { /* The requested destination may not exist yet. */ }
    if (dirname(ancestor) === ancestor) break;
  }
  if (realpathSync(allowedRoot) !== allowedRoot) throw new Error("The authorized root changed after authorization");
  const within = relative(allowedRoot, path);
  if (within === ".." || within.startsWith("../") || within.startsWith("..\\") || isAbsolute(within)) {
    throw new Error("File path is outside the authorized working directory");
  }
  let ancestor = path;
  while (true) {
    try { lstatSync(ancestor); break; }
    catch (error) { if (error.code !== "ENOENT") throw error; }
    const parent = dirname(ancestor);
    if (parent === ancestor) throw new Error("File path no longer has an existing parent");
    ancestor = parent;
  }
  if (realpathSync(ancestor) !== ancestor) throw new Error("The file path changed after authorization");
  return path;
}

// Pi's local operations deliver the original stdout/stderr before any tool truncation.
// Spooling avoids an unbounded queue when a process writes faster than HTTP can upload.
export async function executeShell({ operations, command, cwd, timeout, signal, onUpdate, publish,
  maxCaptureBytes = MAX_CAPTURE_BYTES }) {
  let directory;
  let file;
  let fd;
  let captured = 0;
  let total = 0;
  let tail = Buffer.alloc(0);
  let inline = Buffer.alloc(0);
  let warning;
  let exitCode = null;
  let outcome = "completed";
  let failure;
  let lastUpdate = 0;
  try {
    try {
      directory = mkdtempSync(join(process.env.PI_CODING_AGENT_DIR || tmpdir(), `.output-spool-${process.pid}-`));
      file = join(directory, "output.txt");
      fd = openSync(file, "wx", 0o600);
    } catch { warning = "Complete output could not be saved; the command still ran."; }
    try {
      const result = await operations.exec(command, cwd, { timeout, signal, onData(data) {
        const bytes = Buffer.from(data);
        total += bytes.length;
        if (total <= INLINE_BYTES) inline = Buffer.concat([inline, bytes]);
        else inline = Buffer.alloc(0);
        tail = Buffer.concat([tail, bytes.subarray(Math.max(0, bytes.length - PREVIEW_BYTES))]);
        if (tail.length > PREVIEW_BYTES) tail = tail.subarray(tail.length - PREVIEW_BYTES);
        if (fd !== undefined && captured < maxCaptureBytes && !warning) {
          const chunk = bytes.subarray(0, maxCaptureBytes - captured);
          try {
            let written = 0;
            while (written < chunk.length) written += writeSync(fd, chunk, written, chunk.length - written);
            captured += written;
          } catch { warning = "Saving complete output failed; only a partial file may be available."; }
        }
        if (total > maxCaptureBytes) warning = "Output reached the file size limit; the saved file is partial.";
        if (onUpdate && Date.now() - lastUpdate >= 200) {
          lastUpdate = Date.now();
          onUpdate({ content: [{ type: "text", text: boundedText(utf8Tail(tail), PREVIEW_BYTES, true) }], details: { outputBytes: total } });
        }
      } });
      exitCode = result.exitCode;
      if (exitCode !== 0) outcome = "failed";
    } catch (error) {
      failure = error instanceof Error ? error.message : String(error);
      outcome = signal?.aborted || failure === "aborted" ? "cancelled"
        : failure.startsWith("timeout:") ? "timeout" : "failed";
    } finally {
      if (fd !== undefined) { try { closeSync(fd); } catch { warning ??= "Output file could not be closed cleanly."; } fd = undefined; }
    }
    const data = { text: total <= INLINE_BYTES ? inline.toString("utf8") : boundedText(utf8Tail(tail), PREVIEW_BYTES, true), exitCode, outcome };
    if (failure) data.error = failure.slice(0, 1000);
    const large = total > INLINE_BYTES || Buffer.byteLength(JSON.stringify(data)) > INLINE_BYTES - 512;
    if (large) data.text = boundedText(utf8Tail(tail), PREVIEW_BYTES, true);
    const result = { ok: outcome === "completed", data };
    if (large && file && captured > 0) {
      try {
        const complete = !warning && captured === total && outcome !== "cancelled" && outcome !== "timeout";
        result.output = await publish(fileChunks(file), complete,
          warning || (complete ? null : "The command stopped before its output was complete."));
      } catch { warning = "Complete output could not be published; do not repeat the command just to recover its output."; }
    }
    if (warning && large) result.warning = warning;
    return result;
  } finally {
    if (fd !== undefined) { try { closeSync(fd); } catch { /* Preserve the execution outcome. */ } }
    if (directory) { try { rmSync(directory, { recursive: true, force: true }); } catch { /* OS cleanup can retry later. */ } }
  }
}

/** Budget the serialized text too: control characters cost more than one JSON byte. */
export function boundedText(text, budget, tail = false) {
  const slice = length => {
    let value = tail ? text.slice(text.length - length) : text.slice(0, length);
    if (tail && value.length && value.charCodeAt(0) >= 0xdc00 && value.charCodeAt(0) <= 0xdfff) value = value.slice(1);
    if (!tail && value.length && value.charCodeAt(value.length - 1) >= 0xd800 && value.charCodeAt(value.length - 1) <= 0xdbff) value = value.slice(0, -1);
    return value;
  };
  let low = 0; let high = text.length;
  while (low < high) {
    const middle = Math.ceil((low + high) / 2);
    if (Buffer.byteLength(JSON.stringify(slice(middle))) <= budget) low = middle; else high = middle - 1;
  }
  return slice(low);
}

export async function presentNative(output, publish, ok = true) {
  const result = { ok, data: output };
  const truncated = output.details?.truncation?.truncated === true;
  const warning = truncated ? "The underlying tool already truncated this output; a complete result is unavailable." : null;
  const serialized = JSON.stringify(result);
  if (Buffer.byteLength(serialized) <= INLINE_BYTES) {
    if (warning) result.output = { mode: "unavailable", complete: false, previewTruncated: true, warning };
    return result;
  }
  const bytes = Buffer.from(serialized);
  const complete = !truncated && bytes.length <= MAX_CAPTURE_BYTES;
  result.data = { text: boundedText(serialized, PREVIEW_BYTES) };
  try {
    result.output = await publish(bufferChunks(bytes.subarray(0, MAX_CAPTURE_BYTES)), complete,
      warning || (complete ? null : "Output reached the file size limit."), "json");
  } catch {
    result.output = { mode: "unavailable", complete: false, previewTruncated: true,
      warning: `Saving the full result failed. The file operation ${ok ? "succeeded" : "failed"}; do not repeat it to recover output.` };
  }
  return result;
}

async function* bufferChunks(bytes) {
  for (let offset = 0; offset < bytes.length; offset += 48 * 1024) yield bytes.subarray(offset, offset + 48 * 1024);
}

async function* fileChunks(path) {
  yield* createReadStream(path, { highWaterMark: 48 * 1024 });
}

function utf8Tail(bytes) {
  let start = 0;
  while (start < bytes.length && (bytes[start] & 0xc0) === 0x80) start++;
  // A streaming update may end halfway through a UTF-8 character.
  return new StringDecoder("utf8").write(bytes.subarray(start));
}
