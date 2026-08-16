import assert from 'node:assert/strict';
import type { SSERequestCallbacks, SSERequestHandle } from './index';
import { createAbortableRequestHandle, createRequestHandle, createSSEStreamError } from './requestHandle';
import { guardSSERequestCallbacks, SSERequestOwner } from './requestOwner';

interface PendingRequest {
  handle: SSERequestHandle;
  signal: () => AbortSignal;
  resolve: () => void;
}

const createPendingRequest = (): PendingRequest => {
  let requestSignal: AbortSignal | undefined;
  let resolveRequest!: () => void;
  const handle = createAbortableRequestHandle(async (signal) => {
    requestSignal = signal;
    await new Promise<void>((resolve, reject) => {
      resolveRequest = resolve;
      signal.addEventListener(
        'abort',
        () => {
          const error = new Error('The request was stopped');
          error.name = 'AbortError';
          reject(error);
        },
        { once: true },
      );
    });
  });

  return {
    handle,
    signal: () => {
      assert.ok(requestSignal);
      return requestSignal;
    },
    resolve: () => resolveRequest(),
  };
};

const createTrackedHandle = (onStop?: () => void) => {
  let stopCount = 0;
  const done = Promise.resolve();
  const handle: SSERequestHandle = createRequestHandle(done, () => {
    stopCount += 1;
    onStop?.();
  });
  return { handle, stopCount: () => stopCount };
};

const testIndependentRequestHandles = async () => {
  const first = createPendingRequest();
  const second = createPendingRequest();
  await Promise.resolve();

  assert.notEqual(first.signal(), second.signal());
  first.handle.stop();
  first.handle.stop();

  assert.equal(first.signal().aborted, true);
  assert.equal(second.signal().aborted, false);
  await first.handle.done;

  second.resolve();
  await second.handle.done;
  assert.equal(second.signal().aborted, false);
  second.handle.stop();
  assert.equal(second.signal().aborted, false);
};

const testStoppedHandleResolvesAndRemainsAwaitable = async () => {
  let stopCount = 0;
  let releaseRequest!: () => void;
  const handle = createAbortableRequestHandle(
    async (signal) => {
      await new Promise<void>((resolve, reject) => {
        releaseRequest = resolve;
        signal.addEventListener('abort', () => {
          const error = new Error('The request was stopped');
          error.name = 'AbortError';
          reject(error);
        });
      });
    },
    {
      onStop: () => {
        stopCount += 1;
      },
    },
  );
  await Promise.resolve();

  let awaitFinished = false;
  const waiting = (async () => {
    await handle;
    awaitFinished = true;
  })();

  assert.equal(awaitFinished, false);
  handle.stop();
  await waiting;
  assert.equal(awaitFinished, true);
  assert.equal(stopCount, 1);
  assert.equal(handle.done, handle);
  await handle.finally(() => undefined);

  releaseRequest();
};

const testOwnerInvalidatesBeforeStopping = () => {
  const owner = new SSERequestOwner();
  let firstGeneration = 0;
  const first = createTrackedHandle(() => {
    assert.equal(owner.isActive(firstGeneration, first.handle), false);
  });
  firstGeneration = owner.begin();
  assert.equal(owner.attach(firstGeneration, first.handle), true);
  assert.equal(owner.isActive(firstGeneration, first.handle), true);

  const secondGeneration = owner.begin();
  assert.equal(first.stopCount(), 1);
  assert.equal(owner.isActive(firstGeneration, first.handle), false);

  const second = createTrackedHandle();
  assert.equal(owner.attach(secondGeneration, second.handle), true);
  first.handle.stop();
  assert.equal(owner.isActive(secondGeneration, second.handle), true);
  assert.equal(second.stopCount(), 0);

  assert.equal(owner.stop(), true);
  assert.equal(second.stopCount(), 1);
  assert.equal(owner.stop(), false);
  assert.equal(second.stopCount(), 1);
};

const testStaleHandleCannotAttach = () => {
  const owner = new SSERequestOwner();
  const generation = owner.begin();
  owner.stop();
  const stale = createTrackedHandle();

  assert.equal(owner.attach(generation, stale.handle), false);
  assert.equal(stale.stopCount(), 1);
};

const testSSEErrorEventAllowsSpecWhitespace = () => {
  const error = createSSEStreamError({
    event: ' error',
    data: ' {"type":"error","content":"stream failed"}',
  });

  assert.equal(error?.message, 'stream failed');
  assert.equal(createSSEStreamError({ event: 'done', data: '{}' }), undefined);
};

const testGuardedCallbacksIgnoreStaleAndUnmountedRequests = () => {
  const owner = new SSERequestOwner();
  let mounted = true;
  const handleRef: { current?: SSERequestHandle } = {};
  const generation = owner.begin();
  const calls: string[] = [];
  const callbacks: SSERequestCallbacks<string> = {
    onSuccess: () => calls.push('success'),
    onError: () => calls.push('error'),
    onUpdate: () => calls.push('update'),
    onStop: () => calls.push('stop'),
  };
  const guarded = guardSSERequestCallbacks(callbacks, () => mounted && owner.owns(generation, handleRef.current));

  guarded.onUpdate('before-attach');
  assert.deepEqual(calls, ['update']);

  const tracked = createTrackedHandle();
  handleRef.current = tracked.handle;
  assert.equal(owner.attach(generation, tracked.handle), true);
  guarded.onSuccess([]);
  assert.deepEqual(calls, ['update', 'success']);

  owner.begin();
  guarded.onUpdate('stale');
  guarded.onError(new Error('stale'));
  guarded.onStop?.();
  assert.deepEqual(calls, ['update', 'success']);

  const mountedGeneration = owner.begin();
  const mountedHandle = createTrackedHandle().handle;
  assert.equal(owner.attach(mountedGeneration, mountedHandle), true);
  const mountedGuard = guardSSERequestCallbacks(
    callbacks,
    () => mounted && owner.owns(mountedGeneration, mountedHandle),
  );
  mounted = false;
  mountedGuard.onUpdate('unmounted');
  assert.deepEqual(calls, ['update', 'success']);
};

const main = async () => {
  await testIndependentRequestHandles();
  await testStoppedHandleResolvesAndRemainsAwaitable();
  testOwnerInvalidatesBeforeStopping();
  testStaleHandleCannotAttach();
  testSSEErrorEventAllowsSpecWhitespace();
  testGuardedCallbacksIgnoreStaleAndUnmountedRequests();
};

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
