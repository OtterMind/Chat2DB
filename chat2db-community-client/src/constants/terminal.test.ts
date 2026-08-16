import assert from 'node:assert/strict';
import { DEFAULT_TERMINAL_SETTINGS, isTerminalCloseConfirmationEnabled } from './terminal';

assert.equal(DEFAULT_TERMINAL_SETTINGS.confirmBeforeClose, true);
assert.equal(isTerminalCloseConfirmationEnabled(), true);
assert.equal(isTerminalCloseConfirmationEnabled({}), true);
assert.equal(isTerminalCloseConfirmationEnabled({ confirmBeforeClose: true }), true);
assert.equal(isTerminalCloseConfirmationEnabled({ confirmBeforeClose: false }), false);

console.log('Terminal settings tests passed');
