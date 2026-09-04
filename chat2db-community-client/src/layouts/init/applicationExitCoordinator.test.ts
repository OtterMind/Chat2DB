import assert from 'node:assert/strict';
import { ApplicationExitConfirmation, coordinateApplicationExit } from './applicationExitCoordinator';

async function testExitWithoutActiveTasks() {
  const calls: string[] = [];
  await coordinateApplicationExit({
    confirmDirtyEditors: async () => true,
    shouldManageTasks: () => true,
    getActiveTaskCount: async () => 0,
    prepareUserExit: async () => {
      calls.push('prepare');
    },
    abortUserExit: async () => {
      calls.push('abort');
    },
    confirmCloseWindow: async () => {
      calls.push('confirm');
      return true;
    },
    cancelApplicationExit: async () => {
      calls.push('cancel-native');
      return true;
    },
    requestConfirmation: () => {
      calls.push('prompt');
    },
    onCancel: () => {
      calls.push('cancel');
    },
  });
  assert.deepEqual(calls, ['prepare', 'confirm']);
}

async function testExitWithActiveTasks() {
  const calls: string[] = [];
  let confirmation: ApplicationExitConfirmation | undefined;
  await coordinateApplicationExit({
    confirmDirtyEditors: async () => true,
    shouldManageTasks: () => true,
    getActiveTaskCount: async () => 2,
    prepareUserExit: async () => {
      calls.push('prepare');
    },
    abortUserExit: async () => {
      calls.push('abort');
    },
    confirmCloseWindow: async () => {
      calls.push('confirm');
      return true;
    },
    cancelApplicationExit: async () => {
      calls.push('cancel-native');
      return true;
    },
    requestConfirmation: (request) => {
      calls.push('prompt');
      confirmation = request;
    },
    onCancel: () => {
      calls.push('cancel');
    },
  });

  assert.deepEqual(calls, ['prompt']);
  assert.equal(confirmation?.activeTaskCount, 2);
  confirmation?.onCancel();
  assert.deepEqual(calls, ['prompt', 'cancel']);
  await confirmation?.onConfirm();
  assert.deepEqual(calls, ['prompt', 'cancel', 'prepare', 'confirm']);
}

async function testPrepareFailureKeepsNativeWindowOpen() {
  let confirmCalled = false;
  let finalizeCalled = false;
  const prepareError = new Error('prepare failed');
  await assert.rejects(
    coordinateApplicationExit({
      confirmDirtyEditors: async () => true,
      finalizeBeforeClose: async () => {
        finalizeCalled = true;
        return true;
      },
      shouldManageTasks: () => true,
      getActiveTaskCount: async () => 0,
      prepareUserExit: async () => {
        throw prepareError;
      },
      abortUserExit: async () => undefined,
      confirmCloseWindow: async () => {
        confirmCalled = true;
        return true;
      },
      cancelApplicationExit: async () => true,
      requestConfirmation: () => undefined,
      onCancel: () => undefined,
    }),
    prepareError,
  );
  assert.equal(confirmCalled, false);
  assert.equal(finalizeCalled, false);
}

async function testMissingNativeExitRequestFailsClosed() {
  const calls: string[] = [];
  await assert.rejects(
    coordinateApplicationExit({
      confirmDirtyEditors: async () => true,
      shouldManageTasks: () => true,
      getActiveTaskCount: async () => 0,
      prepareUserExit: async () => {
        calls.push('prepare');
      },
      abortUserExit: async () => {
        calls.push('abort');
      },
      confirmCloseWindow: async () => {
        calls.push('confirm');
        return false;
      },
      cancelApplicationExit: async () => {
        calls.push('cancel-native');
        return true;
      },
      requestConfirmation: () => undefined,
      onCancel: () => undefined,
    }),
    /No pending application exit request/,
  );
  assert.deepEqual(calls, ['prepare', 'confirm', 'abort', 'cancel-native']);
}

async function testDirtyEditorCancellationStopsBeforeTaskQueries() {
  const calls: string[] = [];
  await coordinateApplicationExit({
    confirmDirtyEditors: async () => false,
    shouldManageTasks: () => true,
    getActiveTaskCount: async () => {
      calls.push('active-count');
      return 1;
    },
    prepareUserExit: async () => {
      calls.push('prepare');
    },
    abortUserExit: async () => {
      calls.push('abort');
    },
    confirmCloseWindow: async () => {
      calls.push('confirm');
      return true;
    },
    cancelApplicationExit: async () => true,
    requestConfirmation: () => {
      calls.push('prompt');
    },
    onCancel: () => {
      calls.push('cancel');
    },
  });
  assert.deepEqual(calls, ['cancel']);
}

