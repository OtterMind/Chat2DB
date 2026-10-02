import assert from 'node:assert/strict';
import {
  cleanupTrackedCommandLineRequest,
  rejectTrackedCommandLineRequest,
  settleCommandLineRequest,
  settleTrackedCommandLineResponse,
  type TrackedCommandLineRequest,
} from './requestSettlement';

interface TestRequest extends TrackedCommandLineRequest {
  label: string;
}

function registry(records: Record<string, TestRequest>, clearedTimers: unknown[]) {
  return {
    get: (requestId: string) => records[requestId],
    remove: (requestId: string) => {
      delete records[requestId];
    },
    clearTimer: (timer: unknown) => clearedTimers.push(timer),
  };
}

function testSilentFailureStillCleansUp() {
  const calls: string[] = [];
  const error = { errorCode: 'api.networkError' };

  settleCommandLineRequest({
    beforeSettle: () => calls.push('intercept'),
    success: false,
    successValue: undefined,
    error,
    suppressErrorReport: true,
    reject: (reason) => calls.push(reason === error ? 'reject' : 'wrong-error'),
    reportError: () => calls.push('report'),
    cleanup: () => calls.push('cleanup'),
  });

  assert.deepEqual(calls, ['intercept', 'reject', 'cleanup']);
}

function testReportedFailureCleansUpAfterReporting() {
  const calls: string[] = [];
  settleCommandLineRequest({
    beforeSettle: () => calls.push('intercept'),
    success: false,
    successValue: undefined,
    error: new Error('failed'),
    suppressErrorReport: false,
    reject: () => calls.push('reject'),
    reportError: () => calls.push('report'),
    cleanup: () => calls.push('cleanup'),
  });
  assert.deepEqual(calls, ['intercept', 'reject', 'report', 'cleanup']);
}

function testSuccessCleansUp() {
  const calls: string[] = [];
  settleCommandLineRequest({
    beforeSettle: () => calls.push('intercept'),
    success: true,
    successValue: 'data',
    error: undefined,
    suppressErrorReport: false,
    resolve: (value) => calls.push(`resolve:${value}`),
    reportError: () => calls.push('report'),
    cleanup: () => calls.push('cleanup'),
  });
  assert.deepEqual(calls, ['intercept', 'resolve:data', 'cleanup']);
}

function testCleanupRunsWhenInterceptionThrows() {
  const calls: string[] = [];
  const failure = new Error('interceptor failed');
  settleCommandLineRequest({
    beforeSettle: () => {
      throw failure;
    },
    success: true,
    successValue: undefined,
    error: undefined,
    suppressErrorReport: false,
    reject: (reason) => calls.push(reason === failure ? 'reject' : 'wrong-error'),
    reportError: () => calls.push('report'),
    cleanup: () => calls.push('cleanup'),
  });
  assert.deepEqual(calls, ['reject', 'cleanup']);
}

function testTrackedSilentResponseClearsTimerAndRecord() {
  const timer = Symbol('timer');
  const calls: string[] = [];
  const clearedTimers: unknown[] = [];
  const records: Record<string, TestRequest> = {
    silent: {
      label: 'silent',
      requestTimeoutTimer: timer,
      requestData: { requestUrl: '/api/test' },
      options: { fullResponse: false },
      reject: (reason) => calls.push(`reject:${String((reason as { errorCode?: string }).errorCode)}`),
    },
  };
  const requestRegistry = registry(records, clearedTimers);
  const response = {
    success: false,
    errorCode: 'api.networkError',
    errorMessage: 'offline',
  };

  assert.equal(
    settleTrackedCommandLineResponse({
      requestId: 'silent',
      message: response,
      registry: requestRegistry,
      beforeSettle: () => calls.push('intercept'),
      suppressErrorReport: (errorCode) => errorCode === 'api.networkError',
      reportError: () => calls.push('report'),
    }),
    true,
  );
  assert.deepEqual(calls, ['intercept', 'reject:api.networkError']);
  assert.deepEqual(clearedTimers, [timer]);
  assert.equal(records.silent, undefined);
  assert.equal(
    settleTrackedCommandLineResponse({
      requestId: 'silent',
      message: response,
      registry: requestRegistry,
      beforeSettle: () => calls.push('late-intercept'),
      suppressErrorReport: () => true,
      reportError: () => calls.push('late-report'),
    }),
    false,
    'duplicate late responses are ignored after the record is removed',
  );
  assert.deepEqual(calls, ['intercept', 'reject:api.networkError']);
}

function testNativeFailureRejectsAndRemovesTrackedRequest() {
  const timer = Symbol('native-timer');
  const failure = new Error('native failure');
  const clearedTimers: unknown[] = [];
  let rejected: unknown;
  const records: Record<string, TestRequest> = {
    native: {
      label: 'native',
      requestTimeoutTimer: timer,
      requestData: {},
      options: {},
      reject: (reason) => {
        rejected = reason;
      },
    },
  };
  const requestRegistry = registry(records, clearedTimers);

  assert.equal(rejectTrackedCommandLineRequest('native', failure, requestRegistry), true);
  assert.equal(rejected, failure);
  assert.deepEqual(clearedTimers, [timer]);
  assert.deepEqual(records, {});
  assert.equal(rejectTrackedCommandLineRequest('native', failure, requestRegistry), false);
}

function testExplicitCleanupRemovesRequestWhenTimerClearThrows() {
  const records: Record<string, TestRequest> = {
    cleanup: {
      label: 'cleanup',
      requestTimeoutTimer: Symbol('timer'),
      requestData: {},
      options: {},
    },
  };
  const clearFailure = new Error('clear failed');
  assert.throws(
    () =>
      cleanupTrackedCommandLineRequest('cleanup', {
        get: (requestId) => records[requestId],
        remove: (requestId) => {
          delete records[requestId];
        },
        clearTimer: () => {
          throw clearFailure;
        },
      }),
    clearFailure,
  );
  assert.deepEqual(records, {});
}

testSilentFailureStillCleansUp();
testReportedFailureCleansUpAfterReporting();
testSuccessCleansUp();
testCleanupRunsWhenInterceptionThrows();
testTrackedSilentResponseClearsTimerAndRecord();
testNativeFailureRejectsAndRemovesTrackedRequest();
testExplicitCleanupRemovesRequestWhenTimerClearThrows();
console.log('Desktop request settlement tests passed');
