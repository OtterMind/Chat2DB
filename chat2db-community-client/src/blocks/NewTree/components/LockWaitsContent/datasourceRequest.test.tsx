import assert from 'node:assert/strict';
import Module from 'node:module';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { JSDOM } from 'jsdom';

require.extensions['.css'] = () => undefined;
require.extensions['.less'] = () => undefined;
require.extensions['.png'] = () => undefined;
require.extensions['.svg'] = () => undefined;
require.extensions['.webp'] = () => undefined;
require.extensions['.woff'] = () => undefined;
require.extensions['.woff2'] = () => undefined;

const dom = new JSDOM('<!doctype html><html><body></body></html>');
dom.window.document.body.appendChild(dom.window.document.createElement('script'));

(globalThis as any).__APP_NAME__ = 'chat2db-community-test';
(globalThis as any).__APP_CAPITAL_NAME__ = 'Chat2DB Community Test';
(globalThis as any).__APP_DISPLAY_NAME__ = 'Chat2DB Community Test';
(globalThis as any).__APP_PROTOCOL_SCHEME__ = 'chat2db-community-test';
(globalThis as any).__APP_VERSION__ = '5.3.0';
(globalThis as any).__RUNTIME_ENV__ = 'community';
(globalThis as any).__ENV__ = 'test';
(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
(globalThis as any).window = dom.window;
(globalThis as any).document = dom.window.document;
Object.defineProperty(globalThis, 'location', { configurable: true, value: dom.window.location });
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: dom.window.navigator });
(globalThis as any).HTMLElement = dom.window.HTMLElement;
(globalThis as any).Element = dom.window.Element;
(globalThis as any).SVGElement = dom.window.SVGElement;
(globalThis as any).ShadowRoot = dom.window.ShadowRoot;
const getComputedStyleStub = () => ({ getPropertyValue: () => '' });
const matchMediaStub = () => ({
  matches: false,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  addListener: () => undefined,
  removeListener: () => undefined,
});
const ResizeObserverStub = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
(globalThis as any).getComputedStyle = getComputedStyleStub;
(globalThis as any).matchMedia = matchMediaStub;
(globalThis as any).ResizeObserver = ResizeObserverStub;
(dom.window as any).getComputedStyle = getComputedStyleStub;
(dom.window as any).matchMedia = matchMediaStub;
(dom.window as any).ResizeObserver = ResizeObserverStub;

const i18nMessages: Record<string, string> = {
  'common.button.refresh': 'Refresh',
  'common.text.failure': 'Failure',
  'workspace.ops.blockerLockMode': 'Blocker Lock Mode',
  'workspace.ops.blockerQuery': 'Blocker Query',
  'workspace.ops.blockerState': 'Blocker State',
  'workspace.ops.blockerThread': 'Blocker Thread',
  'workspace.ops.blockerUser': 'Blocker User',
  'workspace.ops.blockingChains': 'Blocking Chains',
  'workspace.ops.cycle': 'Cycle',
  'workspace.ops.database': 'Database',
  'workspace.ops.datasourceId': 'Datasource ID',
  'workspace.ops.engineThread': 'engine thread {1}',
  'workspace.ops.engineThreadId': 'Engine Thread',
  'workspace.ops.host': 'Host',
  'workspace.ops.innodbDataLocks': 'InnoDB Data Locks ({1})',
  'workspace.ops.lockBlocking': 'Blocking',
  'workspace.ops.lockData': 'Lock Data',
  'workspace.ops.lockDuration': 'Duration',
  'workspace.ops.lockId': 'Lock ID',
  'workspace.ops.lockMetadataUnavailable': 'Lock metadata unavailable',
  'workspace.ops.lockMode': 'Mode',
  'workspace.ops.lockObject': 'Object',
  'workspace.ops.lockOpenSession': 'Open session {1}',
  'workspace.ops.lockPrivilegeRequired': 'Additional privileges are required to inspect lock metadata',
  'workspace.ops.lockSnapshotNotice': 'Current datasource snapshot only.',
  'workspace.ops.lockSource': 'Source: {1}',
  'workspace.ops.lockSourceUnavailable': 'lock sources unavailable',
  'workspace.ops.lockStatus': 'Status',
  'workspace.ops.lockType': 'Type',
  'workspace.ops.metadataBlockingChains': 'Metadata Blocking Chains ({1})',
  'workspace.ops.metadataLockCount': '{1} metadata locks',
  'workspace.ops.metadataLocks': 'Metadata Locks ({1})',
  'workspace.ops.metadataLocksUnavailable': 'Metadata lock instrumentation unavailable',
  'workspace.ops.noLockWaits': 'No active lock waits',
  'workspace.ops.ownerThread': 'Owner Thread',
  'workspace.ops.query': 'Query',
  'workspace.ops.role': 'Role',
  'workspace.ops.rootBlocker': 'ROOT BLOCKER',
  'workspace.ops.sessionId': 'Session ID',
  'workspace.ops.sessionStale': 'stale',
  'workspace.ops.sessions': 'Sessions ({1})',
  'workspace.ops.sessionsUnavailable': 'Session rows unavailable',
  'workspace.ops.state': 'State',
  'workspace.ops.user': 'User',
  'workspace.ops.valueUnavailable': 'unavailable',
  'workspace.ops.waiterLockMode': 'Waiter Lock Mode',
  'workspace.ops.waiterQuery': 'Waiter Query',
  'workspace.ops.waiterState': 'Waiter State',
  'workspace.ops.waiterThread': 'Waiter Thread',
  'workspace.ops.waiterUser': 'Waiter User',
};

