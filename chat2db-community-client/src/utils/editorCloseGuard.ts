import type { EditorSettings } from '@/components/SQLEditor';
import type { IWorkspaceTab } from '@/typings';
import { WorkspaceTabType } from '@/constants/workspace';

export type EditorCloseDecision = 'save' | 'saved' | 'discard' | 'cancel';

export interface EditorCloseGuardRef {
  hasUnsavedChangesBeforeClose?: () => boolean;
  saveBeforeClose?: () => Promise<boolean>;
  waitForPendingSave?: () => Promise<void>;
  persistBeforeApplicationExit?: () => Promise<boolean>;
}

export type EditorCloseGuardMap = Record<string | number, EditorCloseGuardRef | undefined>;

function requiresEditorCloseGuard(tab: IWorkspaceTab) {
  return (
    tab.type === WorkspaceTabType.CONSOLE ||
    (tab.type === WorkspaceTabType.LocalSQLFile && !tab.uniqueData?.filePreviewMimeType)
  );
}

export const isEditorCloseConfirmationEnabled = (settings?: Partial<EditorSettings>) =>
  settings?.confirmBeforeClose ?? true;

export async function waitForPendingEditorTabs(tabs: IWorkspaceTab[], editorList: EditorCloseGuardMap) {
  for (const tab of tabs) {
    const editor = editorList[tab.id];
    if (!editor) {
      if (requiresEditorCloseGuard(tab)) {
        return false;
      }
      continue;
    }
    await editor.waitForPendingSave?.();
  }
  return true;
}

async function confirmDirtyEditorTabsAfterPending(
  tabs: IWorkspaceTab[],
  editorList: EditorCloseGuardMap,
  requestDecision: (tab: IWorkspaceTab, editor: EditorCloseGuardRef) => Promise<EditorCloseDecision>,
) {
  for (const tab of tabs) {
    const editor = editorList[tab.id];
    if (!editor) {
      continue;
    }
    if (!editor.hasUnsavedChangesBeforeClose?.()) {
      continue;
    }

    const decision = await requestDecision(tab, editor);
    if (decision === 'cancel') {
      return false;
    }
    if (decision === 'save') {
      if (!editor.saveBeforeClose || !(await editor.saveBeforeClose())) {
        return false;
      }
    }
  }
  return true;
}

export async function confirmDirtyEditorTabs(
  tabs: IWorkspaceTab[],
  editorList: EditorCloseGuardMap,
  requestDecision: (tab: IWorkspaceTab, editor: EditorCloseGuardRef) => Promise<EditorCloseDecision>,
) {
  if (!(await waitForPendingEditorTabs(tabs, editorList))) {
    return false;
  }
  return confirmDirtyEditorTabsAfterPending(tabs, editorList, requestDecision);
}

export async function prepareEditorsForApplicationExit(
  tabs: IWorkspaceTab[],
  editorList: EditorCloseGuardMap,
  requestDecision: (tab: IWorkspaceTab, editor: EditorCloseGuardRef) => Promise<EditorCloseDecision>,
  confirmDirtyEditors: boolean,
) {
  if (!(await waitForPendingEditorTabs(tabs, editorList))) {
    return false;
  }
  const tabsRequiringConfirmation: IWorkspaceTab[] = [];
  for (const tab of tabs) {
    const editor = editorList[tab.id];
    if (editor?.persistBeforeApplicationExit) {
      if (!(await editor.persistBeforeApplicationExit())) {
        return false;
      }
      continue;
    }
    tabsRequiringConfirmation.push(tab);
  }
  if (!confirmDirtyEditors) {
    return true;
  }
  return confirmDirtyEditorTabsAfterPending(tabsRequiringConfirmation, editorList, requestDecision);
}
