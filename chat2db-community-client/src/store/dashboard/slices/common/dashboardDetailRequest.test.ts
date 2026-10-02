import assert from 'node:assert/strict';
import { DashboardDetailRequestOwner } from './dashboardDetailRequest';

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}

function deferred<T>(): Deferred<T> {
  let resolvePromise: ((value: T) => void) | undefined;
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve;
  });
  return {
    promise,
    resolve: (value) => resolvePromise?.(value),
  };
}

async function testLatestDashboardDetailOwnsCurrentState() {
  const owner = new DashboardDetailRequestOwner();
  const firstRequest = deferred<{ id: number }>();
  const latestRequest = deferred<{ id: number }>();
  let currentDashboard: { id: number } | null = null;
  const commit = (dashboard: { id: number }) => {
    currentDashboard = dashboard;
  };

  const firstLoad = owner.run(() => firstRequest.promise, commit);
  const latestLoad = owner.run(() => latestRequest.promise, commit);
  latestRequest.resolve({ id: 2 });
  await latestLoad;
  assert.equal(currentDashboard?.id, 2);

  firstRequest.resolve({ id: 1 });
  await firstLoad;
  assert.equal(currentDashboard?.id, 2, 'an older response must not replace the latest dashboard');
}

async function testDirectSelectionInvalidatesPendingDetail() {
  const owner = new DashboardDetailRequestOwner();
  const request = deferred<{ id: number }>();
  let currentDashboard: { id: number } | null = null;
  const load = owner.run(
    () => request.promise,
    (dashboard) => {
      currentDashboard = dashboard;
    },
  );

  owner.invalidate();
  currentDashboard = { id: 2 };
  request.resolve({ id: 1 });
  await load;

  assert.equal(currentDashboard.id, 2, 'a direct selection must not be overwritten by an older request');
}

async function testLatestFailureStillInvalidatesOlderSuccess() {
  const owner = new DashboardDetailRequestOwner();
  const olderRequest = deferred<{ id: number }>();
  let currentDashboard: { id: number } | null = null;
  const olderLoad = owner.run(
    () => olderRequest.promise,
    (dashboard) => {
      currentDashboard = dashboard;
    },
  );
  const latestError = new Error('latest request failed');
  await assert.rejects(
    owner.run(
      () => Promise.reject(latestError),
      () => undefined,
    ),
    latestError,
  );

  olderRequest.resolve({ id: 1 });
  await olderLoad;
  assert.equal(currentDashboard, null, 'an older success must stay stale after the latest request fails');
}

void Promise.all([
  testLatestDashboardDetailOwnsCurrentState(),
  testDirectSelectionInvalidatesPendingDetail(),
  testLatestFailureStillInvalidatesOlderSuccess(),
])
  .then(() => console.log('Dashboard detail request ownership tests passed'))
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
