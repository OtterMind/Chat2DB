import assert from 'node:assert/strict';
import type { IInnodbStatusResponse } from '@/service/sql';
import {
  applyInnodbStatusFailure,
  applyInnodbStatusSuccess,
  beginInnodbStatusRefresh,
  getInnodbStatusCopyText,
  initialInnodbStatusViewState,
  loadLatestInnodbStatus,
  type InnodbStatusViewState,
} from './state';

const successfulResult: IInnodbStatusResponse = {
  rawText: "UPDATE orders SET status='paid', password=<redacted> WHERE id=1",
  capturedAt: '2026-08-31T10:00:00Z',
  sections: [],
  latestDeadlock: {
    found: false,
    message: 'The server did not provide a latest deadlock.',
    transactions: [],
  },
  messages: [],
};

const loaded = applyInnodbStatusSuccess(initialInnodbStatusViewState, successfulResult, 'fallback');
assert.equal(loaded.result, successfulResult, 'successful refresh stores the latest structured result');
assert.equal(loaded.lastSuccessAt, '2026-08-31T10:00:00Z', 'successful refresh records the server timestamp');
assert.equal(loaded.error, null, 'successful refresh clears previous errors');

const refreshing = beginInnodbStatusRefresh(loaded);
assert.equal(refreshing.loading, true, 'refresh starts without clearing the visible result');
assert.equal(refreshing.result, successfulResult, 'refresh keeps the previous successful result visible');

const failed = applyInnodbStatusFailure(refreshing, { errorMessage: 'PROCESS privilege required' });
assert.equal(failed.loading, false, 'failed refresh clears loading');
assert.equal(failed.result, successfulResult, 'failed refresh retains the previous successful result');
assert.equal(failed.lastSuccessAt, '2026-08-31T10:00:00Z', 'failed refresh retains the previous success timestamp');
assert.equal(failed.error, 'PROCESS privilege required', 'failed refresh exposes diagnostic messaging');

assert.equal(
  getInnodbStatusCopyText(failed.result),
  "UPDATE orders SET status='paid', password=<redacted> WHERE id=1",
  'copy uses the redacted raw monitor text supplied by the diagnostics API',
);
assert.equal(
  getInnodbStatusCopyText(failed.result).includes('secret-value'),
  false,
  'copy does not reintroduce redacted secrets',
);
assert.equal(getInnodbStatusCopyText(null), '', 'copy is empty when no successful result exists');

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
};

async function testStaleRefreshCannotReplaceLatestResult() {
  const generationRef = { current: 0 };
  const first = deferred<IInnodbStatusResponse>();
  const second = deferred<IInnodbStatusResponse>();
  let state = initialInnodbStatusViewState;
  const updateState = (updater: (current: InnodbStatusViewState) => InnodbStatusViewState) => {
    state = updater(state);
  };
  const oldResult = { ...successfulResult, capturedAt: 'old' };
  const newResult = { ...successfulResult, capturedAt: 'new' };

  const firstRun = loadLatestInnodbStatus(generationRef, () => first.promise, updateState, () => 'first');
  const secondRun = loadLatestInnodbStatus(generationRef, () => second.promise, updateState, () => 'second');
  first.resolve(oldResult);
  await firstRun;
  assert.equal(state.loading, true, 'a stale completion must not settle the latest request');
  assert.equal(state.result, null, 'a stale result must not become visible');

  second.resolve(newResult);
  await secondRun;
  assert.equal(state.loading, false);
  assert.equal(state.result, newResult);
}

async function testStaleFailureCannotReplaceLatestSuccess() {
  const generationRef = { current: 0 };
  const first = deferred<IInnodbStatusResponse>();
  const second = deferred<IInnodbStatusResponse>();
  let state = initialInnodbStatusViewState;
  const updateState = (updater: (current: InnodbStatusViewState) => InnodbStatusViewState) => {
    state = updater(state);
  };

  const firstRun = loadLatestInnodbStatus(generationRef, () => first.promise, updateState, () => 'first');
  const secondRun = loadLatestInnodbStatus(generationRef, () => second.promise, updateState, () => 'second');
  second.resolve(successfulResult);
  await secondRun;
  first.reject(new Error('stale failure'));
  await firstRun;

  assert.equal(state.result, successfulResult);
  assert.equal(state.error, null);
}

Promise.all([testStaleRefreshCannotReplaceLatestResult(), testStaleFailureCannotReplaceLatestSuccess()])
  .then(() => console.log('InnoDB status panel state tests passed.'))
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
