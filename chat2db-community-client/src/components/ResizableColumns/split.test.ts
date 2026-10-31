import assert from 'node:assert/strict';
import test from 'node:test';
import { DEFAULT_SPLIT, splitAtPointer, splitForKey } from './split';

test('maps the pointer relative to the container and the divider center', () => {
  assert.equal(splitAtPointer(600, 100, 1000), 50);
  assert.equal(splitAtPointer(850, 350, 1000), 50);
  assert.equal(splitAtPointer(350, 100, 500), 50);
});

test('keeps both panes usable when dragging outside the container', () => {
  assert.equal(splitAtPointer(-500, 100, 1000), 25);
  assert.equal(splitAtPointer(2000, 100, 1000), 75);
  assert.equal(splitAtPointer(100, 100, 0), DEFAULT_SPLIT);
});

test('supports keyboard adjustment and clamps at the limits', () => {
  assert.equal(splitForKey(55, 'ArrowLeft'), 52.5);
  assert.equal(splitForKey(55, 'ArrowRight'), 57.5);
  assert.equal(splitForKey(25, 'ArrowLeft'), 25);
  assert.equal(splitForKey(75, 'ArrowRight'), 75);
  assert.equal(splitForKey(55, 'Home'), 25);
  assert.equal(splitForKey(55, 'End'), 75);
  assert.equal(splitForKey(55, 'Tab'), undefined);
});