async function testUnavailableTaskCapabilityConfirmsWithoutTaskApis() {
  const calls: string[] = [];
  await coordinateApplicationExit({
    confirmDirtyEditors: async () => true,
    shouldManageTasks: () => false,
    getActiveTaskCount: async () => {
      calls.push('active-count');
      return 1;
    },
    prepareUserExit: async () => {
      calls.push('prepare');
    },
    abortUserExit: async () => {
      calls.push('abort');
    },
    confirmCloseWindow: async () => {
      calls.push('confirm');
      return true;
    },
    cancelApplicationExit: async () => true,
    requestConfirmation: () => {
      calls.push('prompt');
    },
    onCancel: () => {
      calls.push('cancel');
    },
  });
  assert.deepEqual(calls, ['confirm']);
}

async function testTransactionFinalizationRunsAfterTaskConfirmation() {
  const calls: string[] = [];
  let confirmation: ApplicationExitConfirmation | undefined;
  await coordinateApplicationExit({
    confirmDirtyEditors: async () => {
      calls.push('dirty');
      return true;
    },
    finalizeBeforeClose: async () => {
      calls.push('finalize');
      return true;
    },
    shouldManageTasks: () => true,
    getActiveTaskCount: async () => {
      calls.push('active-count');
      return 1;
    },
    prepareUserExit: async () => {
      calls.push('prepare');
    },
    abortUserExit: async () => undefined,
    confirmCloseWindow: async () => {
      calls.push('confirm');
      return true;
    },
    cancelApplicationExit: async () => true,
    requestConfirmation: (request) => {
      calls.push('prompt');
      confirmation = request;
    },
    onCancel: () => {
      calls.push('cancel');
    },
  });

  assert.deepEqual(calls, ['dirty', 'active-count', 'prompt']);
  await confirmation?.onConfirm();
  assert.deepEqual(calls, ['dirty', 'active-count', 'prompt', 'prepare', 'finalize', 'confirm']);
}

async function testTransactionFinalizationCancellationKeepsWindowOpen() {
  const calls: string[] = [];
  await coordinateApplicationExit({
    confirmDirtyEditors: async () => true,
    finalizeBeforeClose: async () => {
      calls.push('finalize');
      return false;
    },
    shouldManageTasks: () => false,
    getActiveTaskCount: async () => 0,
    prepareUserExit: async () => {
      calls.push('prepare');
    },
    abortUserExit: async () => undefined,
    confirmCloseWindow: async () => {
      calls.push('confirm');
      return true;
    },
    cancelApplicationExit: async () => true,
    requestConfirmation: () => undefined,
    onCancel: () => {
      calls.push('cancel');
    },
  });

  assert.deepEqual(calls, ['finalize', 'cancel']);
}

async function testPreparedTaskExitIsAbortedWhenTransactionFinalizationIsCancelled() {
  const calls: string[] = [];
  await coordinateApplicationExit({
    confirmDirtyEditors: async () => true,
    finalizeBeforeClose: async () => {
      calls.push('finalize');
      return false;
    },
    shouldManageTasks: () => true,
    getActiveTaskCount: async () => 0,
    prepareUserExit: async () => {
      calls.push('prepare');
    },
    abortUserExit: async () => {
      calls.push('abort');
    },
    confirmCloseWindow: async () => {
      calls.push('confirm');
      return true;
    },
    cancelApplicationExit: async () => true,
    requestConfirmation: () => undefined,
    onCancel: () => {
      calls.push('cancel');
    },
  });

  assert.deepEqual(calls, ['prepare', 'finalize', 'abort', 'cancel']);
}

void Promise.all([
  testExitWithoutActiveTasks(),
  testExitWithActiveTasks(),
  testPrepareFailureKeepsNativeWindowOpen(),
  testMissingNativeExitRequestFailsClosed(),
  testDirtyEditorCancellationStopsBeforeTaskQueries(),
  testUnavailableTaskCapabilityConfirmsWithoutTaskApis(),
  testTransactionFinalizationRunsAfterTaskConfirmation(),
  testTransactionFinalizationCancellationKeepsWindowOpen(),
  testPreparedTaskExitIsAbortedWhenTransactionFinalizationIsCancelled(),
]).then(() => {
  console.log('Application exit coordinator tests passed');
});
