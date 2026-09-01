import assert from 'node:assert/strict';
import type { ISQLEditorWithOperationRef } from '@/components/SQLEditor/editor/SQLEditorWithOperation';
import { createLiveSqlEditorHandle, type CurrentSqlEditorHandleRef } from './liveEditorHandle';

const first = {
  getValue: () => 'first',
  hasUnsavedChangesBeforeClose: () => false,
  saveBeforeClose: async () => false,
} as ISQLEditorWithOperationRef;
const second = {
  getValue: () => 'second',
  hasUnsavedChangesBeforeClose: () => true,
  saveBeforeClose: async () => true,
  waitForPendingSave: async () => undefined,
  persistBeforeApplicationExit: async () => true,
} as ISQLEditorWithOperationRef;
const editorRef: CurrentSqlEditorHandleRef = { current: first };
const liveEditor = createLiveSqlEditorHandle(editorRef);

async function run() {
  assert.equal(liveEditor.getValue(), 'first');
  assert.equal(liveEditor.hasUnsavedChangesBeforeClose?.(), false);
  assert.equal(liveEditor.persistBeforeApplicationExit, undefined);

  editorRef.current = second;
  assert.equal(liveEditor.getValue(), 'second', 'the registered handle reads the latest editor ref');
  assert.equal(liveEditor.hasUnsavedChangesBeforeClose?.(), true);
  assert.equal(await liveEditor.saveBeforeClose?.(), true);
  assert.equal(await liveEditor.waitForPendingSave?.(), undefined);
  assert.equal(await liveEditor.persistBeforeApplicationExit?.(), true);

  editorRef.current = null;
  assert.equal(liveEditor.hasUnsavedChangesBeforeClose?.(), true, 'a missing live editor fails closed');
  assert.equal(await liveEditor.saveBeforeClose?.(), false);
  assert.equal(await liveEditor.waitForPendingSave?.(), undefined);

  console.log('live editor handle tests passed');
}

void run();
