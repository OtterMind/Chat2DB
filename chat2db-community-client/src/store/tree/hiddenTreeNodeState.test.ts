import assert from 'node:assert/strict';
import { applyHiddenTreeNodeChanges, HiddenTreeNodeStateCoordinator } from './hiddenTreeNodeState';

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

async function nextMicrotask() {
  await Promise.resolve();
}

async function testWriteWaitsForPendingInitialization() {
  const coordinator = new HiddenTreeNodeStateCoordinator<Record<number, string[]>>();
  const pendingRead = deferred<Record<number, string[]>>();
  let committedValue: Record<number, string[]> | undefined;
  let writeCompleted = false;

  const initialization = coordinator.initialize(
    () => pendingRead.promise,
    (value) => {
      committedValue = value;
    },
  );
  await nextMicrotask();

  const write = coordinator.write(async () => {
    writeCompleted = true;
  });

  await nextMicrotask();
  assert.equal(writeCompleted, false);
  assert.equal(committedValue, undefined);

  const storedValue = { 1: ['existing'] };
  pendingRead.resolve(storedValue);
  assert.equal(await initialization, true);
  await write;
  assert.equal(writeCompleted, true);
  assert.equal(committedValue, storedValue);
}

async function testConcurrentChangesPreserveInitializedData() {
  const coordinator = new HiddenTreeNodeStateCoordinator<Record<number, string[]>>();
  const pendingRead = deferred<Record<number, string[]>>();
  let state: Record<number, string[]> | null = null;
  const persistedValues: string[][] = [];

  const applyChanges = async (changedKeys: { add: string[]; delete: string[] }) => {
    await coordinator.initialize(
      () => pendingRead.promise,
      (value) => {
        state = value;
      },
    );
    await coordinator.write(async () => {
      const currentState: Record<number, string[]> = state || {};
      const nextIds = applyHiddenTreeNodeChanges(currentState[1] || [], changedKeys);
      state = { ...currentState, 1: nextIds };
      persistedValues.push([...nextIds]);
    });
  };

  const firstChange = applyChanges({ add: ['new'], delete: [] });
  const secondChange = applyChanges({ add: [], delete: ['existing'] });
  await nextMicrotask();

  pendingRead.resolve({ 1: ['existing'], 2: ['other-datasource'] });
  await Promise.all([firstChange, secondChange]);

  assert.deepEqual(state, { 1: ['new'], 2: ['other-datasource'] });
  assert.deepEqual(persistedValues, [['existing', 'new'], ['new']]);
}

async function testWritesRunInInvocationOrder() {
  const coordinator = new HiddenTreeNodeStateCoordinator<Record<number, string[]>>();
  const firstWrite = deferred<void>();
  const events: string[] = [];

  const first = coordinator.write(async () => {
    events.push('first:start');
    await firstWrite.promise;
    events.push('first:end');
  });
  const second = coordinator.write(async () => {
    events.push('second');
  });

  await nextMicrotask();
  assert.deepEqual(events, ['first:start']);

  firstWrite.resolve();
  await Promise.all([first, second]);
  assert.deepEqual(events, ['first:start', 'first:end', 'second']);
}

async function testPendingReadIsInvalidatedByReset() {
  const coordinator = new HiddenTreeNodeStateCoordinator<Record<number, string[]>>();
  const pendingRead = deferred<Record<number, string[]>>();
  let committedValue: Record<number, string[]> | undefined;

  const initialization = coordinator.initialize(
    () => pendingRead.promise,
    (value) => {
      committedValue = value;
    },
  );
  await nextMicrotask();

  coordinator.reset();
  pendingRead.resolve({ 1: ['stale'] });

  assert.equal(await initialization, false);
  assert.equal(committedValue, undefined);
}

async function testResetWaitsForWritesBeforeReadingAgain() {
  const coordinator = new HiddenTreeNodeStateCoordinator<Record<number, string[]>>();
  const pendingWrite = deferred<void>();
  const events: string[] = [];

  const write = coordinator.write(async () => {
    events.push('write:start');
    await pendingWrite.promise;
    events.push('write:end');
  });
  coordinator.reset();

  const initialization = coordinator.initialize(
    async () => {
      events.push('read');
      return { 1: ['fresh'] };
    },
    () => {
      events.push('commit');
    },
  );

  await nextMicrotask();
  assert.deepEqual(events, ['write:start']);

  pendingWrite.resolve();
  await write;
  assert.equal(await initialization, true);
  assert.deepEqual(events, ['write:start', 'write:end', 'read', 'commit']);
}

async function testInitializedStateDoesNotReadAgain() {
  const coordinator = new HiddenTreeNodeStateCoordinator<Record<number, string[]>>();
  const pendingRead = deferred<Record<number, string[]>>();
  let readCount = 0;

  const first = coordinator.initialize(
    () => {
      readCount += 1;
      return pendingRead.promise;
    },
    () => undefined,
  );
  const joined = coordinator.initialize(
    async () => {
      readCount += 1;
      return {};
    },
    () => undefined,
  );

  assert.equal(first, joined);
  await nextMicrotask();
  assert.equal(readCount, 1);

  pendingRead.resolve({});
  assert.equal(await first, true);
  assert.equal(await joined, true);

  const afterInitialization = await coordinator.initialize(async () => {
    readCount += 1;
    return {};
  }, () => undefined);
  assert.equal(afterInitialization, false);
  assert.equal(readCount, 1);
}

