import assert from 'node:assert/strict';
import {
  registerCloseActiveResultTabHandler,
  requestCloseActiveResultTab,
} from './resultTabShortcut';

assert.equal(
  requestCloseActiveResultTab(),
  false,
  'the workspace close shortcut continues when no active result tab handles it',
);

const calls: string[] = [];
const unregisterWorkspace = registerCloseActiveResultTabHandler(() => {
  calls.push('workspace');
  return 'closed';
});
const unregisterModal = registerCloseActiveResultTabHandler(() => {
  calls.push('modal');
  return 'closed';
});
assert.equal(
  requestCloseActiveResultTab(),
  true,
  'an active result tab can consume the workspace close shortcut',
);
assert.deepEqual(calls, ['modal'], 'the most recently mounted active result surface is the only owner');

unregisterModal();
assert.equal(requestCloseActiveResultTab(), true);
assert.deepEqual(calls, ['modal', 'workspace'], 'unmounting the top surface restores the previous owner');

const unregisterOutputSurface = registerCloseActiveResultTabHandler(() => {
  calls.push('output');
  return 'pass-through';
});
assert.equal(
  requestCloseActiveResultTab(),
  false,
  'an active Output surface leaves the shortcut available for the workspace fallback',
);
assert.deepEqual(calls, ['modal', 'workspace', 'output'], 'older result surfaces do not also consume the shortcut');
unregisterOutputSurface();

const unregisterHiddenSurface = registerCloseActiveResultTabHandler(() => {
  calls.push('hidden');
  return 'inactive';
});
assert.equal(
  requestCloseActiveResultTab(),
  true,
  'a hidden page does not block the visible result surface from handling the shortcut',
);
assert.deepEqual(calls, ['modal', 'workspace', 'output', 'hidden', 'workspace']);
unregisterHiddenSurface();

unregisterWorkspace();
assert.equal(requestCloseActiveResultTab(), false);

console.log('Result tab shortcut tests passed');
