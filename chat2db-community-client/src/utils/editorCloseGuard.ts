import type { EditorSettings } from '@/components/SQLEditor';
import type { IWorkspaceTab } from '@/typings';

export type EditorCloseDecision = 'save' | 'saved' | 'discard' | 'cancel';

export interface EditorCloseGuardRef {
  hasUnsavedChangesBeforeClose?: () => boolean;
  saveBeforeClose?: () => Promise<boolean>;
}

export type EditorCloseGuardMap = Record<string | number, EditorCloseGuardRef | undefined>;

export const isEditorCloseConfirmationEnabled = (settings?: Partial<EditorSettings>) =>
  settings?.confirmBeforeClose ?? true;

export async function confirmDirtyEditorTabs(
  tabs: IWorkspaceTab[],
  editorList: EditorCloseGuardMap,
  requestDecision: (tab: IWorkspaceTab, editor: EditorCloseGuardRef) => Promise<EditorCloseDecision>,
) {
  for (const tab of tabs) {
    const editor = editorList[tab.id];
    if (!editor?.hasUnsavedChangesBeforeClose?.()) {
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
