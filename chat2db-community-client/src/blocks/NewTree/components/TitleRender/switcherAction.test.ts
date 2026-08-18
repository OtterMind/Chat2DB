import assert from 'node:assert/strict';
import { resolveTreeSwitcherAction } from './switcherAction';

assert.equal(resolveTreeSwitcherAction(true, false), 'ignore');
assert.equal(resolveTreeSwitcherAction(true, true), 'ignore');
assert.equal(resolveTreeSwitcherAction(false, true), 'collapse');
assert.equal(resolveTreeSwitcherAction(false, false), 'load');

console.log('Tree switcher action tests passed');
