import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import ts from 'typescript';
import type { ICommandLineRequestListItem, IOptions } from './commandLine';

const source = readFileSync(`${__dirname}/commandLine.ts`, 'utf8');
const code = ts.transpileModule(source, { compilerOptions: {
  module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020,
} }).outputText;

function setup() {
  const pending: Record<string, ICommandLineRequestListItem> = {};
  type Query = { request: string; onSuccess: (value: string) => void;
    onFailure: (code: number, message: string) => void };
  const requests: Query[] = [];
  const timers = new Map<number, () => void>();
  let next = 0;
  const state = {
    baseSetting: { language: 'zh-CN' }, commandLineRequestList: pending,
    addCommandLineRequestListItem: (item: ICommandLineRequestListItem) => { pending[item.requestData.uuid] = item; },
    removeCommandLineRequestListItem: (id: string) => { delete pending[id]; },
  };
  const modules: Record<string, unknown> = {
    uuid: { v4: () => `request-${++next}` },
    '@/store/global': { useGlobalStore: { getState: () => state } },
    '@/constants/common': { ServiceStatus: {} },
    '@/constants/request': { ErrorCodesWithoutToast: ['quiet'] },
    '@/service/interceptorsResponse': { default: () => {} },
    '@chat2db/ui': { staticMessage: { error: () => {} } },
  };
  const exports = {} as typeof import('./commandLine');
  new Function('require', 'exports', 'window', '__PRINT_LOGS__', 'alert', 'setTimeout', 'clearTimeout', code)(
    (name: string) => { assert.ok(name in modules, name); return modules[name]; }, exports,
    { javaQuery: (query: typeof requests[number]) => { requests.push(query); return requests.length; } },
    false, () => {},
    (fn: () => void) => { timers.set(++next, fn); return next; },
    (id: number) => timers.delete(id),
  );
  const call = (requestOptions?: IOptions['restParams']) => exports.commandLineRequest({
    requestUrl: '/api/v3/ai/skills', method: 'get', message: undefined,
  }, { errorLevel: false, permissionError: false, timeout: true, restParams: requestOptions });
  const respond = (success = true, errorCode = '') => {
    const last = requests.at(-1)!;
    last.onSuccess(JSON.stringify({ uuid: JSON.parse(last.request).uuid,
      message: { success, data: ['chart'], errorCode, errorMessage: 'test failure' } }));
  };
  return { call, respond, pending, requests, timers };
}

function signal() {
  const controller = new AbortController();
  let listeners = 0;
  const add = controller.signal.addEventListener.bind(controller.signal);
  const remove = controller.signal.removeEventListener.bind(controller.signal);
  controller.signal.addEventListener = (...args) => { listeners++; add(...args); };
  controller.signal.removeEventListener = (...args) => { listeners--; remove(...args); };
  return { controller, listeners: () => listeners };
}

test('native AbortSignal reaches javaQuery and successful reply releases the request', async () => {
  const app = setup(); const s = signal();
  const request = app.call({ signal: s.controller.signal });
  assert.equal(app.requests.length, 1);
  app.respond();
  assert.deepEqual(await request, ['chart']);
  assert.equal(s.listeners(), 0); assert.equal(app.timers.size, 0); assert.deepEqual(app.pending, {});
});

test('pre-aborted request does not send; abort drops late responses without leaking listeners', async () => {
  const app = setup(); const before = new AbortController(); before.abort();
  await assert.rejects(app.call({ signal: before.signal }), { name: 'AbortError' });
  assert.equal(app.requests.length, 0);
  const s = signal(); const request = app.call({ signal: s.controller.signal });
  s.controller.abort();
  await assert.rejects(request, { name: 'AbortError' });
  app.respond();
  assert.equal(s.listeners(), 0); assert.equal(app.timers.size, 0); assert.deepEqual(app.pending, {});
});

test('business error, native failure and timeout each release abort listeners', async () => {
  for (const kind of ['quiet', 'native', 'timeout']) {
    const app = setup(); const s = signal(); const request = app.call({ signal: s.controller.signal });
    if (kind === 'quiet') app.respond(false, 'quiet');
    if (kind === 'native') app.requests[0].onFailure(1, 'bridge failure');
    if (kind === 'timeout') { const fn = [...app.timers.values()][0]; app.timers.clear(); fn(); }
    await assert.rejects(request);
    assert.equal(s.listeners(), 0); assert.equal(app.timers.size, 0); assert.deepEqual(app.pending, {});
  }
});

test('legacy callback and requests without a signal still work', async () => {
  const app = setup(); let registered = '';
  const request = app.call({ signal: ({ id }) => { registered = id; } });
  assert.equal(registered, JSON.parse(app.requests[0].request).uuid);
  app.respond(); assert.deepEqual(await request, ['chart']);
  const ordinary = app.call(); app.respond(); assert.deepEqual(await ordinary, ['chart']);
});
