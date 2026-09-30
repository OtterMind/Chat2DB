import assert from 'node:assert/strict';
import { createStagedFileOwnership } from './stagedFileOwnership';

const run = async () => {
  const released: string[] = [];
  const ownership = createStagedFileOwnership(async (fileId) => {
    released.push(fileId);
  });

  ownership.own('first');
  ownership.own('second');
  assert.equal(ownership.owns('first'), true);
  await ownership.release('first');
  await ownership.release('first');
  assert.deepEqual(released, ['first']);

  await ownership.releaseAll();
  assert.deepEqual(released, ['first', 'second']);
  assert.equal(ownership.owns('second'), false);

  ownership.own('submitted');
  ownership.own('selected-after-submit');
  ownership.transfer(['submitted']);
  await ownership.releaseAll();
  assert.deepEqual(released, ['first', 'second', 'selected-after-submit']);

  const failedRelease = createStagedFileOwnership(async () => {
    throw new Error('offline');
  });
  failedRelease.own('best-effort');
  await assert.doesNotReject(() => failedRelease.releaseAll());
  assert.equal(failedRelease.owns('best-effort'), false);

  console.log('Staged import file ownership tests passed');
};

void run();
