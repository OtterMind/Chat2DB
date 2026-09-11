import assert from 'node:assert/strict';
import {
  resolveNextTreeScrollWidth,
  resolveTreeScrollWidth,
  resolveTreeVirtualScrollOffset,
} from './treeScrollWidth';

assert.equal(
  resolveNextTreeScrollWidth(853, 190, true),
  190,
  'a new measurement cycle discards widths from collapsed or replaced nodes',
);
assert.equal(
  resolveNextTreeScrollWidth(190, 853, false),
  853,
  'a measurement cycle retains the widest virtualized node encountered so far',
);
assert.equal(
  resolveTreeVirtualScrollOffset('-240px', '0px'),
  240,
  'left-to-right virtual scrolling is restored before measuring title positions',
);
assert.equal(
  resolveTreeVirtualScrollOffset('0px', '-120px'),
  120,
  'right-to-left virtual scrolling is restored before measuring title positions',
);

assert.equal(resolveTreeScrollWidth(320, []), 320, 'the tree viewport remains the minimum scroll width');
assert.equal(
  resolveTreeScrollWidth(320, [{ left: 72, width: 360 }]),
  440,
  'node indentation and title width extend the horizontal scroll range',
);
assert.equal(
  resolveTreeScrollWidth(320, [
    { left: 20, width: 100 },
    { left: 64, width: 500.2 },
  ]),
  573,
  'the widest rendered title determines the scroll range',
);

console.log('Tree horizontal scroll width tests passed');
