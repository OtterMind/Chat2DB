import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { hasUnsavedSavedConsoleChanges } from './savedConsoleDirty';

assert.equal(hasUnsavedSavedConsoleChanges('', false, ''), false);
assert.equal(hasUnsavedSavedConsoleChanges('select 1', false, ''), true);
assert.equal(hasUnsavedSavedConsoleChanges('select 1', true, 'select 1'), false);
assert.equal(hasUnsavedSavedConsoleChanges('', true, 'select 1'), true);
assert.equal(hasUnsavedSavedConsoleChanges('', true, ''), false);

const saveEditorDataSource = readFileSync('src/components/SQLEditor/hooks/useSaveEditorData.ts', 'utf8');
assert.doesNotMatch(
  saveEditorDataSource,
  /lastSyncConsole\.current\s*=\s*null/,
  'tab activation changes must preserve the persisted console baseline',
);
console.log('saved console dirty tests passed');
