import assert from 'node:assert/strict';
import { isMonacoCancellationError, runMonacoDisposalSafely } from './monaco';

const run = async () => {
  assert.equal(isMonacoCancellationError('Canceled'), true);
  assert.equal(isMonacoCancellationError({ name: 'Canceled', message: 'Canceled' }), true);
  assert.equal(isMonacoCancellationError(new Error('Canceled')), true);
  assert.equal(isMonacoCancellationError(new Error('boom')), false);

  let synchronousCleanupRan = false;
  runMonacoDisposalSafely(() => {
    synchronousCleanupRan = true;
    throw Object.assign(new Error('Canceled'), { name: 'Canceled' });
  });
  assert.equal(synchronousCleanupRan, true);

  const unhandledRejections: unknown[] = [];
  const rejectionListener = (reason: unknown) => unhandledRejections.push(reason);
  process.on('unhandledRejection', rejectionListener);
  runMonacoDisposalSafely(() => Promise.reject(Object.assign(new Error('Canceled'), { name: 'Canceled' })));
  await new Promise((resolve) => setTimeout(resolve, 0));
  process.off('unhandledRejection', rejectionListener);
  assert.deepEqual(unhandledRejections, []);

  assert.throws(
    () =>
      runMonacoDisposalSafely(() => {
        throw new Error('unexpected disposal failure');
      }),
    /unexpected disposal failure/,
  );
};

run().then(() => {
  console.log('monaco lifecycle tests passed');
});
