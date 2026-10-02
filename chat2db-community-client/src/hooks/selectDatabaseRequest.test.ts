import assert from 'node:assert/strict';
import { invalidateLatestRequest } from '@/utils/latestRequest';
import { createSelectDatabaseRequestCoordinator, runLatestSelectionRequest } from './selectDatabaseRequest';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function testLateSuccessCannotReplaceLatestOptions() {
  const generationRef = { current: 0 };
  const first = deferred<string[]>();
  const second = deferred<string[]>();
  const updates: string[][] = [];
  const failures: unknown[] = [];

  const firstResult = runLatestSelectionRequest(
    generationRef,
    () => first.promise,
    (value) => updates.push(value),
    (error) => failures.push(error),
  );
  const secondResult = runLatestSelectionRequest(
    generationRef,
    () => second.promise,
    (value) => updates.push(value),
    (error) => failures.push(error),
  );

  second.resolve(['new']);
  assert.equal(await secondResult, true);
  first.resolve(['old']);
  assert.equal(await firstResult, false);
  assert.deepEqual(updates, [['new']]);
  assert.deepEqual(failures, []);
}

async function testLateFailureCannotClearLatestOptions() {
  const generationRef = { current: 0 };
  const first = deferred<string[]>();
  const second = deferred<string[]>();
  const updates: string[][] = [];
  const failures: unknown[] = [];

  const firstResult = runLatestSelectionRequest(
    generationRef,
    () => first.promise,
    (value) => updates.push(value),
    (error) => failures.push(error),
  );
  const secondResult = runLatestSelectionRequest(
    generationRef,
    () => second.promise,
    (value) => updates.push(value),
    (error) => failures.push(error),
  );

  second.resolve(['new']);
  assert.equal(await secondResult, true);
  first.reject(new Error('old failed'));
  assert.equal(await firstResult, false);
  assert.deepEqual(updates, [['new']]);
  assert.deepEqual(failures, []);
}

async function testInvalidationSuppressesUnmountedRequest() {
  const generationRef = { current: 0 };
  const pending = deferred<string[]>();
  const updates: string[][] = [];
  const result = runLatestSelectionRequest(
    generationRef,
    () => pending.promise,
    (value) => updates.push(value),
    () => assert.fail('invalidated failure callback must not run'),
  );

  invalidateLatestRequest(generationRef);
  pending.resolve(['late']);
  assert.equal(await result, false);
  assert.deepEqual(updates, []);
}

async function testCoordinatorOwnsDataSourceLifecycle() {
  const coordinator = createSelectDatabaseRequestCoordinator();
  const first = deferred<string[]>();
  const second = deferred<string[]>();
  const updates: string[][] = [];
  const failures: unknown[] = [];

  const firstResult = coordinator.run(
    'dataSource',
    () => first.promise,
    (value) => updates.push(value),
    (error) => failures.push(error),
  );
  const secondResult = coordinator.run(
    'dataSource',
    () => second.promise,
    (value) => updates.push(value),
    (error) => failures.push(error),
  );
  second.resolve(['new-source']);
  assert.equal(await secondResult, true);
  first.reject(new Error('old source failed'));
  assert.equal(await firstResult, false);
  assert.deepEqual(updates, [['new-source']]);
  assert.deepEqual(failures, []);

  const unmounted = deferred<string[]>();
  const unmountedResult = coordinator.run(
    'dataSource',
    () => unmounted.promise,
    (value) => updates.push(value),
    (error) => failures.push(error),
  );
  coordinator.invalidateAll();
  unmounted.resolve(['unmounted']);
  assert.equal(await unmountedResult, false);
  assert.deepEqual(updates, [['new-source']]);
}

void Promise.all([
  testLateSuccessCannotReplaceLatestOptions(),
  testLateFailureCannotClearLatestOptions(),
  testInvalidationSuppressesUnmountedRequest(),
  testCoordinatorOwnsDataSourceLifecycle(),
]).then(() => {
  console.log('Select database request ownership tests passed');
});
