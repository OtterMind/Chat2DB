import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, relative } from 'node:path';
import { test } from 'node:test';
import { createPiClient } from './client';
import { PI_ENDPOINT, type PiRequest, type PiResponse } from './contract';
import type { ICommandLineRequest, IOptions } from '../commandLine/commandLine';
import { createHttpPiTransport } from './adapters/http';
import { createDesktopPiTransport } from './adapters/desktop';
import { createDesktopPiHost, createWebPiHost } from './adapters/host';

const success = (request: PiRequest, data: unknown = null): PiResponse => ({
  protocolVersion: 1, requestId: request.requestId, success: true, data,
});

function setup(kind: 'http' | 'desktop') {
  const requests: PiRequest[] = [];
  let reply = (request: PiRequest): Promise<PiResponse> => Promise.resolve(success(request, request.payload));
  const send = (request: PiRequest) => { requests.push(request); return reply(request); };
  const transport = kind === 'http' ? createHttpPiTransport(() => ({ 'Accept-Language': 'zh-CN' }),
    async (url, options) => {
      assert.equal(url, PI_ENDPOINT);
      assert.equal(options?.method, 'POST');
      assert.equal(options?.credentials, 'include');
      assert.equal((options?.headers as Record<string, string>)['Accept-Language'], 'zh-CN');
      assert.ok(options?.signal instanceof AbortSignal);
      return Response.json(await send(JSON.parse(options?.body as string)));
    }) : createDesktopPiTransport(async <T>(request: ICommandLineRequest, options: IOptions): Promise<T> => {
    assert.equal(request.requestUrl, PI_ENDPOINT);
    assert.equal(request.method, 'post');
    assert.equal(options.timeout, false);
    assert.equal(options.rawResponse, true);
    assert.ok(options.restParams?.signal instanceof AbortSignal);
    return await send(request.message) as T;
  });
  return { client: createPiClient(transport), requests,
    respond: (next: typeof reply) => { reply = next; } };
}

for (const kind of ['http', 'desktop'] as const) {
  test(`${kind}: payloads, null/false values and business errors use the common contract`, async () => {
    const app = setup(kind);
    const payload = Object.freeze({ toolName: 'db_query', enabled: false });
    assert.deepEqual(await app.client.tools.setEnabled(payload), payload);
    app.respond(async (request) => success(request));
    assert.equal(await app.client.workspace.selectDirectory(), null);
    app.respond(async (request) => ({ ...success(request), success: false,
      errorCode: 'common.permissionDenied', errorMessage: 'Permission denied' }));
    await assert.rejects(app.client.skills.list(), { name: 'PiRequestError', errorCode: 'common.permissionDenied' });
    assert.equal(new Set(app.requests.map((request) => request.requestId)).size, 3);
  });

  test(`${kind}: wrong correlation IDs, versions and malformed results are rejected`, async () => {
    const app = setup(kind);
    for (const corrupt of [{ requestId: 'other' }, { protocolVersion: 2 }, { success: undefined }]) {
      app.respond(async (request) => ({ ...success(request), ...corrupt } as PiResponse));
      await assert.rejects(app.client.skills.list(), { errorCode: 'pi.invalidResponse' });
    }
  });

  test(`${kind}: abort sends no new request and ignores late replies without cancelling the run`, async () => {
    const app = setup(kind);
    const before = new AbortController(); before.abort();
    await assert.rejects(app.client.events.list(
      { sessionId: 's', afterSequence: 0 }, { signal: before.signal }),
      { name: 'AbortError' });
    assert.equal(app.requests.length, 0);
    let complete: (response: PiResponse) => void = () => {};
    app.respond(() => new Promise((resolveReply) => { complete = resolveReply; }));
    const controller = new AbortController();
    const pending = app.client.events.list({ sessionId: 's', afterSequence: 0 }, { signal: controller.signal });
    controller.abort();
    await assert.rejects(pending, { name: 'AbortError' });
    complete(success(app.requests[0], []));
    assert.deepEqual(app.requests.map((request) => request.operation), ['events.list']);
    app.respond(async (request) => success(request));
    await app.client.runs.cancel({ sessionId: 's', runId: 'r' });
    assert.equal(app.requests.at(-1)?.operation, 'runs.cancel');
  });

  test(`${kind}: timeout aborts the transport and releases the caller listener`, async () => {
    const app = setup(kind);
    const controller = new AbortController();
    const listeners = new Set<unknown>();
    const add = controller.signal.addEventListener.bind(controller.signal);
    const remove = controller.signal.removeEventListener.bind(controller.signal);
    controller.signal.addEventListener = (type, listener, options) => {
      listeners.add(listener); add(type, listener, options);
    };
    controller.signal.removeEventListener = (type, listener, options) => {
      listeners.delete(listener); remove(type, listener, options);
    };
    app.respond(() => new Promise(() => {}));
    await assert.rejects(app.client.skills.list(undefined, { signal: controller.signal, timeoutMs: 5 }),
      { errorCode: 'pi.timeout' });
    assert.equal(listeners.size, 0);
  });
}

