import assert from 'node:assert/strict';
import type { DatabaseTypeCode } from '@/constants';
import { formatSqlWithRequester, type SqlFormatRequest } from './formatSqlRequest';

const MYSQL = 'MYSQL' as DatabaseTypeCode;
const POSTGRESQL = 'POSTGRESQL' as DatabaseTypeCode;

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
}

const deferred = <T>(): Deferred<T> => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
};

const settlementWithinTurn = <T>(promise: Promise<T>) =>
  Promise.race([
    promise.then(
      (value) => ({ status: 'resolved' as const, value }),
      (error) => ({ status: 'rejected' as const, error }),
    ),
    new Promise<{ status: 'pending' }>((resolve) => {
      setImmediate(() => resolve({ status: 'pending' }));
    }),
  ]);

const testSuccessfulFormattingUsesRequester = async () => {
  let capturedRequest: SqlFormatRequest | undefined;
  const formatted = await formatSqlWithRequester(async (request) => {
    capturedRequest = request;
    return 'SELECT\n  1';
  }, 'select 1', MYSQL);

  assert.equal(formatted, 'SELECT\n  1');
  assert.deepEqual(capturedRequest, { sql: 'select 1', dbType: MYSQL });
};

const testRejectedFormattingFallsBackWithoutRemainingPending = async () => {
  const request = deferred<string>();
  const originalSql = 'select * from unfinished_query';
  const formatted = formatSqlWithRequester(() => request.promise, originalSql, POSTGRESQL);

  request.reject(new Error('format service unavailable'));

  assert.deepEqual(await settlementWithinTurn(formatted), {
    status: 'resolved',
    value: originalSql,
  });
};

const testSynchronousRequesterFailureFallsBack = async () => {
  const originalSql = 'select * from synchronous_failure';
  const formatted = formatSqlWithRequester(() => {
    throw new Error('request setup failed');
  }, originalSql, MYSQL);

  assert.equal(await formatted, originalSql);
};

const main = async () => {
  await testSuccessfulFormattingUsesRequester();
  await testRejectedFormattingFallsBackWithoutRemainingPending();
  await testSynchronousRequesterFailureFallsBack();
  console.log('SQL format request tests passed');
};

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
