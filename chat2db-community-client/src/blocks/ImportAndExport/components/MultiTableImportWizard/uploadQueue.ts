export type AsyncTaskLimiter = <T>(task: () => Promise<T>) => Promise<T>;

export const createAsyncTaskLimiter = (maxParallel: number): AsyncTaskLimiter => {
  if (!Number.isInteger(maxParallel) || maxParallel < 1) {
    throw new Error('maxParallel must be a positive integer');
  }

  let active = 0;
  const waiting: Array<() => void> = [];

  const acquire = () =>
    new Promise<void>((resolve) => {
      const start = () => {
        active += 1;
        resolve();
      };
      if (active < maxParallel) start();
      else waiting.push(start);
    });

  const release = () => {
    active -= 1;
    waiting.shift()?.();
  };

  return async <T>(task: () => Promise<T>) => {
    await acquire();
    try {
      return await task();
    } finally {
      release();
    }
  };
};
