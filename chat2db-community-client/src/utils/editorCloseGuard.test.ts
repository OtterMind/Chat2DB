import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings';
import {
  confirmDirtyEditorTabs,
  isEditorCloseConfirmationEnabled,
  prepareEditorsForApplicationExit,
  type EditorCloseGuardMap,
} from './editorCloseGuard';

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

  let releasePendingSave!: () => void;
  const pendingSave = new Promise<void>((resolve) => {
    releasePendingSave = resolve;
  });
  let pendingCloseResolved = false;
  const pendingClose = confirmDirtyEditorTabs(
    [editorOne],
    {
      [editorOne.id]: {
        waitForPendingSave: () => pendingSave,
        hasUnsavedChangesBeforeClose: () => false,
      },
    },
    async () => 'cancel',
  ).then((result) => {
    pendingCloseResolved = true;
    return result;
  });
  await Promise.resolve();
  assert.equal(pendingCloseResolved, false, 'close waits for an in-flight save before checking dirty state');
  releasePendingSave();
  assert.equal(await pendingClose, true);

  assert.equal(
    await confirmDirtyEditorTabs([editorOne], {}, async () => 'discard'),
    false,
    'editable tabs without a close guard fail closed',
  );
  assert.equal(
    await confirmDirtyEditorTabs(
      [{ id: 'preview', type: WorkspaceTabType.LocalSQLFile, title: 'Preview', uniqueData: { filePreviewMimeType: 'image/png' } }],
      {},
      async () => 'cancel',
    ),
    true,
    'non-editable previews do not require a close guard',
  );

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

  let persistedDrafts = 0;
  let applicationExitPrompts = 0;
  assert.equal(
    await prepareEditorsForApplicationExit(
      [editorOne, editorTwo],
      {
        [editorOne.id]: {
          hasUnsavedChangesBeforeClose: () => true,
          persistBeforeApplicationExit: async () => {
            persistedDrafts += 1;
            return true;
          },
        },
        [editorTwo.id]: {
          hasUnsavedChangesBeforeClose: () => true,
        },
      },
      async () => {
        applicationExitPrompts += 1;
        return 'discard';
      },
      true,
    ),
    true,
  );
  assert.equal(persistedDrafts, 1, 'application exit flushes recoverable console drafts');
  assert.equal(applicationExitPrompts, 1, 'only non-auto-saved editors require an application-exit prompt');

  let exitPendingSaveWaited = false;
  assert.equal(
    await prepareEditorsForApplicationExit(
      [editorTwo],
      {
        [editorTwo.id]: {
          waitForPendingSave: async () => {
            exitPendingSaveWaited = true;
          },
          hasUnsavedChangesBeforeClose: () => true,
        },
      },
      async () => 'cancel',
      false,
    ),
    true,
  );
  assert.equal(exitPendingSaveWaited, true, 'application exit drains pending saves even when prompts are disabled');

  const workspaceTabsSource = readFileSync('src/pages/main/workspace/components/WorkspaceTabs/index.tsx', 'utf8');
  const consoleActionSource = readFileSync('src/store/workspace/slices/console/action.ts', 'utf8');
  const applicationExitSource = readFileSync('src/layouts/init/useApplicationExit.ts', 'utf8');
  const editorSource = readFileSync('src/components/SQLEditor/editor/SQLEditorWithOperation/index.tsx', 'utf8');
  const confirmationSource = readFileSync('src/utils/editorCloseConfirmation.tsx', 'utf8');
  const markdownSource = readFileSync('src/pages/main/workspace/components/WorkspaceTabs/FilePreviewTab.tsx', 'utf8');
  const localFileTreeSource = readFileSync('src/pages/main/workspace/components/LocalSQLFileTree/index.tsx', 'utf8');
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
  const deleteTabBody = consoleActionSource.slice(consoleActionSource.indexOf('deleteActiveWorkspaceTab'));
  assert.match(
    deleteTabBody,
    /confirmWorkspaceTabsClose[\s\S]*?confirmAndReleaseTransaction/,
    'the global close shortcut must defer transaction resolution until other close confirmations pass',
  );
  const tabCloseGuardBody = workspaceTabsSource.slice(workspaceTabsSource.indexOf('const confirmWorkspaceTabItemsClose'));
  assert.match(
    tabCloseGuardBody,
    /confirmWorkspaceTabsClose[\s\S]*?confirmAndReleaseTransaction/,
    'tab close must defer transaction resolution until other close confirmations pass',
  );
  const bulkCloseBody = workspaceTabsSource.slice(workspaceTabsSource.indexOf('const requestCloseWorkspaceTabs'));
  assert.match(
    bulkCloseBody,
    /confirmWorkspaceTabsClose[\s\S]*?confirmAndReleaseTransaction/,
    'bulk tab close must defer transaction resolution until other close confirmations pass',
  );
  const applicationExitBody = applicationExitSource.slice(applicationExitSource.indexOf('confirmDirtyEditors'));
  assert.match(
    applicationExitBody,
    /confirmDirtyEditors:[\s\S]*?prepareWorkspaceEditorsForApplicationExit[\s\S]*?finalizeBeforeClose:[\s\S]*?confirmAndReleaseTransaction/,
    'application exit must defer transaction resolution until editor and task confirmations pass',
  );
  assert.match(editorSource, /hasUnsavedChangesBeforeClose/, 'editor refs expose dirty-state detection');
  assert.match(editorSource, /saveBeforeClose/, 'editor refs expose a real save operation');
  assert.match(markdownSource, /setEditorToList\(workspaceTabId, markdownCloseGuard\)/, 'markdown files register a close guard');
  assert.match(localFileTreeSource, /confirmWorkspaceFileTabsBeforeRemoval/, 'file deletion checks open dirty files');
  assert.match(localFileTreeSource, /waitForPendingWorkspaceEditors/, 'file rename waits for pending writes');
  assert.match(markdownSource, /shortcutBindingToMonacoKeybinding/, 'markdown uses the configured save shortcut');
  assert.doesNotMatch(
    markdownSource,
    /monaco\.KeyMod\.CtrlCmd\s*\|\s*monaco\.KeyCode\.KeyS/,
    'markdown must not retain a hard-coded save shortcut',
  );
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
  const transactionSource = readFileSync('src/utils/transactionSession.ts', 'utf8');
  assert.match(
    transactionSource,
    /const transactionDialogFooterStyle = \{[\s\S]*?justifyContent: 'flex-end',[\s\S]*?gap: 8,/,
    'the transaction dialog must render an explicit right-aligned footer',
  );
  assert.match(
    transactionSource,
    /color: 'red',[\s\S]*?variant: 'outlined'/,
    'rollback must use the red outlined action style',
  );
  assert.match(
    transactionSource,
    /color: 'primary',[\s\S]*?variant: 'solid'/,
    'commit must use the theme-colored solid action style',
  );
  assert.match(
    confirmationSource,
    /const handleSave = \(\) => \{[\s\S]*?onDecision\('save'\)/,
    'the save action resolves the close dialog before the guard starts saving',
  );
  assert.doesNotMatch(
    confirmationSource,
    /await editor\.saveBeforeClose\(\)/,
    'the close dialog must not keep a second save modal blocked behind it',
  );
  assert.match(
    confirmationSource,
    /afterClose: \(\) => \{[\s\S]*?resolve\(selectedDecision \?\? 'cancel'\)/,
    'the guard waits for the close mask to finish its exit lifecycle',
  );

  console.log('Editor close guard tests passed');
}

void run();
