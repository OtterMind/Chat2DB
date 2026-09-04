import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { JSDOM } from 'jsdom';
import { TransactionIsolationLevel, TransactionMode } from '@/constants/transaction';
import {
  beginSqlExecutionRequest,
  createSqlExecutionRequestTracker,
  requestSqlExecutionCancellation,
} from '@/service/sqlExecutionRequestTracker';

const requireForAssets = createRequire(__filename);
for (const extension of ['.css', '.less', '.woff', '.woff2', '.ttf', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg']) {
  requireForAssets.extensions[extension] = (module: any) => {
    module.exports = '';
  };
}

Object.assign(globalThis, {
  __APP_NAME__: 'chat2db-community',
  __APP_CAPITAL_NAME__: 'Chat2DB',
  __APP_DISPLAY_NAME__: 'Chat2DB',
  __APP_PROTOCOL_SCHEME__: 'chat2db',
  __APP_VERSION__: '0.0.0-test',
  __RUNTIME_ENV__: 'community',
  __ENV__: 'test',
});

async function run() {
  let resolveBegin!: (value: any) => void;
  const beginPromise = new Promise((resolve) => {
    resolveBegin = resolve;
  });
  let beginCalls = 0;
  let executeCalls = 0;
  let startCalls = 0;
  const transactionState = {
    mode: TransactionMode.MANUAL,
    inTransaction: false,
    opening: false,
    isolationLevel: TransactionIsolationLevel.DEFAULT,
    supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT],
  };
  const executionEvents: any[] = [];
  const nodeModule = requireForAssets('node:module') as any;
  const originalLoad = nodeModule.Module._load;
  nodeModule.Module._load = function mockedLoad(request: string, ...args: any[]) {
    if (request === '@/service/executeSql') {
      return {
        __esModule: true,
        default: {
          executeSql: async () => {
            executeCalls += 1;
            return [];
          },
        },
      };
    }
    if (request === '@/service/transaction') {
      return {
        __esModule: true,
        default: {
          beginTransaction: async () => {
            beginCalls += 1;
            return beginPromise;
          },
        },
      };
    }
    if (request === './useAbortRequest') {
      return {
        __esModule: true,
        default: () => [() => new AbortController().signal, () => undefined],
      };
    }
    if (request === '@/utils/env') {
      return { isDesktop: true };
    }
    if (request === '@/service/sqlExecutionStream') {
      return {
        cancelSqlExecutionWithReconciliation: async () => undefined,
        onSqlExecutionEvent: () => undefined,
        startSqlExecution: async () => {
          startCalls += 1;
          return { executionId: 'server-execution' };
        },
      };
    }
    if (request === 'uuid') {
      return { v4: () => 'test-request' };
    }
    if (request === '@/store/global') {
      return { useGlobalStore: () => 100 };
    }
    if (request === '@/store/global/selectors') {
      return { settingSelectors: { currentBaseSetting: () => ({ defaultPageSize: 100 }) } };
    }
    if (request === '@/store/workspace') {
      return {
        useWorkspaceStore: {
          getState: () => ({
            getTransactionState: () => transactionState,
            setTransactionState: (_consoleId: number, patch: Record<string, unknown>) =>
              Object.assign(transactionState, patch),
          }),
        },
      };
    }
    return originalLoad.apply(this, [request, ...args]);
  };
  const {
    createSqlExecutionCancelledBeforeStartEvent,
    default: useSqlExecutor,
    shouldAbortSqlExecutionAfterManualBegin,
  } = await import('./useSqlExecutor');
  nodeModule.Module._load = originalLoad;

  const tracker = createSqlExecutionRequestTracker();
  const requestSequence = beginSqlExecutionRequest(tracker)!;
  requestSqlExecutionCancellation(tracker);

  assert.equal(
    shouldAbortSqlExecutionAfterManualBegin(tracker, requestSequence),
    true,
    'execution cancelled while begin is pending must not start SQL after begin resolves',
  );
  assert.deepEqual(
    createSqlExecutionCancelledBeforeStartEvent(requestSequence, 1234),
    {
      executionId: `cancelled-before-start-${requestSequence}`,
      occurredAtEpochMs: 1234,
      eventType: 'cancelled',
      message: {},
    },
    'desktop cancellation before server submission emits a local terminal event',
  );

  const dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://127.0.0.1/',
  });
  Object.assign(globalThis, {
    window: dom.window,
    document: dom.window.document,
    DOMException: dom.window.DOMException,
    IS_REACT_ACT_ENVIRONMENT: true,
  });
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: dom.window.navigator });
  const React = requireForAssets('react');
  const { createRoot } = requireForAssets('react-dom/client');
  const rootElement = dom.window.document.getElementById('root');
  assert.ok(rootElement);
  let executor: ReturnType<typeof useSqlExecutor> | undefined;
  const Harness = () => {
    executor = useSqlExecutor({
      onExecutionEvent: (event) => executionEvents.push(event),
    });
    return null;
  };
  const root = createRoot(rootElement);
  await React.act(async () => {
    root.render(React.createElement(Harness));
  });
  assert.ok(executor);

  let executionPromise!: Promise<any[]>;
  await React.act(async () => {
    executionPromise = executor!.executeSQL({
      consoleId: 42,
      dataSourceId: 7,
      databaseName: 'orders',
      databaseType: 'MYSQL',
      sql: 'INSERT INTO orders VALUES (1)',
    } as any);
    await Promise.resolve();
  });
  assert.equal(beginCalls, 1);
  executor!.stopExecuteSQL();
  resolveBegin({
    inTransaction: true,
    mode: TransactionMode.MANUAL,
    isolationLevel: TransactionIsolationLevel.DEFAULT,
    supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT],
  });
  await React.act(async () => {
    await executionPromise;
  });

  assert.equal(startCalls, 0, 'desktop cancellation during begin must not submit SQL to JCEF');
  assert.equal(executeCalls, 0, 'desktop cancellation during begin must not use the web SQL endpoint');
  assert.equal(executionEvents.length, 1);
  assert.equal(executionEvents[0].eventType, 'cancelled');
  assert.equal(executor!.executing, false);
  await React.act(async () => root.unmount());
  dom.window.close();

  console.log('useSqlExecutor transaction cancellation tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
