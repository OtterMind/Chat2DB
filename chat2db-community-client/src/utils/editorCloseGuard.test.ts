import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings';
import { confirmDirtyEditorTabs, isEditorCloseConfirmationEnabled, type EditorCloseGuardMap } from './editorCloseGuard';

const editorOne: IWorkspaceTab = {
  id: 'editor-1',
  type: WorkspaceTabType.CONSOLE,
  title: 'Editor 1',
};
const editorTwo: IWorkspaceTab = {
  id: 'editor-2',
  type: WorkspaceTabType.LocalSQLFile,
  title: 'Editor 2',
};

async function run() {
  assert.equal(isEditorCloseConfirmationEnabled(), true);
  assert.equal(isEditorCloseConfirmationEnabled({}), true);
  assert.equal(isEditorCloseConfirmationEnabled({ confirmBeforeClose: true }), true);
  assert.equal(isEditorCloseConfirmationEnabled({ confirmBeforeClose: false }), false);

  let decisionCount = 0;
  assert.equal(
    await confirmDirtyEditorTabs(
      [editorOne],
      {
        [editorOne.id]: {
          hasUnsavedChangesBeforeClose: () => false,
        },
      },
      async () => {
        decisionCount += 1;
        return 'cancel';
      },
    ),
    true,
  );
  assert.equal(decisionCount, 0, 'clean editors close without confirmation');

  const discarded = await confirmDirtyEditorTabs(
    [editorOne],
    {
      [editorOne.id]: {
        hasUnsavedChangesBeforeClose: () => true,
      },
    },
    async () => 'discard',
  );
  assert.equal(discarded, true, 'discarding changes allows the close');

  let saveCount = 0;
  const saved = await confirmDirtyEditorTabs(
    [editorOne],
    {
      [editorOne.id]: {
        hasUnsavedChangesBeforeClose: () => true,
        saveBeforeClose: async () => {
          saveCount += 1;
          return true;
        },
      },
    },
    async () => 'save',
  );
  assert.equal(saved, true);
  assert.equal(saveCount, 1, 'saving runs exactly once before close');

  const failedSave = await confirmDirtyEditorTabs(
    [editorOne],
    {
      [editorOne.id]: {
        hasUnsavedChangesBeforeClose: () => true,
        saveBeforeClose: async () => false,
      },
    },
    async () => 'save',
  );
  assert.equal(failedSave, false, 'a failed save keeps the editor open');

  const promptedTabs: Array<string | number> = [];
  const editorList: EditorCloseGuardMap = {
    [editorOne.id]: {
      hasUnsavedChangesBeforeClose: () => true,
    },
    [editorTwo.id]: {
      hasUnsavedChangesBeforeClose: () => true,
    },
  };
  const cancelledBatch = await confirmDirtyEditorTabs([editorOne, editorTwo], editorList, async (tab) => {
    promptedTabs.push(tab.id);
    return 'cancel';
  });
  assert.equal(cancelledBatch, false);
  assert.deepEqual(promptedTabs, [editorOne.id], 'cancelling stops the remaining batch close prompts');

  const alreadySaved = await confirmDirtyEditorTabs([editorOne], editorList, async () => 'saved');
  assert.equal(alreadySaved, true, 'a decision handler may complete the save while its dialog is open');

  const workspaceTabsSource = readFileSync('src/pages/main/workspace/components/WorkspaceTabs/index.tsx', 'utf8');
  const consoleActionSource = readFileSync('src/store/workspace/slices/console/action.ts', 'utf8');
  const editorSource = readFileSync('src/components/SQLEditor/editor/SQLEditorWithOperation/index.tsx', 'utf8');
  const confirmationSource = readFileSync('src/utils/editorCloseConfirmation.tsx', 'utf8');
  assert.match(
    workspaceTabsSource,
    /beforeRemove=\{confirmWorkspaceTabItemsClose\}/,
    'tab close, close others, and close all use the shared guard',
  );
  assert.match(
    workspaceTabsSource,
    /requestCloseWorkspaceTabs[\s\S]*?confirmWorkspaceTabsClose/,
    'close-left and close-right actions use the shared guard',
  );
  assert.match(
    consoleActionSource,
    /deleteActiveWorkspaceTab[\s\S]*?confirmWorkspaceTabsClose/,
    'the global close shortcut uses the shared guard',
  );
  assert.match(editorSource, /hasUnsavedChangesBeforeClose/, 'editor refs expose dirty-state detection');
  assert.match(editorSource, /saveBeforeClose/, 'editor refs expose a real save operation');
  assert.match(
    confirmationSource,
    /const footerButtonStyle = \{ marginInlineStart: 0 \}/,
    'the close dialog overrides Ant Design sibling-button indentation',
  );
  assert.equal(
    confirmationSource.match(/style=\{footerButtonStyle\}/g)?.length,
    3,
    'all close-dialog actions share the same horizontal alignment',
  );

  console.log('Editor close guard tests passed');
}

void run();
