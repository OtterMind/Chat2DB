import assert from 'node:assert/strict';
import {
  clearPersistentTerminalBuffer,
  getLastRenderedTerminalSequence,
  getPersistentTerminalBuffer,
  setLastRenderedTerminalSequence,
  setPersistentTerminalBuffer,
} from './terminalBuffer';

const sessionId = 'terminal-buffer-test';
clearPersistentTerminalBuffer(sessionId);

setPersistentTerminalBuffer(sessionId, 'first buffer');
setLastRenderedTerminalSequence(sessionId, 3);
setLastRenderedTerminalSequence(sessionId, 2);
setPersistentTerminalBuffer(sessionId, 'updated buffer');

assert.equal(getPersistentTerminalBuffer(sessionId), 'updated buffer');
assert.equal(getLastRenderedTerminalSequence(sessionId), 3);

clearPersistentTerminalBuffer(sessionId);
assert.equal(getPersistentTerminalBuffer(sessionId), undefined);
assert.equal(getLastRenderedTerminalSequence(sessionId), undefined);

console.log('Terminal buffer tests passed');
