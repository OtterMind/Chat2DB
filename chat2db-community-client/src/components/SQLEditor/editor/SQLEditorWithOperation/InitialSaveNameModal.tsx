import { Form, Input, Modal } from 'antd';
import i18n from '@/i18n';
import { normalizeSavedConsoleName } from '../../helper/savedConsoleName';

interface InitialSaveNameModalProps {
  open: boolean;
  name: string;
  loading: boolean;
  onNameChange: (name: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
}

const InitialSaveNameModal = ({
  open,
  name,
  loading,
  onNameChange,
  onConfirm,
  onCancel,
}: InitialSaveNameModalProps) => {
  const valid = !!normalizeSavedConsoleName(name);

  return (
    <Modal
      open={open}
      title={i18n('workspace.savedConsole.initialSaveTitle')}
      width={420}
      okText={i18n('common.button.confirm')}
      cancelText={i18n('common.button.cancel')}
      confirmLoading={loading}
      okButtonProps={{ disabled: !valid }}
      cancelButtonProps={{ disabled: loading }}
      closable={!loading}
      maskClosable={false}
      destroyOnClose
      onOk={onConfirm}
      onCancel={onCancel}
    >
      <Form layout="vertical" requiredMark={false}>
        <Form.Item
          label={i18n('common.text.name')}
          required
          validateStatus={valid ? undefined : 'error'}
          help={valid ? undefined : i18n('common.form.error.required')}
        >
          <Input
            value={name}
            placeholder={i18n('workspace.savedConsole.namePlaceholder')}
            autoFocus
            disabled={loading}
            onFocus={(event) => event.currentTarget.select()}
            onChange={(event) => onNameChange(event.target.value)}
            onPressEnter={() => {
              if (valid && !loading) {
                onConfirm();
              }
            }}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default InitialSaveNameModal;
