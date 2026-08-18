import assert from 'node:assert/strict';
import { createTerminalAttachmentLifecycle } from './terminalAttachmentLifecycle';

interface AttachmentCall {
  sessionId: string;
  consumerId: string;
}

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function createScheduler() {
  let nextHandle = 0;
  const callbacks = new Map<number, () => void>();
  return {
    schedule(callback: () => void) {
      const handle = ++nextHandle;
      callbacks.set(handle, callback);
      return handle;
    },
    cancel(handle: unknown) {
      callbacks.delete(handle as number);
    },
    flush() {
      const pending = [...callbacks.values()];
      callbacks.clear();
      pending.forEach((callback) => callback());
    },
    get size() {
      return callbacks.size;
    },
  };
}

async function flushPromiseJobs() {
  for (let index = 0; index < 4; index += 1) {
    await Promise.resolve();
  }
}

function createHarness(attachImpl: (call: AttachmentCall) => Promise<unknown> = () => Promise.resolve()) {
  const scheduler = createScheduler();
  const attachCalls: AttachmentCall[] = [];
  const detachCalls: AttachmentCall[] = [];
  let consumerSequence = 0;
  const lifecycle = createTerminalAttachmentLifecycle({
    attach: (call) => {
      attachCalls.push(call);
      return attachImpl(call);
    },
    detach: (call) => {
      detachCalls.push(call);
      return Promise.resolve();
    },
    createConsumerId: (sessionId) => `${sessionId}:consumer-${++consumerSequence}`,
    scheduleDetach: scheduler.schedule,
    cancelScheduledDetach: scheduler.cancel,
  });
  return { lifecycle, scheduler, attachCalls, detachCalls };
}

async function testMigrationCancelsDetach() {
  const { lifecycle, scheduler, attachCalls, detachCalls } = createHarness();
  const sourcePane = lifecycle.acquire('session-1');
  sourcePane.release();
  assert.equal(scheduler.size, 1);

  const destinationPane = lifecycle.acquire('session-1');
  assert.equal(scheduler.size, 0, 'mounting the destination pane must cancel the pending detach');
  scheduler.flush();
  await flushPromiseJobs();
  assert.equal(attachCalls.length, 1, 'pane migration must reuse the active attachment');
  assert.equal(detachCalls.length, 0, 'pane migration must not detach the active session');

  destinationPane.release();
  scheduler.flush();
  await flushPromiseJobs();
  assert.equal(detachCalls.length, 1);
}

async function testOverlappingMountsKeepAttachmentAlive() {
  const { lifecycle, scheduler, attachCalls, detachCalls } = createHarness();
  const firstMount = lifecycle.acquire('session-1');
  const secondMount = lifecycle.acquire('session-1');
  firstMount.release();
  scheduler.flush();
  await flushPromiseJobs();
  assert.equal(attachCalls.length, 1);
  assert.equal(detachCalls.length, 0, 'one remaining mount must keep the attachment alive');

  secondMount.release();
  scheduler.flush();
  await flushPromiseJobs();
  assert.equal(detachCalls.length, 1);
}

async function testActualCloseDetachesExactlyOnce() {
  const { lifecycle, scheduler, detachCalls } = createHarness();
  const attachment = lifecycle.acquire('session-1');
  attachment.release();
  attachment.release();
  scheduler.flush();
  await flushPromiseJobs();
  assert.equal(detachCalls.length, 1, 'closing a tab must detach exactly once');
}

async function testCloseWaitsForPendingAttach() {
  const pendingAttach = createDeferred<void>();
  const { lifecycle, scheduler, detachCalls } = createHarness(() => pendingAttach.promise);
  const attachment = lifecycle.acquire('session-1');
  attachment.release();
  scheduler.flush();
  await flushPromiseJobs();
  assert.equal(detachCalls.length, 0, 'detach must wait until attach settles');

  pendingAttach.resolve();
  await attachment.attached;
  await flushPromiseJobs();
  assert.equal(detachCalls.length, 1);
}

async function testReopenAfterDetachCreatesFreshConsumer() {
  const { lifecycle, scheduler, attachCalls, detachCalls } = createHarness();
  const firstAttachment = lifecycle.acquire('session-1');
  firstAttachment.release();
  scheduler.flush();
  await flushPromiseJobs();

  const reopenedAttachment = lifecycle.acquire('session-1');
  assert.equal(attachCalls.length, 2);
  assert.notEqual(attachCalls[0].consumerId, attachCalls[1].consumerId);
  assert.deepEqual(detachCalls, [attachCalls[0]]);
  reopenedAttachment.release();
  scheduler.flush();
  await flushPromiseJobs();
  assert.deepEqual(detachCalls, attachCalls);
}

async function testAttachFailureStillAllowsCleanup() {
  const attachError = new Error('attach failed');
  const { lifecycle, scheduler, detachCalls } = createHarness(() => Promise.reject(attachError));
  const attachment = lifecycle.acquire('session-1');
  await assert.rejects(attachment.attached, attachError);
  attachment.release();
  scheduler.flush();
  await flushPromiseJobs();
  assert.equal(detachCalls.length, 1);
}

async function main() {
  await testMigrationCancelsDetach();
  await testOverlappingMountsKeepAttachmentAlive();
  await testActualCloseDetachesExactlyOnce();
  await testCloseWaitsForPendingAttach();
  await testReopenAfterDetachCreatesFreshConsumer();
  await testAttachFailureStillAllowsCleanup();
  console.log('Terminal attachment lifecycle tests passed');
}

void main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
