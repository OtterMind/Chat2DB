import assert from 'node:assert/strict';
import { getTabWheelScrollAmount } from './wheelScroll';

assert.equal(
  getTabWheelScrollAmount(40, -1),
  null,
  'cross-axis noise must not reverse a dominant rightward trackpad gesture',
);
assert.equal(
  getTabWheelScrollAmount(-40, 1),
  null,
  'cross-axis noise must not reverse a dominant leftward trackpad gesture',
);
assert.equal(getTabWheelScrollAmount(20, 0), null, 'pure horizontal gestures use native scrolling');
assert.equal(getTabWheelScrollAmount(5, 5), null, 'ambiguous diagonal gestures use native scrolling');
assert.equal(getTabWheelScrollAmount(0, 8), 8, 'small mouse-wheel deltas retain their distance');
assert.equal(getTabWheelScrollAmount(2, 20), 10, 'medium vertical deltas retain the existing scaling');
assert.equal(getTabWheelScrollAmount(0, -100), -20, 'large vertical deltas retain their direction and scaling');

console.log('Tab wheel scroll decision tests passed');
