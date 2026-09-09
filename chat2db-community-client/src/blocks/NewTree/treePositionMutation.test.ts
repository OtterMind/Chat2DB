import assert from 'node:assert/strict';
import { TreePositionMutationCoordinator, TreePositionRefreshError } from './treePositionMutation';

function deferred() {
  let resolve!: () => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<void>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function testSuccessfulMoveRefreshesTree() {
  const coordinator = new TreePositionMutationCoordinator();
  let refreshCount = 0;

  const committed = await coordinator.run(
    'dataSource_1',
    () => Promise.resolve(),
    () => {
      refreshCount += 1;
      return Promise.resolve();
    },
  );

  assert.equal(committed, true);
  assert.equal(refreshCount, 1);
}

async function testFailedMoveDoesNotRefreshTree() {
  const coordinator = new TreePositionMutationCoordinator();
  let refreshCount = 0;

  await assert.rejects(
    coordinator.run(
      'dataSource_1',
      () => Promise.reject(new Error('move failed')),
      () => {
        refreshCount += 1;
        return Promise.resolve();
      },
    ),
    /move failed/,
  );

  assert.equal(refreshCount, 0);
}

async function testConcurrentMoveForSameNodeIsRejected() {
  const coordinator = new TreePositionMutationCoordinator();
  const request = deferred();
  let updateCount = 0;
  const updatePosition = () => {
    updateCount += 1;
    return request.promise;
  };

  const first = coordinator.run('dataSource_1', updatePosition, () => Promise.resolve());
  const second = coordinator.run('dataSource_1', updatePosition, () => Promise.resolve());

  assert.equal(await second, false);
  await Promise.resolve();
  assert.equal(updateCount, 1);
  request.resolve();
  assert.equal(await first, true);
}

async function testMoveCanRetryAfterFailure() {
  const coordinator = new TreePositionMutationCoordinator();
  const request = deferred();
  const first = coordinator.run('dataSource_1', () => request.promise, () => Promise.resolve());
  request.reject(new Error('move failed'));
  await assert.rejects(first, /move failed/);

  assert.equal(
    await coordinator.run('dataSource_1', () => Promise.resolve(), () => Promise.resolve()),
    true,
  );
}

async function testRefreshFailureIsDistinguishedAndCanRetry() {
  const coordinator = new TreePositionMutationCoordinator();
  let updateCount = 0;
  const updatePosition = () => {
    updateCount += 1;
    return Promise.resolve();
  };

  await assert.rejects(
    coordinator.run('dataSource_1', updatePosition, () => Promise.reject(new Error('refresh failed'))),
    TreePositionRefreshError,
  );

  assert.equal(
    await coordinator.run('dataSource_1', updatePosition, () => Promise.resolve()),
    true,
  );
  assert.equal(updateCount, 2);
}

async function main() {
  await testSuccessfulMoveRefreshesTree();
  await testFailedMoveDoesNotRefreshTree();
  await testConcurrentMoveForSameNodeIsRejected();
  await testMoveCanRetryAfterFailure();
  await testRefreshFailureIsDistinguishedAndCanRetry();
  console.log('Tree position mutation tests passed');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
