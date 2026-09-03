import assert from 'node:assert/strict';
import { McpTokenRequestCoordinator } from './mcpTokenRequestCoordinator';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((next, fail) => {
    resolve = next;
    reject = fail;
  });
  return { promise, resolve, reject };
}

async function run() {
  const ownership = new McpTokenRequestCoordinator();
  const mountToken = deferred<string>();
  const resetToken = deferred<string>();
  const committedTokens: string[] = [];
  const feedbackEvents: string[] = [];
  const mountOwner = ownership.beginMount();
  const mountRequest = mountToken.promise.then((token) => {
    if (ownership.isCurrent(mountOwner)) {
      committedTokens.push(token);
    }
  });
  const resetOwner = ownership.beginReset()!;
  const resetRequest = resetToken.promise.then((token) => {
    if (ownership.isCurrent(resetOwner)) {
      committedTokens.push(token);
      feedbackEvents.push('reset-success');
    }
    ownership.finishReset(resetOwner);
  });
  resetToken.resolve('reset-token');
  await resetRequest;
  mountToken.resolve('mount-token');
  await mountRequest;

  const singleFlight = new McpTokenRequestCoordinator();
  let resetStarts = 0;
  const firstReset = singleFlight.beginReset();
  if (firstReset) resetStarts += 1;
  if (singleFlight.beginReset()) resetStarts += 1;
  assert.equal(singleFlight.finishReset(firstReset!), true);
  if (singleFlight.beginReset()) resetStarts += 1;

  const unmount = new McpTokenRequestCoordinator();
  const unmountedMount = deferred<string>();
  const unmountedReset = deferred<string>();
  const postUnmountTokens: string[] = [];
  const postUnmountFeedback: string[] = [];
  const unmountedMountOwner = unmount.beginMount();
  const unmountedMountRequest = unmountedMount.promise.catch(() => {
    if (unmount.isCurrent(unmountedMountOwner)) {
      postUnmountFeedback.push('mount-error');
    }
  });
  const unmountedResetOwner = unmount.beginReset()!;
  const unmountedResetRequest = unmountedReset.promise.then((token) => {
    if (unmount.isCurrent(unmountedResetOwner)) {
      postUnmountTokens.push(token);
      postUnmountFeedback.push('reset-success');
    }
    unmount.finishReset(unmountedResetOwner);
  });
  unmount.invalidate();
  unmountedMount.reject(new Error('load failed'));
  unmountedReset.resolve('late-reset-token');
  await Promise.all([unmountedMountRequest, unmountedResetRequest]);

  assert.deepEqual(
    { committedTokens, feedbackEvents, resetStarts, postUnmountTokens, postUnmountFeedback },
    {
      committedTokens: ['reset-token'],
      feedbackEvents: ['reset-success'],
      resetStarts: 2,
      postUnmountTokens: [],
      postUnmountFeedback: [],
    },
  );

  console.log('MCP token request coordinator tests passed.');
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
