import { createRef, forwardRef, useImperativeHandle } from 'react';
import { Form, Input, type FormInstance } from 'antd';
import { staticModal } from '@chat2db/ui';
import i18n from '@/i18n';
import sqlService, { type ICopyTableParams } from '@/service/sql';

interface CopyTableValues {
  newName: string;
}

interface CopyTableContentProps {
  defaultName: string;
  onSubmit: () => void;
}

const CopyTableContent = forwardRef<FormInstance<CopyTableValues>, CopyTableContentProps>(
  ({ defaultName, onSubmit }, ref) => {
    const [form] = Form.useForm<CopyTableValues>();
    useImperativeHandle(ref, () => form, [form]);

    return (
      <Form form={form} layout="vertical" initialValues={{ newName: defaultName }}>
        <Form.Item
          name="newName"
          label={i18n('common.text.tableName')}
          rules={[{ required: true, whitespace: true, message: i18n('common.form.error.required') }]}
        >
          <Input
            onFocus={(event) => event.target.select()}
            onPressEnter={(event) => {
              event.preventDefault();
              if (event.nativeEvent.isComposing) return;
              onSubmit();
            }}
          />
        </Form.Item>
      </Form>
    );
  },
);

export const openCopyTableModal = async (params: Omit<ICopyTableParams, 'newName'>, onSuccess: () => void) => {
  const defaultName = await sqlService.prepareCopyTable(params);
  const form = createRef<FormInstance<CopyTableValues>>();
  let pending: Promise<void> | undefined;

  const submit = (): Promise<void> => {
    if (pending) return pending;
    pending = (async () => {
      const values = await form.current!.validateFields();
      modal.update({ confirmLoading: true, cancelButtonProps: { disabled: true }, keyboard: false, closable: false });
      await sqlService.copyTable({ ...params, newName: values.newName });
      onSuccess();
      modal.destroy();
    })().finally(() => {
      pending = undefined;
      modal.update({ confirmLoading: false, cancelButtonProps: { disabled: false }, keyboard: true, closable: true });
    });
    return pending;
  };

  const modal = staticModal.confirm({
    title: i18n(params.copyData ? 'workspace.menu.copyStructureData' : 'workspace.menu.copyStructure'),
    icon: null,
    autoFocusButton: null,
    afterOpenChange: (open) => {
      if (open) form.current?.focusField('newName', { focus: true });
    },
    content: <CopyTableContent ref={form} defaultName={defaultName} onSubmit={() => void submit().catch(() => {})} />,
    okText: i18n('common.button.confirm'),
    cancelText: i18n('common.button.cancel'),
    confirmLoading: false,
    cancelButtonProps: { disabled: false },
    maskClosable: false,
    closable: true,
    keyboard: true,
    onOk: submit,
  });
};