async function testFailedWriteDoesNotBlockLaterWrites() {
  const coordinator = new HiddenTreeNodeStateCoordinator<Record<number, string[]>>();
  const events: string[] = [];

  const failedWrite = coordinator.write(async () => {
    events.push('failed');
    throw new Error('write failed');
  });
  const successfulWrite = coordinator.write(async () => {
    events.push('successful');
  });

  await assert.rejects(failedWrite, /write failed/);
  await successfulWrite;
  assert.deepEqual(events, ['failed', 'successful']);
}

async function testForcedRereadSeesAnotherWindowChanges() {
  const coordinator = new HiddenTreeNodeStateCoordinator<Record<number, string[]>>();
  const reads: string[][] = [];
  const commits: Record<number, string[]>[] = [];

  const firstInitialization = coordinator.initialize(
    async () => {
      reads.push(['first']);
      return { 1: ['first'] };
    },
    (value) => commits.push(value),
  );
  assert.equal(await firstInitialization, true);

  const secondInitialization = coordinator.initialize(
    async () => {
      reads.push(['second']);
      return { 1: ['second'] };
    },
    (value) => commits.push(value),
    true,
  );

  assert.equal(await secondInitialization, true);
  assert.deepEqual(reads, [['first'], ['second']]);
  assert.deepEqual(commits, [{ 1: ['first'] }, { 1: ['second'] }]);
}

async function testOverlappingRefreshesAndChangesPreserveFreshHiddenIds() {
  const coordinator = new HiddenTreeNodeStateCoordinator<Record<number, string[]>>();
  let state = { 1: ['ALPHA'], 2: ['other'] };
  await coordinator.initialize(async () => state, (value) => { state = value; });
  const read = deferred<typeof state>();
  let reads = 0;
  const refresh = () => coordinator.initialize(() => { reads++; return read.promise; },
    (value) => { state = value; }, true);
  const first = refresh();
  await nextMicrotask();
  const changes = ['BETA', 'GAMMA'].map((key) => coordinator.write(async () => {
    state = { ...state, 1: applyHiddenTreeNodeChanges(state[1], { add: [key], delete: [] }) };
  }));
  const second = refresh();
  assert.equal(first, second, 'repeated refreshes must join the current read');
  assert.deepEqual(state, { 1: ['ALPHA'], 2: ['other'] }, 'refresh keeps the last known state');
  read.resolve({ 1: ['ALPHA', 'external'], 2: ['other'] });
  await Promise.all([first, second, ...changes]);
  assert.equal(reads, 1);
  assert.deepEqual(state, { 1: ['ALPHA', 'external', 'BETA', 'GAMMA'], 2: ['other'] });
}

async function testRefreshAfterQueuedWriteReadsThePersistedChange() {
  const coordinator = new HiddenTreeNodeStateCoordinator<string[]>();
  let persisted = ['ALPHA'];
  let state = persisted;
  const read = () => coordinator.initialize(async () => persisted, (value) => { state = value; }, true);
  await read();
  const gate = deferred<void>();
  const write = coordinator.write(async () => {
    await gate.promise;
    persisted = ['ALPHA', 'BETA'];
  });
  const refresh = read();
  gate.resolve();
  await Promise.all([write, refresh]);
  assert.deepEqual(state, ['ALPHA', 'BETA']);
}

async function testFailedRefreshPreservesStateAndBlocksItsWaitingWrite() {
  const coordinator = new HiddenTreeNodeStateCoordinator<string[]>();
  let state = ['ALPHA'];
  await coordinator.initialize(async () => state, (value) => { state = value; });
  const gate = deferred<string[]>();
  const refresh = coordinator.initialize(() => gate.promise, (value) => { state = value; }, true);
  let wrote = false;
  const write = coordinator.write(async () => { wrote = true; });
  const failedRefresh = assert.rejects(refresh, /read failed/);
  const failedWrite = assert.rejects(write, /read failed/);
  gate.reject(new Error('read failed'));
  await Promise.all([failedRefresh, failedWrite]);
  assert.deepEqual(state, ['ALPHA']);
  assert.equal(wrote, false);
  assert.equal(await coordinator.initialize(async () => ['ALPHA', 'external'],
    (value) => { state = value; }), true, 'a failed refresh remains retryable');
  assert.deepEqual(state, ['ALPHA', 'external']);
}

async function run() {
  await testWriteWaitsForPendingInitialization();
  await testConcurrentChangesPreserveInitializedData();
  await testWritesRunInInvocationOrder();
  await testPendingReadIsInvalidatedByReset();
  await testResetWaitsForWritesBeforeReadingAgain();
  await testInitializedStateDoesNotReadAgain();
  await testFailedWriteDoesNotBlockLaterWrites();
  await testForcedRereadSeesAnotherWindowChanges();
  await testOverlappingRefreshesAndChangesPreserveFreshHiddenIds();
  await testRefreshAfterQueuedWriteReadsThePersistedChange();
  await testFailedRefreshPreservesStateAndBlocksItsWaitingWrite();
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
