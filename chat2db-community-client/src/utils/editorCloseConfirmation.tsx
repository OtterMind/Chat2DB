import { useState } from 'react';
import { Button } from 'antd';
import { staticModal } from '@chat2db/ui';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';
import type { IWorkspaceTab } from '@/typings';
import { confirmAndKillTerminalTabs } from '@/utils/terminalSession';
import {
  confirmDirtyEditorTabs,
  isEditorCloseConfirmationEnabled,
  type EditorCloseDecision,
  type EditorCloseGuardMap,
  type EditorCloseGuardRef,
} from './editorCloseGuard';

interface EditorCloseDialogFooterProps {
  editor: EditorCloseGuardRef;
  onDecision: (decision: EditorCloseDecision) => void;
}

function EditorCloseDialogFooter({ editor, onDecision }: EditorCloseDialogFooterProps) {
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (!editor.saveBeforeClose || saving) {
      return;
    }
    setSaving(true);
    try {
      if (await editor.saveBeforeClose()) {
        onDecision('saved');
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ display: 'grid', gap: 8, width: '100%' }}>
      <Button block type="primary" loading={saving} onClick={handleSave}>
        {i18n('common.button.save')}
      </Button>
      <Button block disabled={saving} onClick={() => onDecision('discard')}>
        {i18n('workspace.editorClose.dontSave')}
      </Button>
      <Button block disabled={saving} onClick={() => onDecision('cancel')}>
        {i18n('common.button.cancel')}
      </Button>
    </div>
  );
}

function requestEditorCloseDecision(tab: IWorkspaceTab, editor: EditorCloseGuardRef) {
  return new Promise<EditorCloseDecision>((resolve) => {
    let settled = false;
    const finish = (decision: EditorCloseDecision) => {
      if (settled) {
        return;
      }
      settled = true;
      modal.destroy();
      resolve(decision);
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
        if (!settled) {
          settled = true;
          resolve('cancel');
        }
      },
    });
  });
}

export async function confirmWorkspaceTabsClose(
  tabs: IWorkspaceTab[],
  allTabs: IWorkspaceTab[],
  editorList: EditorCloseGuardMap,
) {
  if (
    isEditorCloseConfirmationEnabled(useGlobalStore.getState().editorSettings) &&
    !(await confirmDirtyEditorTabs(tabs, editorList, requestEditorCloseDecision))
  ) {
    return false;
  }

  return confirmAndKillTerminalTabs(tabs, allTabs);
}
