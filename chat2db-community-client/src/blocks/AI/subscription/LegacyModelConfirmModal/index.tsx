import { Modal, Select } from 'antd';
import i18n from '@/i18n';

interface LegacyModelConfirmModalProps {
  open: boolean;
  options: Array<{ label: string; value: string }>;
  preselectedModelRefKey?: string | null;
  selectedModelRefKey?: string | null;
  onChange: (modelRefKey: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Legacy conversations remain readable; the next send requires explicit model confirmation.
 * A valid global default may be preselected but is never auto-confirmed.
 */
export default function LegacyModelConfirmModal({
  open,
  options,
  preselectedModelRefKey,
  selectedModelRefKey,
  onChange,
  onConfirm,
  onCancel,
}: LegacyModelConfirmModalProps) {
  const value = selectedModelRefKey || preselectedModelRefKey || undefined;

  return (
    <Modal
      open={open}
      title={i18n('ai.subscription.legacy.confirmTitle')}
      okText={i18n('ai.subscription.legacy.confirm')}
      onOk={onConfirm}
      onCancel={onCancel}
      okButtonProps={{ disabled: !value }}
      destroyOnClose
    >
      <p>{i18n('ai.subscription.legacy.confirmDesc')}</p>
      <Select
        style={{ width: '100%' }}
        placeholder={i18n('ai.select.model')}
        options={options}
        value={value}
        onChange={onChange}
      />
    </Modal>
  );
}