const lockViewSource = {
  PERFORMANCE_SCHEMA: 'PERFORMANCE_SCHEMA',
  INFORMATION_SCHEMA: 'INFORMATION_SCHEMA',
  UNAVAILABLE: 'UNAVAILABLE',
};
const lockViewErrorCode = {
  PRIVILEGE_REQUIRED: 'PRIVILEGE_REQUIRED',
  UNAVAILABLE: 'UNAVAILABLE',
};

const emptyLockView = (dataSourceId: number) => ({
  dataSourceId,
  source: lockViewSource.PERFORMANCE_SCHEMA,
  dataLocks: [],
  waits: [],
  metaLocks: [],
  sessions: [],
  waitChains: [],
  metadataWaitChains: [],
  errors: [],
});

const mockSqlService = {
  getLockView: async (_params: unknown): Promise<any> => emptyLockView(1),
};
const originalLoad = (Module as any)._load;
(Module as any)._load = function load(request: string, parent: unknown, isMain: boolean) {
  if (request === '@/i18n') {
    return {
      __esModule: true,
      default: (key: string, ...args: unknown[]) =>
        (i18nMessages[key] || key).replace(/\{(\d+)}/g, (_, index) => String(args[Number(index) - 1] ?? '')),
    };
  }
  if (request === '@/service/sql') {
    return {
      __esModule: true,
      default: mockSqlService,
      LockViewErrorCode: lockViewErrorCode,
      LockViewSource: lockViewSource,
    };
  }
  return originalLoad.call(this, request, parent, isMain);
};

function createTestContainer() {
  const container = document.createElement('div');
  document.body.appendChild(container);
  return container;
}

async function renderLockView(view: any, dataSourceId: number) {
  const { default: LockWaitsContent } = await import('./index');
  const originalGetLockView = mockSqlService.getLockView;
  mockSqlService.getLockView = async () => view;
  const container = createTestContainer();
  const root = createRoot(container);
  await act(async () => {
    root.render(createElement(LockWaitsContent, { dataSourceId }));
  });
  return {
    container,
    cleanup: async () => {
      await act(async () => root.unmount());
      container.remove();
      mockSqlService.getLockView = originalGetLockView;
    },
  };
}

async function testLockViewRequestUsesRenderedDatasource() {
  const { default: LockWaitsContent } = await import('./index');
  let capturedParams: unknown;
  const originalGetLockView = mockSqlService.getLockView;
  mockSqlService.getLockView = async (params: unknown) => {
    capturedParams = params;
    return emptyLockView(72);
  };
  const container = createTestContainer();
  const root = createRoot(container);
  try {
    await act(async () => root.render(createElement(LockWaitsContent, { dataSourceId: 72 })));
    assert.deepEqual(capturedParams, { dataSourceId: 72 });
  } finally {
    await act(async () => root.unmount());
    container.remove();
    mockSqlService.getLockView = originalGetLockView;
  }
}

