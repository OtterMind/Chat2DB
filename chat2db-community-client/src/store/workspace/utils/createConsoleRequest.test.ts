import assert from 'node:assert/strict';
import { addPendingConsoleRequest, removePendingConsoleRequest, runCreateConsoleRequest } from './createConsoleRequest';

interface TestTab {
  id: number;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function testRejectsAndReleasesLoading() {
  const failure = new Error('create failed');
  const loading: boolean[] = [];
  const tabs: TestTab[] = [];
  let pending = 0;

  await assert.rejects(
    runCreateConsoleRequest({
      create: async () => {
        throw failure;
      },
      getTabs: () => tabs,
      buildTab: (id) => ({ id }),
      setTabs: () => assert.fail('failed create must not add a tab'),
      setActive: () => assert.fail('failed create must not activate a tab'),
      begin: () => {
        pending += 1;
        loading.push(pending > 0);
      },
      finish: () => {
        pending -= 1;
        loading.push(pending > 0);
      },
    }),
    failure,
  );

  assert.deepEqual(loading, [true, false]);
}

async function testConcurrentCreatesUseLatestTabsAndKeepLoading() {
  const first = deferred<number>();
  const second = deferred<number>();
  const loading: boolean[] = [];
  let tabs: TestTab[] = [];
  const activeIds: number[] = [];
  let pending = 0;
  const dependencies = (request: Promise<number>) => ({
    create: () => request,
    getTabs: () => tabs,
    buildTab: (id: number) => ({ id }),
    setTabs: (nextTabs: TestTab[]) => {
      tabs = nextTabs;
    },
    setActive: (id: number) => activeIds.push(id),
    begin: () => {
      pending += 1;
      if (pending === 1) {
        loading.push(true);
      }
    },
    finish: () => {
      pending -= 1;
      if (pending === 0) {
        loading.push(false);
      }
    },
  });

  const firstResult = runCreateConsoleRequest(dependencies(first.promise));
  const secondResult = runCreateConsoleRequest(dependencies(second.promise));
  assert.deepEqual(loading, [true]);

  second.resolve(2);
  assert.equal(await secondResult, 2);
  assert.deepEqual(tabs, [{ id: 2 }]);
  assert.deepEqual(loading, [true], 'loading stays set while the first create is pending');

  first.resolve(1);
  assert.equal(await firstResult, 1);
  assert.deepEqual(tabs, [{ id: 2 }, { id: 1 }]);
  assert.deepEqual(activeIds, [2, 1]);
  assert.deepEqual(loading, [true, false]);
}

async function testLifecycleErrorsStillFinish() {
  const beginFailure = new Error('begin failed');
  const tabFailure = new Error('set tabs failed');
  const activeFailure = new Error('set active failed');
  const calls: string[] = [];
  const baseDependencies = {
    create: async () => 1,
    getTabs: () => [] as TestTab[],
    buildTab: (id: number) => ({ id }),
    setTabs: () => undefined,
    setActive: () => undefined,
    begin: () => calls.push('begin'),
    finish: () => calls.push('finish'),
  };

  await assert.rejects(
    runCreateConsoleRequest({
      ...baseDependencies,
      begin: () => {
        calls.push('begin');
        throw beginFailure;
      },
    }),
    beginFailure,
  );
  assert.deepEqual(calls, ['begin', 'finish']);

  calls.length = 0;
  await assert.rejects(
    runCreateConsoleRequest({
      ...baseDependencies,
      setTabs: () => {
        calls.push('set-tabs');
        throw tabFailure;
      },
    }),
    tabFailure,
  );
  assert.deepEqual(calls, ['begin', 'set-tabs', 'finish']);

  calls.length = 0;
  await assert.rejects(
    runCreateConsoleRequest({
      ...baseDependencies,
      setActive: () => {
        calls.push('set-active');
        throw activeFailure;
      },
    }),
    activeFailure,
  );
  assert.deepEqual(calls, ['begin', 'set-active', 'finish']);
}

function testStaleCompletionCannotClearANewerRequestAfterReset() {
  let requestIds = addPendingConsoleRequest([], 'old');
  requestIds = [];
  requestIds = addPendingConsoleRequest(requestIds, 'new');
  requestIds = removePendingConsoleRequest(requestIds, 'old');
  assert.deepEqual(requestIds, ['new']);
  requestIds = removePendingConsoleRequest(requestIds, 'new');
  assert.deepEqual(requestIds, []);
}

void Promise.all([
  testRejectsAndReleasesLoading(),
  testConcurrentCreatesUseLatestTabsAndKeepLoading(),
  testLifecycleErrorsStillFinish(),
]).then(() => {
  testStaleCompletionCannotClearANewerRequestAfterReset();
  console.log('Workspace console request tests passed');
});
