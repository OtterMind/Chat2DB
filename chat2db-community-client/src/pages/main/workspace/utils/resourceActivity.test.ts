import assert from 'node:assert/strict';
import { isWorkspaceTabResourceActive } from './resourceActivity';

assert.equal(isWorkspaceTabResourceActive(true, 'console-1', 'console-1'), true);
assert.equal(isWorkspaceTabResourceActive(true, 'console-1', 'console-2'), false);
assert.equal(isWorkspaceTabResourceActive(false, 'console-1', 'console-1'), false);
assert.equal(isWorkspaceTabResourceActive(true, 7, 7), true);
assert.equal(isWorkspaceTabResourceActive(true, 7, null), false);

console.log('Workspace resource activity tests passed');