async function testTypedRowsAndMetadataChainsRender() {
  const view = {
    ...emptyLockView(73),
    metaLocks: [
      {
        objectType: 'GLOBAL',
        objectSchema: null,
        objectName: null,
        objectInstanceId: '1001',
        lockType: 'INTENTION_EXCLUSIVE',
        lockDuration: 'STATEMENT',
        lockStatus: 'GRANTED',
        ownerThreadId: '52',
        ownerEventId: '5',
        ownerSessionId: null,
        ownerUser: null,
        ownerHost: null,
        ownerDatabase: null,
        ownerState: null,
        ownerQuery: null,
        ownerSessionAvailable: false,
      },
    ],
    sessions: [
      {
        engineThreadId: '55',
        sessionId: '155',
        user: 'root-user',
        host: '127.0.0.1',
        databaseName: 'app',
        command: 'Query',
        timeSeconds: '1',
        state: 'executing',
        query: 'update t set c = 1',
        transactionId: 'trx-session',
      },
    ],
    metadataWaitChains: [
      {
        dataSourceId: 73,
        lockKind: 'METADATA',
        lockObject: 'app.orders',
        waiterTransactionId: null,
        waiterLockId: 'metadata:app.orders:55',
        waiterThreadId: '155',
        waiterEngineThreadId: '55',
        waiterState: 'LOCK WAIT',
        waiterUser: 'root-user',
        waiterHost: '127.0.0.1',
        waiterDatabase: 'app',
        waiterQuery: 'alter table orders add note int',
        waiterSessionAvailable: true,
        waiterMetadataLockCount: 1,
        waiterLockMode: 'EXCLUSIVE',
        blockerTransactionId: null,
        blockerLockId: 'metadata:app.orders:56',
        blockerThreadId: '156',
        blockerEngineThreadId: '56',
        blockerState: 'RUNNING',
        blockerUser: 'writer',
        blockerHost: '127.0.0.1',
        blockerDatabase: 'app',
        blockerQuery: 'update orders set status = 1',
        blockerSessionAvailable: true,
        blockerMetadataLockCount: 1,
        blockerLockMode: 'SHARED_WRITE',
        rootBlocker: true,
        cycle: false,
      },
    ],
  };
  const rendered = await renderLockView(view, 73);
  try {
    assert.match(rendered.container.textContent || '', /Datasource ID: 73/);
    assert.match(rendered.container.textContent || '', /Metadata Blocking Chains \(1\)/);
  } finally {
    await rendered.cleanup();
  }
}

async function testLocalizedErrorsDoNotExposeContractCodes() {
  const view = {
    ...emptyLockView(82),
    source: lockViewSource.UNAVAILABLE,
    errors: [
      { section: 'DATA_LOCKS', code: lockViewErrorCode.PRIVILEGE_REQUIRED },
      { section: 'METADATA_WAITS', code: lockViewErrorCode.UNAVAILABLE },
    ],
  };
  const rendered = await renderLockView(view, 82);
  try {
    const text = rendered.container.textContent || '';
    assert.match(text, /Additional privileges are required/);
    assert.match(text, /Lock metadata unavailable/);
    assert.doesNotMatch(text, /DATA_LOCKS|METADATA_WAITS|PRIVILEGE_REQUIRED/);
  } finally {
    await rendered.cleanup();
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolver) => {
    resolve = resolver;
  });
  return { promise, resolve };
}

async function testLatestRefreshWins() {
  const { default: LockWaitsContent } = await import('./index');
  const originalGetLockView = mockSqlService.getLockView;
  const first = deferred<any>();
  const second = deferred<any>();
  let requestCount = 0;
  mockSqlService.getLockView = () => (++requestCount === 1 ? first.promise : second.promise);
  const container = createTestContainer();
  const root = createRoot(container);
  try {
    await act(async () => root.render(createElement(LockWaitsContent, { dataSourceId: 80 })));
    await act(async () => root.render(createElement(LockWaitsContent, { dataSourceId: 81 })));
    await act(async () => {
      second.resolve(emptyLockView(81));
      await second.promise;
    });
    await act(async () => {
      first.resolve(emptyLockView(80));
      await first.promise;
    });
    assert.match(container.textContent || '', /Datasource ID: 81/);
    assert.doesNotMatch(container.textContent || '', /Datasource ID: 80/);
  } finally {
    await act(async () => root.unmount());
    container.remove();
    mockSqlService.getLockView = originalGetLockView;
  }
}

async function testStableMetadataKeys() {
  const { lockObjectText, metadataLockRowKey } = await import('./index');
  const base = {
    objectSchema: null,
    objectName: null,
    lockType: 'INTENTION_EXCLUSIVE',
    lockDuration: 'STATEMENT',
    lockStatus: 'GRANTED',
    ownerThreadId: '52',
    ownerEventId: '4',
    ownerSessionId: '152',
    ownerUser: 'root',
    ownerHost: 'localhost',
    ownerDatabase: null,
    ownerState: 'executing',
    ownerQuery: null,
    ownerSessionAvailable: true,
  };
  const globalLock = { ...base, objectType: 'GLOBAL', objectInstanceId: '1001' };
  const backupLock = { ...base, objectType: 'BACKUP LOCK', objectInstanceId: '1002' };
  assert.equal(lockObjectText(null, null, globalLock.objectType), 'GLOBAL');
  assert.notEqual(metadataLockRowKey(globalLock), metadataLockRowKey(backupLock));
}

Promise.resolve()
  .then(testLockViewRequestUsesRenderedDatasource)
  .then(testTypedRowsAndMetadataChainsRender)
  .then(testLocalizedErrorsDoNotExposeContractCodes)
  .then(testLatestRefreshWins)
  .then(testStableMetadataKeys)
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
