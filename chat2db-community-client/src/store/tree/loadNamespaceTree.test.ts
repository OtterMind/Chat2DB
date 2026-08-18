import assert from 'node:assert/strict';
import { LatestLoadCoordinator, loadNamespaceTree } from './loadNamespaceTree';

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function rejectableDeferred() {
  let reject!: (error: Error) => void;
  const promise = new Promise<void>((_resolve, rejectPromise) => {
    reject = rejectPromise;
  });
  return { promise, reject };
}

async function testRejectedRequestPreservesError() {
  const error = new Error('request failed');
  const result = await loadNamespaceTree<string>(() => Promise.reject(error));
  assert.deepEqual(result, { ok: false, error });
}

async function testFulfilledRequestPreservesTreeItems() {
  const result = await loadNamespaceTree(() => Promise.resolve(['datasource']));
  assert.deepEqual(result, { ok: true, items: ['datasource'] });
}

async function testConcurrentInitialLoadsShareOneRequest() {
  const coordinator = new LatestLoadCoordinator<string, void>(() => undefined);
  const request = deferred();
  let loadCount = 0;
  const load = async () => {
    loadCount += 1;
    await request.promise;
  };

  const first = coordinator.run('root', { supersede: false }, load);
  const second = coordinator.run('root', { supersede: false }, load);

  assert.equal(first, second);
  assert.equal(loadCount, 1);
  assert.equal(coordinator.hasPending('root'), true);
  request.resolve();
  await first;
  await Promise.resolve();
  assert.equal(coordinator.hasPending('root'), false);
}

async function testEveryRefreshSupersedesThePreviousRequest() {
  const coordinator = new LatestLoadCoordinator<string, string>(() => 'invalidated');
  const initialRequest = deferred();
  const firstRefreshRequest = deferred();
  const secondRefreshRequest = deferred();
  let initialIsCurrent = true;
  let firstRefreshIsCurrent = true;
  let secondRefreshIsCurrent = false;

  const initial = coordinator.run('root', { supersede: false }, async (isCurrent) => {
    await initialRequest.promise;
    initialIsCurrent = isCurrent();
    return 'initial';
  });
  const firstRefresh = coordinator.run('root', { supersede: true }, async (isCurrent) => {
    await firstRefreshRequest.promise;
    firstRefreshIsCurrent = isCurrent();
    return 'first-refresh';
  });
  const joinedFirstRefresh = coordinator.run('root', { supersede: false }, async () => 'unused');
  const secondRefresh = coordinator.run('root', { supersede: true }, async (isCurrent) => {
    await secondRefreshRequest.promise;
    secondRefreshIsCurrent = isCurrent();
    return 'second-refresh';
  });
  const joinedSecondRefresh = coordinator.run('root', { supersede: false }, async () => 'unused');

  assert.notEqual(initial, firstRefresh);
  assert.equal(joinedFirstRefresh, firstRefresh);
  assert.notEqual(secondRefresh, firstRefresh);
  assert.equal(joinedSecondRefresh, secondRefresh);

  initialRequest.resolve();
  firstRefreshRequest.resolve();
  secondRefreshRequest.resolve();
  const results = await Promise.all([initial, firstRefresh, secondRefresh]);

  assert.deepEqual(results, ['second-refresh', 'second-refresh', 'second-refresh']);
  assert.equal(initialIsCurrent, false);
  assert.equal(firstRefreshIsCurrent, false);
  assert.equal(secondRefreshIsCurrent, true);
}

async function testSupersededFailureFollowsTheLatestRequest() {
  const coordinator = new LatestLoadCoordinator<string, string>(() => 'invalidated');
  const oldRequest = rejectableDeferred();
  const latestRequest = deferred();

  const oldPromise = coordinator.run('root', { supersede: false }, async () => {
    await oldRequest.promise;
    return 'old';
  });
  const latestPromise = coordinator.run('root', { supersede: true }, async () => {
    await latestRequest.promise;
    return 'latest';
  });

  oldRequest.reject(new Error('superseded request failed'));
  latestRequest.resolve();

  assert.equal(await oldPromise, 'latest');
  assert.equal(await latestPromise, 'latest');
}

async function testLowerPriorityLoadCannotWeakenRefreshContract() {
  type LoadResult = { committed: true } | { committed: false; error: Error };
  const coordinator = new LatestLoadCoordinator<string, LoadResult>(() => ({ committed: false, error: new Error('invalidated') }));
  const strongRequest = deferred();
  const refreshError = new Error('refresh failed');
  let weakerLoadCount = 0;

  const strongRawPromise = coordinator.run(
    'root',
    { supersede: true, priority: 1 },
    async () => {
      await strongRequest.promise;
      return { committed: false, error: refreshError };
    },
  );
  const strictCaller = strongRawPromise.then((result) => {
    if (!result.committed) {
      throw result.error;
    }
    return true;
  });
  const weakerRawPromise = coordinator.run(
    'root',
    { supersede: true, priority: 0 },
    async () => {
      weakerLoadCount += 1;
      return { committed: true };
    },
  );
  const tolerantCaller = weakerRawPromise.then((result) => result.committed);

  assert.equal(weakerRawPromise, strongRawPromise);
  assert.equal(weakerLoadCount, 0);
  strongRequest.resolve();

  await assert.rejects(strictCaller, (error) => error === refreshError);
  assert.equal(await tolerantCaller, false);
}

