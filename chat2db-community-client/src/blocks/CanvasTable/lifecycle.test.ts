import assert from 'node:assert/strict';
import { releaseTableInstance } from './lifecycle';

let releaseCount = 0;
let notificationCount = 0;
const lifecycleEvents: string[] = [];
const instance = {
  completeEditCell: () => {
    lifecycleEvents.push('complete-edit');
  },
  getScrollLeft: () => 17,
  getScrollTop: () => 29,
  release: () => {
    lifecycleEvents.push('release');
    releaseCount += 1;
  },
};
const instanceRef = { current: instance };

assert.deepEqual(
  releaseTableInstance(instanceRef, () => {
    lifecycleEvents.push('notify');
    notificationCount += 1;
  }),
  { scrollLeft: 17, scrollTop: 29 },
);
assert.equal(instanceRef.current, null);
assert.equal(releaseCount, 1);
assert.equal(notificationCount, 1);
assert.deepEqual(lifecycleEvents, ['complete-edit', 'release', 'notify']);

assert.equal(
  releaseTableInstance(instanceRef, () => {
    notificationCount += 1;
  }),
  null,
);
assert.equal(releaseCount, 1, 'repeated suspension must not release an instance twice');
assert.equal(notificationCount, 1, 'repeated suspension must not notify twice');

let notifiedAfterFailure = false;
const failingRef = {
  current: {
    release: () => {
      throw new Error('release failed');
    },
  },
};
assert.throws(() =>
  releaseTableInstance(failingRef, () => {
    notifiedAfterFailure = true;
  }),
);
assert.equal(failingRef.current, null);
assert.equal(notifiedAfterFailure, true, 'the owner must clear stale references even when release fails');

let releasedAfterPreflightFailure = false;
let notifiedAfterPreflightFailure = false;
const preflightFailureRef = {
  current: {
    completeEditCell: () => {
      throw new Error('complete edit failed');
    },
    release: () => {
      releasedAfterPreflightFailure = true;
    },
  },
};
assert.throws(() =>
  releaseTableInstance(preflightFailureRef, () => {
    notifiedAfterPreflightFailure = true;
  }),
);
assert.equal(preflightFailureRef.current, null);
assert.equal(releasedAfterPreflightFailure, true, 'preflight failure must not skip releasing the table');
assert.equal(notifiedAfterPreflightFailure, true, 'preflight failure must still clear the owner reference');

let releasedAfterViewportFailure = false;
let notifiedAfterViewportFailure = false;
const viewportFailureRef = {
  current: {
    getScrollLeft: () => {
      throw new Error('scroll read failed');
    },
    release: () => {
      releasedAfterViewportFailure = true;
    },
  },
};
assert.throws(() =>
  releaseTableInstance(viewportFailureRef, () => {
    notifiedAfterViewportFailure = true;
  }),
);
assert.equal(viewportFailureRef.current, null);
assert.equal(releasedAfterViewportFailure, true, 'viewport read failure must not skip releasing the table');
assert.equal(notifiedAfterViewportFailure, true, 'viewport read failure must still clear the owner reference');

console.log('CanvasTable lifecycle tests passed');
