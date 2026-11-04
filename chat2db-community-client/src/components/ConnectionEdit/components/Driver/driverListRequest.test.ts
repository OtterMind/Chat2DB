import assert from 'node:assert/strict';
import { DriverListRequestOwner, type DriverListRequestScope } from './driverListRequest';

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolvePromise: ((value: T) => void) | undefined;
  let rejectPromise: ((error: unknown) => void) | undefined;
  const promise = new Promise<T>((resolve, reject) => {
    resolvePromise = resolve;
    rejectPromise = reject;
  });
  return {
    promise,
    resolve: (value) => resolvePromise?.(value),
    reject: (error) => rejectPromise?.(error),
  };
}

function activate(owner: DriverListRequestOwner): DriverListRequestScope {
  const scope = owner.createScope();
  owner.activate(scope);
  return scope;
}

async function testLatestSuccessOwnsDriverStateAndOnChange() {
  const owner = new DriverListRequestOwner();
  const scope = activate(owner);
  const firstRequest = deferred<string>();
  const latestRequest = deferred<string>();
  let currentDriver: string | null = null;
  const changes: string[] = [];
  const commit = (driver: string) => {
    currentDriver = driver;
    changes.push(driver);
  };

  const firstLoad = owner.run(scope, () => firstRequest.promise, { onSuccess: commit });
  const latestLoad = owner.run(scope, () => latestRequest.promise, { onSuccess: commit });
  latestRequest.resolve('driver-b');
  await latestLoad;
  firstRequest.resolve('driver-a');
  await firstLoad;

  assert.equal(currentDriver, 'driver-b');
  assert.deepEqual(changes, ['driver-b'], 'a stale success must not invoke the form onChange path');
}

async function testStaleFailureDoesNotReachCurrentErrorHandler() {
  const owner = new DriverListRequestOwner();
  const scope = activate(owner);
  const firstRequest = deferred<string>();
  const latestRequest = deferred<string>();
  const errors: unknown[] = [];
  const firstLoad = owner.run(scope, () => firstRequest.promise, {
    onSuccess: () => undefined,
    onError: (error) => errors.push(error),
  });
  const latestLoad = owner.run(scope, () => latestRequest.promise, { onSuccess: () => undefined });

  firstRequest.reject(new Error('stale failure'));
  await firstLoad;
  assert.deepEqual(errors, []);
  latestRequest.resolve('driver-b');
  await latestLoad;
}

async function testContextSwitchSuppressesLateCallbacks() {
  const owner = new DriverListRequestOwner();
  const firstScope = activate(owner);
  const request = deferred<string>();
  const calls: string[] = [];
  const load = owner.run(firstScope, () => request.promise, {
    onSuccess: () => calls.push('success'),
    onError: () => calls.push('error'),
  });
  owner.dispose(firstScope);
  activate(owner);
  request.resolve('driver-a');
  await load;
  assert.deepEqual(calls, []);
}

async function testLatestFailureReachesItsErrorHandlerOnce() {
  const owner = new DriverListRequestOwner();
  const scope = activate(owner);
  const failure = new Error('latest failure');
  const errors: unknown[] = [];
  await owner.run(scope, () => Promise.reject(failure), {
    onSuccess: () => undefined,
    onError: (error) => errors.push(error),
  });
  assert.deepEqual(errors, [failure]);
}

async function testOldMutationCannotStartARefreshAfterContextSwitch() {
  const owner = new DriverListRequestOwner();
  const firstScope = activate(owner);
  const mutation = deferred<void>();
  let currentDriver: string | null = null;
  let oldRefreshCalls = 0;
  const oldMutationRefresh = mutation.promise.then(() =>
    owner.run(
      firstScope,
      () => {
        oldRefreshCalls += 1;
        return Promise.resolve('driver-a');
      },
      {
        onSuccess: (driver) => {
          currentDriver = driver;
        },
      },
    ),
  );

  owner.dispose(firstScope);
  const latestScope = activate(owner);
  await owner.run(latestScope, () => Promise.resolve('driver-b'), {
    onSuccess: (driver) => {
      currentDriver = driver;
    },
  });
  mutation.resolve();
  await oldMutationRefresh;

  assert.equal(oldRefreshCalls, 0, 'a mutation from the old datasource must not start another list request');
  assert.equal(currentDriver, 'driver-b');
}

