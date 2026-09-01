import { Button } from 'antd';
import { staticModal } from '@chat2db/ui';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';
import type { IWorkspaceTab } from '@/typings';
import { confirmAndKillTerminalTabs } from '@/utils/terminalSession';
import {
  confirmDirtyEditorTabs,
  isEditorCloseConfirmationEnabled,
  prepareEditorsForApplicationExit,
  waitForPendingEditorTabs,
  type EditorCloseDecision,
  type EditorCloseGuardMap,
  type EditorCloseGuardRef,
} from './editorCloseGuard';

interface EditorCloseDialogFooterProps {
  editor: EditorCloseGuardRef;
  onDecision: (decision: EditorCloseDecision) => void;
}

const footerButtonStyle = { marginInlineStart: 0 };

function EditorCloseDialogFooter({ editor, onDecision }: EditorCloseDialogFooterProps) {
  const handleSave = () => {
    if (!editor.saveBeforeClose) {
      return;
    }
    // Resolve the dialog before starting the save. An unnamed draft opens a
    // second modal to collect its name, which must be the only active layer.
    onDecision('save');
  };

  return (
    <div style={{ display: 'grid', gap: 8, width: '100%' }}>
      <Button block type="primary" style={footerButtonStyle} onClick={handleSave}>
        {i18n('common.button.save')}
      </Button>
      <Button block style={footerButtonStyle} onClick={() => onDecision('discard')}>
        {i18n('workspace.editorClose.dontSave')}
      </Button>
      <Button block style={footerButtonStyle} onClick={() => onDecision('cancel')}>
        {i18n('common.button.cancel')}
      </Button>
    </div>
  );
}

function requestEditorCloseDecision(tab: IWorkspaceTab, editor: EditorCloseGuardRef) {
  return new Promise<EditorCloseDecision>((resolve) => {
    let selectedDecision: EditorCloseDecision | undefined;
    const finish = (nextDecision: EditorCloseDecision) => {
      if (selectedDecision !== undefined) {
        return;
      }
      selectedDecision = nextDecision;
      modal.destroy();
    };
    const modal = staticModal.confirm({
      icon: null,
      width: 390,
      centered: true,
      closable: false,
      maskClosable: false,
      autoFocusButton: null,
      title: i18n('workspace.editorClose.title', tab.title),
      content: i18n('workspace.editorClose.content'),
      footer: () => <EditorCloseDialogFooter editor={editor} onDecision={finish} />,
      onCancel: () => finish('cancel'),
      afterClose: () => {
        resolve(selectedDecision ?? 'cancel');
      },
    });
  });
}

export async function confirmDirtyWorkspaceEditors(tabs: IWorkspaceTab[], editorList: EditorCloseGuardMap) {
  if (!isEditorCloseConfirmationEnabled(useGlobalStore.getState().editorSettings)) {
    return waitForPendingEditorTabs(tabs, editorList);
  }
  if (!(await confirmDirtyEditorTabs(tabs, editorList, requestEditorCloseDecision))) {
    return false;
  }

  return true;
}

export function waitForPendingWorkspaceEditors(tabs: IWorkspaceTab[], editorList: EditorCloseGuardMap) {
  return waitForPendingEditorTabs(tabs, editorList);
}

export async function prepareWorkspaceEditorsForApplicationExit(
  tabs: IWorkspaceTab[],
  editorList: EditorCloseGuardMap,
) {
  return prepareEditorsForApplicationExit(
    tabs,
    editorList,
    requestEditorCloseDecision,
    isEditorCloseConfirmationEnabled(useGlobalStore.getState().editorSettings),
  );
}

export async function confirmWorkspaceTabsClose(
  tabs: IWorkspaceTab[],
  allTabs: IWorkspaceTab[],
  editorList: EditorCloseGuardMap,
) {
  if (!(await confirmDirtyWorkspaceEditors(tabs, editorList))) {
    return false;
  }

  return confirmAndKillTerminalTabs(tabs, allTabs);
}