test('HTTP failures and native transport failures reject with an actionable error', async () => {
  const http = createPiClient(createHttpPiTransport(() => ({}), async () =>
    new Response(null, { status: 503, statusText: 'Service unavailable' })));
  await assert.rejects(http.skills.list(), { errorCode: 'http.503', message: 'Service unavailable' });
  const desktop = createPiClient(createDesktopPiTransport(async () => { throw 'Bridge unavailable'; }));
  await assert.rejects(desktop.skills.list(), { errorCode: 'pi.transport', message: 'Bridge unavailable' });
});

test('host adapters keep cancellation side-effect free and route files through their own platform', async () => {
  const calls: PiRequest[] = [];
  let result: unknown = null;
  const client = createPiClient({ invoke: async (request) => {
    calls.push(request); return success(request, result);
  } });
  const downloads: string[] = [];
  const revealed: string[] = [];
  const attachment = { fileName: 'file.txt', fileType: 'txt', contentCategory: 'DOCUMENT' as const, content: 'text' };
  const web = createWebPiHost(client, {
    selectDirectory: async () => null, download: (url) => downloads.push(url),
    selectFiles: async () => [], parseAttachment: async () => attachment,
  });
  const desktop = createDesktopPiHost(client, {
    reveal: async (path) => { revealed.push(path); }, selectFiles: async () => [{ filePath: '/file.txt' }],
  });
  assert.equal(await web.selectDirectory('/current'), null);
  assert.equal(calls.length, 0, 'Cancelling a directory prompt must not write settings');
  assert.equal(await desktop.selectDirectory('/current'), null);
  const output = { sessionId: 's/?', artifactId: 'a/b' };
  await desktop.downloadOutput(output);
  assert.deepEqual(revealed, []);
  result = '/saved/output.jsonl';
  await desktop.downloadOutput(output);
  assert.deepEqual(revealed, [result]);
  await web.downloadOutput(output);
  assert.deepEqual(downloads, ['/api/v3/ai/sessions/s%2F%3F/outputs/a%2Fb/download']);
  result = attachment;
  assert.deepEqual(await desktop.parseAttachment((await desktop.selectFiles(['txt']))[0]), attachment);
  assert.equal(calls.at(-1)?.operation, 'attachments.parseLocal');
  assert.deepEqual(await web.parseAttachment({ fileName: 'file.txt' }), attachment);
  assert.ok(calls.every((request) => request.operation !== 'workspace.set'));
  const controller = new AbortController(); controller.abort();
  await assert.rejects(web.downloadOutput(output, { signal: controller.signal }), { name: 'AbortError' });
  assert.equal(downloads.length, 1);
});

test('every declared operation is exposed by the shared client', () => {
  const client = createPiClient({ invoke: async (request) => success(request) });
  const exposed = Object.entries(client).flatMap(([group, methods]) =>
    Object.keys(methods).map((method) => `${group}.${method}`));
  const declared = [...readFileSync(`${__dirname}/contract.ts`, 'utf8').matchAll(/^ {2}'([^']+)': Operation</gm)]
    .map((match) => match[1]);
  assert.deepEqual(exposed.sort(), declared.sort());
});

test('Pi presentation and client core cannot bypass the adapters', () => {
  const root = resolve(__dirname, '../..');
  const walk = (path: string): string[] => readdirSync(path, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? walk(resolve(path, entry.name)) : [resolve(path, entry.name)]);
  const files = ['blocks/AI/components/AgentV2Session', 'blocks/AI/components/PiToolSettings']
    .flatMap((path) => walk(resolve(root, path)))
    .concat(['service/pi/client.ts', 'service/agentOutput.ts'].map((path) => resolve(root, path)));
  for (const file of files.filter((path) => /\.tsx?$/.test(path) && !path.includes('.test.'))) {
    assert.doesNotMatch(readFileSync(file, 'utf8'),
      /(?:from\s+['"][^'"]*(?:service\/base|commandLine|jcef|utils\/env)['"]|\bfetch\s*\(|\bjavaQuery\b|\bisDesktop\b)/,
      relative(root, file));
  }
});