async function testDisposedOwnerRejectsLateStarts() {
  const owner = new DriverListRequestOwner();
  const scope = activate(owner);
  let requestCalls = 0;
  const callbacks: string[] = [];
  owner.dispose(scope);
  await owner.run(
    scope,
    () => {
      requestCalls += 1;
      return Promise.resolve('driver-a');
    },
    { onSuccess: () => callbacks.push('success'), onError: () => callbacks.push('error') },
  );

  assert.equal(requestCalls, 0, 'an unmounted owner must not start a request from an old closure');
  assert.deepEqual(callbacks, []);
}

async function testOldMutationCannotReviveAcrossAnAbaContextSwitch() {
  const owner = new DriverListRequestOwner();
  const firstMysqlScope = activate(owner);
  const mutation = deferred<void>();
  let currentDriver: string | null = null;
  let oldRefreshCalls = 0;
  const oldMutationRefresh = mutation.promise.then(() =>
    owner.run(
      firstMysqlScope,
      () => {
        oldRefreshCalls += 1;
        return Promise.resolve('driver-a');
      },
      {
        onSuccess: (driver) => {
          currentDriver = driver;
        },
      },
    ),
  );

  owner.dispose(firstMysqlScope);
  const postgresScope = activate(owner);
  await owner.run(postgresScope, () => Promise.resolve('driver-b'), {
    onSuccess: (driver) => {
      currentDriver = driver;
    },
  });
  owner.dispose(postgresScope);
  const secondMysqlScope = activate(owner);
  assert.notEqual(firstMysqlScope, secondMysqlScope, 'separate mysql activations must use unique scopes');
  await owner.run(secondMysqlScope, () => Promise.resolve('driver-c'), {
    onSuccess: (driver) => {
      currentDriver = driver;
    },
  });

  mutation.resolve();
  await oldMutationRefresh;
  assert.equal(oldRefreshCalls, 0, 'an old mysql scope must not match a later mysql scope');
  assert.equal(currentDriver, 'driver-c');
}

async function testSameTypeCustomDriverChangeInvalidatesOldList() {
  const owner = new DriverListRequestOwner();
  const emptyDriverScope = activate(owner);
  const oldList = deferred<string>();
  let selectedDriver = '';
  const load = owner.run(emptyDriverScope, () => oldList.promise, {
    onSuccess: (driver) => {
      selectedDriver = driver;
    },
  });

  owner.dispose(emptyDriverScope);
  const customDriverScope = activate(owner);
  assert.notEqual(emptyDriverScope, customDriverScope, 'a custom-driver render must receive a new scope');
  selectedDriver = 'custom-driver';
  oldList.resolve('default-driver');
  await load;
  assert.equal(selectedDriver, 'custom-driver', 'a list from the pre-custom scope must not overwrite the selection');
}

void Promise.all([
  testLatestSuccessOwnsDriverStateAndOnChange(),
  testStaleFailureDoesNotReachCurrentErrorHandler(),
  testContextSwitchSuppressesLateCallbacks(),
  testLatestFailureReachesItsErrorHandlerOnce(),
  testOldMutationCannotStartARefreshAfterContextSwitch(),
  testDisposedOwnerRejectsLateStarts(),
  testOldMutationCannotReviveAcrossAnAbaContextSwitch(),
  testSameTypeCustomDriverChangeInvalidatesOldList(),
])
  .then(() => console.log('Driver list request ownership tests passed'))
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
