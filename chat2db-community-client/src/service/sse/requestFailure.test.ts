import assert from 'node:assert/strict';
import { JcefEventBus } from '@/jcef/eventBus';
import { createClientRequestHandle } from '@/components/SSERequest/clientRequestHandle';
import type { SSERequestCallbacks } from '@/components/SSERequest';
import {
  buildStreamErrorOutput,
  dispatchDesktopStreamRequest,
  describeRequestFailure,
  extractEnvelopeErrorMessage,
  streamEventName,
} from './requestFailure';

const testExtractEnvelopeErrorMessage = () => {
  const errorEnvelope = JSON.stringify({
    actionType: 'error',
    uuid: 'req-1',
    message: { success: false, errorCode: 'model.invalid', errorMessage: 'AI model is not available' },
  });
  assert.equal(extractEnvelopeErrorMessage(errorEnvelope), 'AI model is not available');

  assert.equal(
    extractEnvelopeErrorMessage({ actionType: 'error', message: { success: false } }),
    'AI stream request failed',
  );

  const failedActionResultOnly = { actionType: 'execute', message: { success: false, errorMessage: 'boom' } };
  assert.equal(extractEnvelopeErrorMessage(failedActionResultOnly), 'boom');

  assert.equal(extractEnvelopeErrorMessage(JSON.stringify({ actionType: 'execute', uuid: 'req-1' })), undefined);
  assert.equal(extractEnvelopeErrorMessage({ actionType: 'execute', message: { success: true } }), undefined);
  assert.equal(extractEnvelopeErrorMessage('not json'), undefined);
  assert.equal(extractEnvelopeErrorMessage(undefined), undefined);
  assert.equal(extractEnvelopeErrorMessage(42), undefined);
};

const testDescribeRequestFailure = () => {
  assert.equal(describeRequestFailure(500, 'bridge unavailable'), 'bridge unavailable');
  assert.equal(describeRequestFailure(500, '   '), 'AI stream request failed (500)');
  assert.equal(describeRequestFailure(undefined, undefined), 'AI stream request failed');
};

const testPublishedFailureSettlesStreamHandle = async () => {
  const eventName = streamEventName('failed-before-stream');
  const errors: Error[] = [];
  const successes: unknown[][] = [];
  const callbacks: SSERequestCallbacks<any> = {
    onSuccess: (chunks) => successes.push(chunks),
    onError: (error) => errors.push(error),
    onUpdate: () => {},
  };
  const handle = createClientRequestHandle({
    callbacks,
    eventBus: JcefEventBus,
    eventName,
    handleErrorPayload: () => false,
  });

  JcefEventBus.publish(eventName, buildStreamErrorOutput('AI model is not available'));

  await assert.rejects(handle.done, /AI model is not available/);
  assert.equal(errors.length, 1);
  assert.equal(successes.length, 0);

  // The listener must be removed once settled: a late event changes nothing.
  JcefEventBus.publish(eventName, buildStreamErrorOutput('late'));
  assert.equal(errors.length, 1);
};

const createFailureHandle = (requestId: string) => {
  const errors: Error[] = [];
  const updates: unknown[] = [];
  const handle = createClientRequestHandle({
    callbacks: {
      onSuccess: () => assert.fail('failed stream must not succeed'),
      onError: (error) => errors.push(error),
      onUpdate: (output) => updates.push(output),
    },
    eventBus: JcefEventBus,
    eventName: streamEventName(requestId),
    handleErrorPayload: () => false,
  });
  return { handle, errors, updates };
};

const testSynchronousErrorEnvelopeWaitsForListener = async () => {
  const requestId = 'sync-error-envelope';
  dispatchDesktopStreamRequest({
    requestId,
    serializedRequest: '{}',
    javaQuery: ({ onSuccess }) => {
      onSuccess(
        JSON.stringify({
          actionType: 'error',
          message: { success: false, errorMessage: 'model unavailable' },
        }),
      );
    },
    publish: JcefEventBus.publish,
  });
  const { handle, errors, updates } = createFailureHandle(requestId);

  await assert.rejects(handle.done, /model unavailable/);
  assert.equal(errors.length, 1);
  assert.equal(updates.length, 1);
};

const testSynchronousNativeFailureWaitsForListener = async () => {
  const requestId = 'sync-native-failure';
  dispatchDesktopStreamRequest({
    requestId,
    serializedRequest: '{}',
    javaQuery: ({ onFailure }) => onFailure(503, 'bridge unavailable'),
    publish: JcefEventBus.publish,
  });
  const { handle, errors } = createFailureHandle(requestId);

  await assert.rejects(handle.done, /bridge unavailable/);
  assert.equal(errors.length, 1);
  JcefEventBus.publish(streamEventName(requestId), buildStreamErrorOutput('late'));
  assert.equal(errors.length, 1);
};

const testMissingAndThrowingBridgePublishFailures = async () => {
  const published: Array<{ eventName: string; output: ReturnType<typeof buildStreamErrorOutput> }> = [];
  const publish = (eventName: string, output: ReturnType<typeof buildStreamErrorOutput>) => {
    published.push({ eventName, output });
  };

  dispatchDesktopStreamRequest({ requestId: 'missing', serializedRequest: '{}', publish });
  dispatchDesktopStreamRequest({
    requestId: 'throwing',
    serializedRequest: '{}',
    javaQuery: () => {
      throw new Error('bridge threw');
    },
    publish,
  });
  await Promise.resolve();

  assert.deepEqual(
    published.map(({ eventName }) => eventName),
    [streamEventName('missing'), streamEventName('throwing')],
  );
  assert.match(published[0].output.data, /Java Query is not available/);
  assert.match(published[1].output.data, /bridge threw/);
};

const testNormalAcknowledgementDoesNotPublish = async () => {
  const published: unknown[] = [];
  dispatchDesktopStreamRequest({
    requestId: 'normal-ack',
    serializedRequest: '{}',
    javaQuery: ({ onSuccess }) => onSuccess(JSON.stringify({ actionType: 'execute', message: { success: true } })),
    publish: (...args) => published.push(args),
  });
  await Promise.resolve();
  assert.deepEqual(published, []);
};

const main = async () => {
  testExtractEnvelopeErrorMessage();
  testDescribeRequestFailure();
  await testPublishedFailureSettlesStreamHandle();
  await testSynchronousErrorEnvelopeWaitsForListener();
  await testSynchronousNativeFailureWaitsForListener();
  await testMissingAndThrowingBridgePublishFailures();
  await testNormalAcknowledgementDoesNotPublish();
  console.log('sse request failure tests passed');
};

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
