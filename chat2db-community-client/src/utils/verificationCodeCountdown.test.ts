import assert from 'node:assert/strict';
import {
  createVerificationCodeCountdownLifecycle,
  type CountdownScheduler,
} from './verificationCodeCountdown';

type TimerHandle = ReturnType<typeof setInterval>;

class ManualScheduler implements CountdownScheduler {
  private nextId = 0;
  private readonly activeCallbacks = new Map<TimerHandle, () => void>();
  private readonly allCallbacks = new Map<TimerHandle, () => void>();
  setCount = 0;
  clearCount = 0;

  setInterval(callback: () => void): TimerHandle {
    const handle = { id: ++this.nextId } as unknown as TimerHandle;
    this.setCount += 1;
    this.activeCallbacks.set(handle, callback);
    this.allCallbacks.set(handle, callback);
    return handle;
  }

  clearInterval(handle: TimerHandle) {
    this.clearCount += 1;
    this.activeCallbacks.delete(handle);
  }

  get activeHandles() {
    return [...this.activeCallbacks.keys()];
  }

  tick(handle: TimerHandle) {
    const callback = this.activeCallbacks.get(handle);
    if (!callback) {
      throw new Error('expected timer to be active');
    }
    callback();
  }

  fireQueued(handle: TimerHandle) {
    const callback = this.allCallbacks.get(handle);
    if (!callback) {
      throw new Error('expected timer callback to exist');
    }
    callback();
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function testDelayedRequestDoesNothingAfterDispose() {
  const scheduler = new ManualScheduler();
  const countdownUpdates: Array<number | null> = [];
  let settledUpdates = 0;
  const lifecycle = createVerificationCodeCountdownLifecycle((value) => countdownUpdates.push(value), scheduler);
  const sendEmailSMS = deferred<void>();
  const request = lifecycle.beginRequest();
  const completion = request.track(
    sendEmailSMS.promise,
    () => request.startCountdown(),
    () => {
      settledUpdates += 1;
    },
  );

  lifecycle.dispose();
  sendEmailSMS.resolve();
  await completion;

  assert.deepEqual(countdownUpdates, []);
  assert.equal(settledUpdates, 0);
  assert.equal(scheduler.setCount, 0);
  assert.equal(scheduler.activeHandles.length, 0);
}

async function testNormalCountdownCompletes() {
  const scheduler = new ManualScheduler();
  const countdownUpdates: Array<number | null> = [];
  let settledUpdates = 0;
  const lifecycle = createVerificationCodeCountdownLifecycle((value) => countdownUpdates.push(value), scheduler);
  const request = lifecycle.beginRequest();

  await request.track(
    Promise.resolve(),
    () => request.startCountdown(2),
    () => {
      settledUpdates += 1;
    },
  );

  assert.deepEqual(countdownUpdates, [2]);
  assert.equal(settledUpdates, 1);
  const [timer] = scheduler.activeHandles;
  scheduler.tick(timer);
  scheduler.tick(timer);
  assert.deepEqual(countdownUpdates, [2, 1, 0, null]);
  assert.equal(scheduler.activeHandles.length, 0);
}

function testDisposeClearsRunningCountdown() {
  const scheduler = new ManualScheduler();
  const countdownUpdates: Array<number | null> = [];
  const lifecycle = createVerificationCodeCountdownLifecycle((value) => countdownUpdates.push(value), scheduler);
  const request = lifecycle.beginRequest();
  request.startCountdown(2);
  const [timer] = scheduler.activeHandles;

  lifecycle.dispose();
  assert.equal(scheduler.activeHandles.length, 0);
  scheduler.fireQueued(timer);
  assert.deepEqual(countdownUpdates, [2]);
}

function testOldRequestAndTimerCannotAffectLatestCountdown() {
  const scheduler = new ManualScheduler();
  const countdownUpdates: Array<number | null> = [];
  const lifecycle = createVerificationCodeCountdownLifecycle((value) => countdownUpdates.push(value), scheduler);
  const firstRequest = lifecycle.beginRequest();
  firstRequest.startCountdown(2);
  const [firstTimer] = scheduler.activeHandles;

  const secondRequest = lifecycle.beginRequest();
  secondRequest.startCountdown(3);
  const [secondTimer] = scheduler.activeHandles;
  scheduler.fireQueued(firstTimer);

  assert.equal(firstRequest.isCurrent(), false);
  assert.equal(secondRequest.isCurrent(), true);
  assert.deepEqual(scheduler.activeHandles, [secondTimer]);
  assert.deepEqual(countdownUpdates, [2, 3]);
}

async function testOldRequestResolutionCannotReplaceLatestCountdown() {
  const scheduler = new ManualScheduler();
  const countdownUpdates: Array<number | null> = [];
  const lifecycle = createVerificationCodeCountdownLifecycle((value) => countdownUpdates.push(value), scheduler);
  const firstSend = deferred<void>();
  const firstRequest = lifecycle.beginRequest();
  const firstCompletion = firstRequest.track(
    firstSend.promise,
    () => firstRequest.startCountdown(2),
    () => undefined,
  );
  const secondSend = deferred<void>();
  const secondRequest = lifecycle.beginRequest();
  const secondCompletion = secondRequest.track(
    secondSend.promise,
    () => secondRequest.startCountdown(3),
    () => undefined,
  );

  secondSend.resolve();
  await secondCompletion;
  const [latestTimer] = scheduler.activeHandles;
  firstSend.resolve();
  await firstCompletion;

  assert.deepEqual(countdownUpdates, [3]);
  assert.deepEqual(scheduler.activeHandles, [latestTimer]);
}

async function run() {
  await testDelayedRequestDoesNothingAfterDispose();
  await testNormalCountdownCompletes();
  testDisposeClearsRunningCountdown();
  testOldRequestAndTimerCannotAffectLatestCountdown();
  await testOldRequestResolutionCannotReplaceLatestCountdown();
  console.log('Verification-code countdown lifecycle tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
