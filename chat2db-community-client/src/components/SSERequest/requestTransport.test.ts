import assert from 'node:assert/strict';
import type { SSERequestCallbacks } from './index';
import { createClientRequestHandle } from './clientRequestHandle';
import { JcefEventBus } from '@/jcef/eventBus';

interface CallbackState {
  errors: Error[];
  stops: number;
  successes: unknown[][];
  updates: unknown[];
}

const createCallbacks = () => {
  const state: CallbackState = {
    errors: [],
    stops: 0,
    successes: [],
    updates: [],
  };
  const callbacks: SSERequestCallbacks<any> = {
    onSuccess: (chunks) => state.successes.push(chunks),
    onError: (error) => state.errors.push(error),
    onUpdate: (chunk) => state.updates.push(chunk),
    onStop: () => {
      state.stops += 1;
    },
  };
  return { callbacks, state };
};

const createHandle = (eventName: string, callbacks: SSERequestCallbacks<any>) =>
  createClientRequestHandle({
    callbacks,
    eventBus: JcefEventBus,
    eventName,
    handleErrorPayload: () => false,
  });

const testRequestListenersAreIndependent = async () => {
  const first = createCallbacks();
  const second = createCallbacks();
  const firstEvent = 'ai_sse_message_first-request';
  const secondEvent = 'ai_sse_message_second-request';
  const firstHandle = createHandle(firstEvent, first.callbacks);
  const secondHandle = createHandle(secondEvent, second.callbacks);

  JcefEventBus.publish(firstEvent, { data: 'first' });
  JcefEventBus.publish(secondEvent, { data: 'second' });
  assert.deepEqual(first.state.updates, [{ data: 'first' }]);
  assert.deepEqual(second.state.updates, [{ data: 'second' }]);

  firstHandle.stop();
  await firstHandle.done;
  assert.equal(first.state.stops, 1);

  JcefEventBus.publish(firstEvent, { data: 'late-first' });
  JcefEventBus.publish(secondEvent, { data: 'still-second' });
  assert.deepEqual(first.state.updates, [{ data: 'first' }]);
  assert.deepEqual(second.state.updates, [{ data: 'second' }, { data: 'still-second' }]);

  JcefEventBus.publish(secondEvent, null);
  await secondHandle;
  assert.equal(second.state.successes.length, 1);
  assert.equal(second.state.errors.length, 0);
};

const testErrorTerminalRejectsAndCleansUp = async () => {
  const request = createCallbacks();
  const eventName = 'ai_sse_message_failed-request';
  const handle = createHandle(eventName, request.callbacks);
  const errorTerminal = {
    event: 'error',
    data: JSON.stringify({
      type: 'error',
      messageType: 'error',
      content: 'AI stream failed',
    }),
  };

  JcefEventBus.publish(eventName, errorTerminal);

  await assert.rejects(handle.done, /AI stream failed/);
  assert.deepEqual(request.state.updates, [errorTerminal]);
  assert.equal(request.state.errors.length, 1);
  assert.equal(request.state.successes.length, 0);

  JcefEventBus.publish(eventName, null);
  assert.equal(request.state.successes.length, 0);
};

const testDonePayloadUpdatesBeforeSuccess = async () => {
  const request = createCallbacks();
  const eventName = 'ai_sse_message_done-request';
  const handle = createHandle(eventName, request.callbacks);

  JcefEventBus.publish(eventName, {
    event: 'done',
    data: JSON.stringify({ type: 'done', content: '[DONE]' }),
  });

  await handle.done;
  assert.deepEqual(request.state.updates, [
    {
      event: 'done',
      data: { type: 'done', content: '[DONE]' },
    },
  ]);
  assert.equal(request.state.successes.length, 1);
};

const main = async () => {
  await testRequestListenersAreIndependent();
  await testErrorTerminalRejectsAndCleansUp();
  await testDonePayloadUpdatesBeforeSuccess();
};

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
