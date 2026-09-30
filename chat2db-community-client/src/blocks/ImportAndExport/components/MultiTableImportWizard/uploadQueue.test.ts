import assert from 'node:assert/strict';
import { createAsyncTaskLimiter } from './uploadQueue';

assert.throws(() => createAsyncTaskLimiter(0), /positive integer/);
assert.throws(() => createAsyncTaskLimiter(1.5), /positive integer/);

const run = async () => {
  const limit = createAsyncTaskLimiter(3);
  let active = 0;
  let maxActive = 0;
  const starts: number[] = [];

  await Promise.all(
    Array.from({ length: 9 }, (_, index) =>
      limit(async () => {
        starts.push(index);
        active += 1;
        maxActive = Math.max(maxActive, active);
        await new Promise((resolve) => setTimeout(resolve, 2));
        active -= 1;
        return index;
      }),
    ),
  );

  assert.equal(maxActive, 3);
  assert.deepEqual(starts, [0, 1, 2, 3, 4, 5, 6, 7, 8]);

  const rejected = createAsyncTaskLimiter(1);
  await assert.rejects(() => rejected(async () => Promise.reject(new Error('expected'))), /expected/);
  assert.equal(await rejected(async () => 42), 42);

  console.log('Multi-table upload queue tests passed');
};

void run();
