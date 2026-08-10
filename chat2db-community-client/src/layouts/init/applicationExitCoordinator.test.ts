import assert from 'node:assert/strict';
import { ApplicationExitConfirmation, coordinateApplicationExit } from './applicationExitCoordinator';

async function testExitWithoutActiveTasks() {
  const calls: string[] = [];
  await coordinateApplicationExit({
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
  const prepareError = new Error('prepare failed');
  await assert.rejects(
    coordinateApplicationExit({
      getActiveTaskCount: async () => 0,
      prepareUserExit: async () => {
        throw prepareError;
      },
      abortUserExit: async () => undefined,
      confirmCloseWindow: async () => {
        confirmCalled = true;
        return true;
      },
      requestConfirmation: () => undefined,
      onCancel: () => undefined,
    }),
    prepareError,
  );
  assert.equal(confirmCalled, false);
}

async function testMissingNativeExitRequestFailsClosed() {
  const calls: string[] = [];
  await assert.rejects(
    coordinateApplicationExit({
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
      requestConfirmation: () => undefined,
      onCancel: () => undefined,
    }),
    /No pending application exit request/,
  );
  assert.deepEqual(calls, ['prepare', 'confirm', 'abort']);
}

void Promise.all([
  testExitWithoutActiveTasks(),
  testExitWithActiveTasks(),
  testPrepareFailureKeepsNativeWindowOpen(),
  testMissingNativeExitRequestFailsClosed(),
]).then(() => {
  console.log('Application exit coordinator tests passed');
});
