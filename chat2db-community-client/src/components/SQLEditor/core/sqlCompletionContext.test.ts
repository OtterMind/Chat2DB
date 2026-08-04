import assert from 'node:assert/strict';
import { getSqlCompletionContextId } from './sqlCompletionContext';

assert.equal(
  getSqlCompletionContextId({ consoleId: 42, workspaceTabId: 99 }),
  42,
  'saved consoles should keep using their console ID',
);
assert.equal(
  getSqlCompletionContextId({ workspaceTabId: 99 }),
  99,
  'local SQL files should use their numeric workspace tab ID',
);
assert.equal(
  getSqlCompletionContextId({ workspaceTabId: 'local-file-tab' }),
  undefined,
  'non-numeric workspace tab IDs cannot be sent to the backend Long field',
);
assert.equal(getSqlCompletionContextId({}), undefined, 'missing editor identity should not enable backend completion');
assert.equal(getSqlCompletionContextId(undefined), undefined, 'missing bound info should be supported');

console.log('sql completion context tests passed');
