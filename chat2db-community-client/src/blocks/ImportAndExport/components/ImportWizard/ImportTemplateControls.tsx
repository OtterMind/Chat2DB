import { useState } from 'react';
import { Button, Input, Modal } from 'antd';
import i18n from '@/i18n';

interface IProps {
  /** File type the preset is saved as, shown on the button so the scope of the save is explicit. */
  fileType?: string;
  disabled?: boolean;
  onSave: (name: string) => void;
}

/**
 * Save entry point for the format preset of the wizard's format step, rendered in the format
 * toolbar. A preset stores exactly the option group the step shows and the file type it
 * belongs to, so there is nothing else to pick here; storage access stays in
 * `utils/importTemplates`.
 */
export default function ImportTemplateControls({ fileType, disabled, onSave }: IProps) {
  const [saveOpen, setSaveOpen] = useState(false);
  const [name, setName] = useState('');

  const openSave = () => {
    setName('');
    setSaveOpen(true);
  };

  const submitSave = () => {
    const trimmed = name.trim();
    if (!trimmed) {
      return;
    }
    onSave(trimmed);
    setSaveOpen(false);
  };

  return (
    <>
      <Button type="link" size="small" disabled={disabled} onClick={openSave}>
        {fileType
          ? i18n('workspace.importExport.template.saveTyped', fileType)
          : i18n('workspace.importExport.template.save')}
      </Button>
      <Modal
        open={saveOpen}
        title={i18n('workspace.importExport.template.saveTitle')}
        okText={i18n('common.button.confirm')}
        cancelText={i18n('common.button.cancel')}
        onOk={submitSave}
        okButtonProps={{ disabled: !name.trim() }}
        onCancel={() => setSaveOpen(false)}
        destroyOnClose
      >
        <Input
          autoFocus
          value={name}
          maxLength={80}
          placeholder={i18n('workspace.importExport.template.namePlaceholder')}
          aria-label={i18n('workspace.importExport.template.nameLabel')}
          onChange={(event) => setName(event.target.value)}
          onPressEnter={submitSave}
        />
      </Modal>
    </>
  );
}
