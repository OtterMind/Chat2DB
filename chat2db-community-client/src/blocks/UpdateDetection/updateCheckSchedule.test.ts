import assert from 'node:assert/strict';
import {
  UPDATE_CHECK_INTERVAL_MINUTES,
  isCheckDue,
  nextCheckDueAt,
  nextNotifiedVersion,
  shouldNotifyVersion,
  updateCheckDelayMs,
} from './updateCheckSchedule';

const minute = 60 * 1000;

assert.deepEqual(UPDATE_CHECK_INTERVAL_MINUTES, [30, 60, 120, 240, 360]);
assert.equal(updateCheckDelayMs(0), 30 * minute);
assert.equal(updateCheckDelayMs(1), 60 * minute);
assert.equal(updateCheckDelayMs(4), 360 * minute);
assert.equal(updateCheckDelayMs(5), 30 * minute, 'schedule repeats after the last interval');
assert.equal(updateCheckDelayMs(9), 360 * minute, 'second cycle keeps the same order');
assert.equal(updateCheckDelayMs(11), 60 * minute);

assert.equal(shouldNotifyVersion('5.3.8', ''), true);
assert.equal(shouldNotifyVersion('5.3.8', '5.3.8'), false, 'same version is not announced twice');
assert.equal(shouldNotifyVersion('5.3.9', '5.3.8'), true, 'a newer version is announced again');
assert.equal(shouldNotifyVersion('', ''), false, 'missing version is not announced');
assert.equal(shouldNotifyVersion(undefined, ''), false);

assert.equal(nextNotifiedVersion(true, '5.3.8', ''), '5.3.8', 'first sighting is announced');
assert.equal(nextNotifiedVersion(true, '5.3.8', '5.3.8'), '5.3.8', 'repeat check keeps the marker');
assert.equal(nextNotifiedVersion(true, '5.3.9', '5.3.8'), '5.3.9', 'a newer version replaces the marker');
assert.equal(nextNotifiedVersion(false, '5.3.8', ''), '', 'reminders disabled: nothing announced');
assert.equal(nextNotifiedVersion(false, '5.3.8', '5.3.7'), '5.3.7', 'reminders disabled: marker untouched');
assert.equal(nextNotifiedVersion(true, undefined, '5.3.7'), '5.3.7', 'missing version is not announced');

assert.equal(nextCheckDueAt(1000, 0), 1000 + 30 * 60 * 1000, 'first round is due after 30 minutes');
assert.equal(nextCheckDueAt(1000, 5), 1000 + 30 * 60 * 1000, 'the list repeats after the last round');

assert.equal(isCheckDue(2000, 1000), true, 'a passed deadline is due');
assert.equal(isCheckDue(1000, 1000), true, 'the exact deadline is due');
assert.equal(isCheckDue(500, 1000), false, 'a future deadline is not due');
assert.equal(isCheckDue(2000, 0), false, 'an unarmed schedule is never due');

console.log('Update check schedule tests passed');