async function testSupersededCallerDoesNotWaitForTheOldTransport() {
  const coordinator = new LatestLoadCoordinator<string, string>(() => 'invalidated');
  const oldRequest = deferred();
  const latestRequest = deferred();
  let oldTransportSettled = false;

  const oldPromise = coordinator.run('root', { supersede: false }, async () => {
    await oldRequest.promise;
    oldTransportSettled = true;
    return 'old';
  });
  const latestPromise = coordinator.run('root', { supersede: true }, async () => {
    await latestRequest.promise;
    return 'latest';
  });

  latestRequest.resolve();

  assert.equal(await latestPromise, 'latest');
  assert.equal(await oldPromise, 'latest');
  assert.equal(oldTransportSettled, false);

  oldRequest.resolve();
}

async function testInvalidationDetachesPendingRequest() {
  const coordinator = new LatestLoadCoordinator<string, void>(() => undefined);
  const oldRequest = deferred();
  const newRequest = deferred();
  let oldRequestIsCurrent = true;
  let newRequestIsCurrent = false;

  const oldPromise = coordinator.run('root', { supersede: false }, async (isCurrent) => {
    await oldRequest.promise;
    oldRequestIsCurrent = isCurrent();
  });
  coordinator.invalidate('root');
  await oldPromise;
  assert.equal(oldRequestIsCurrent, true);
  const newPromise = coordinator.run('root', { supersede: false }, async (isCurrent) => {
    await newRequest.promise;
    newRequestIsCurrent = isCurrent();
  });

  assert.notEqual(oldPromise, newPromise);
  assert.equal(coordinator.run('root', { supersede: false }, async () => undefined), newPromise);
  oldRequest.resolve();
  await Promise.resolve();
  assert.equal(oldRequestIsCurrent, false);

  newRequest.resolve();
  await newPromise;
  assert.equal(newRequestIsCurrent, true);
}

async function testInvalidateAllDetachesEveryPendingRequest() {
  const coordinator = new LatestLoadCoordinator<string, void>(() => undefined);
  const rootRequest = deferred();
  const childRequest = deferred();
  let rootIsCurrent = true;
  let childIsCurrent = true;

  const rootPromise = coordinator.run('root', { supersede: false }, async (isCurrent) => {
    await rootRequest.promise;
    rootIsCurrent = isCurrent();
  });
  const childPromise = coordinator.run('child', { supersede: false }, async (isCurrent) => {
    await childRequest.promise;
    childIsCurrent = isCurrent();
  });

  coordinator.invalidateAll();
  rootRequest.resolve();
  childRequest.resolve();
  await Promise.all([rootPromise, childPromise]);

  assert.equal(rootIsCurrent, false);
  assert.equal(childIsCurrent, false);
}

async function testMatchingInvalidationDetachesAnEntireScope() {
  const coordinator = new LatestLoadCoordinator<string, void>(() => undefined);
  const dataSourceRequest = deferred();
  const databaseRequest = deferred();
  const otherRequest = deferred();
  let dataSourceIsCurrent = true;
  let databaseIsCurrent = true;
  let otherIsCurrent = false;

  const dataSourcePromise = coordinator.run('dataSource_1', { supersede: false }, async (isCurrent) => {
    await dataSourceRequest.promise;
    dataSourceIsCurrent = isCurrent();
  });
  const databasePromise = coordinator.run('dataSource_1-database_app', { supersede: false }, async (isCurrent) => {
    await databaseRequest.promise;
    databaseIsCurrent = isCurrent();
  });
  const otherPromise = coordinator.run('dataSource_10-database_app', { supersede: false }, async (isCurrent) => {
    await otherRequest.promise;
    otherIsCurrent = isCurrent();
  });

  coordinator.invalidateMatching((key) => key === 'dataSource_1' || key.startsWith('dataSource_1-'));
  dataSourceRequest.resolve();
  databaseRequest.resolve();
  otherRequest.resolve();
  await Promise.all([dataSourcePromise, databasePromise, otherPromise]);

  assert.equal(dataSourceIsCurrent, false);
  assert.equal(databaseIsCurrent, false);
  assert.equal(otherIsCurrent, true);
}

async function testLateNodeResponseCannotOverwriteLatestRefresh() {
  const coordinator = new LatestLoadCoordinator<string, void>(() => undefined);
  const initialRequest = deferred();
  const refreshRequest = deferred();
  let committedChildren = ['existing'];

  const initialPromise = coordinator.run('dataSource_1', { supersede: false }, async (isCurrent) => {
    await initialRequest.promise;
    if (isCurrent()) {
      committedChildren = ['stale'];
    }
  });
  const refreshPromise = coordinator.run('dataSource_1', { supersede: true }, async (isCurrent) => {
    await refreshRequest.promise;
    if (isCurrent()) {
      committedChildren = ['latest'];
    }
  });

  refreshRequest.resolve();
  await refreshPromise;
  initialRequest.resolve();
  await initialPromise;

  assert.deepEqual(committedChildren, ['latest']);
}

Promise.all([
  testRejectedRequestPreservesError(),
  testFulfilledRequestPreservesTreeItems(),
  testConcurrentInitialLoadsShareOneRequest(),
  testEveryRefreshSupersedesThePreviousRequest(),
  testSupersededFailureFollowsTheLatestRequest(),
  testLowerPriorityLoadCannotWeakenRefreshContract(),
  testSupersededCallerDoesNotWaitForTheOldTransport(),
  testInvalidationDetachesPendingRequest(),
  testInvalidateAllDetachesEveryPendingRequest(),
  testMatchingInvalidationDetachesAnEntireScope(),
  testLateNodeResponseCannotOverwriteLatestRefresh(),
])
  .then(() => {
    console.log('Tree namespace loading tests passed');
  })
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
