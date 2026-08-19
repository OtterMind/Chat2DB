import assert from 'node:assert/strict';
import { invalidateLatestRequest } from '@/utils/latestRequest';
import { loadLatestAccountGrants } from './accountGrantsRequest';

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function testStaleResponseCannotReplaceLatestAccount() {
  const requestGenerationRef = { current: 0 };
  const firstRequest = deferred<string[]>();
  const secondRequest = deferred<string[]>();
  let displayedGrants = ['initial'];
  let settleCount = 0;

  const runFirstRequest = loadLatestAccountGrants(
    requestGenerationRef,
    () => firstRequest.promise,
    (grants) => {
      displayedGrants = grants;
    },
    () => {
      settleCount += 1;
    },
  );
  const runSecondRequest = loadLatestAccountGrants(
    requestGenerationRef,
    () => secondRequest.promise,
    (grants) => {
      displayedGrants = grants;
    },
    () => {
      settleCount += 1;
    },
  );

  firstRequest.resolve(['old-account-grant']);
  await runFirstRequest;

  assert.deepEqual(displayedGrants, ['initial']);
  assert.equal(settleCount, 0, 'an old request must not stop the latest request spinner');

  secondRequest.resolve(['new-account-grant']);
  await runSecondRequest;

  assert.deepEqual(displayedGrants, ['new-account-grant']);
  assert.equal(settleCount, 1);
}

async function testLatestFailureClearsGrantsAndSettles() {
  const requestGenerationRef = { current: 0 };
  let displayedGrants = ['previous-grant'];
  let settled = false;

  await loadLatestAccountGrants(
    requestGenerationRef,
    async () => {
      throw new Error('request failed');
    },
    (grants) => {
      displayedGrants = grants;
    },
    () => {
      settled = true;
    },
  );

  assert.deepEqual(displayedGrants, []);
  assert.equal(settled, true);
}

async function testUnmountedRequestCannotUpdateState() {
  const requestGenerationRef = { current: 0 };
  const request = deferred<string[]>();
  let updateCount = 0;
  let settleCount = 0;

  const runRequest = loadLatestAccountGrants(
    requestGenerationRef,
    () => request.promise,
    () => {
      updateCount += 1;
    },
    () => {
      settleCount += 1;
    },
  );

  invalidateLatestRequest(requestGenerationRef);
  request.resolve(['ignored-grant']);
  await runRequest;

  assert.equal(updateCount, 0);
  assert.equal(settleCount, 0);
}

Promise.all([
  testStaleResponseCannotReplaceLatestAccount(),
  testLatestFailureClearsGrantsAndSettles(),
  testUnmountedRequestCannotUpdateState(),
])
  .then(() => {
    console.log('Account grants request tests passed');
  })
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
