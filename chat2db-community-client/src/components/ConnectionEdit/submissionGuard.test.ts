import assert from 'node:assert/strict';
import { SubmissionGuard } from './submissionGuard';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function testConcurrentSubmissionIsRejected() {
  const guard = new SubmissionGuard();
  const request = deferred<number>();
  let submitCount = 0;
  const submit = () => {
    submitCount += 1;
    return request.promise;
  };

  const first = guard.run(submit);
  const second = guard.run(submit);

  assert.ok(first);
  assert.equal(second, null);
  await Promise.resolve();
  assert.equal(submitCount, 1);
  request.resolve(1);
  assert.equal(await first, 1);
}

async function testSubmissionCanRetryAfterFailure() {
  const guard = new SubmissionGuard();
  const failedRequest = deferred<void>();
  const first = guard.run(() => failedRequest.promise);
  assert.ok(first);
  failedRequest.reject(new Error('save failed'));
  await assert.rejects(first, /save failed/);

  const second = guard.run(() => Promise.resolve('saved'));
  assert.ok(second);
  assert.equal(await second, 'saved');
}

async function main() {
  await testConcurrentSubmissionIsRejected();
  await testSubmissionCanRetryAfterFailure();
  console.log('Connection submission guard tests passed');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
