import { Form, Input, InputNumber, type FormInstance } from 'antd';
import i18n from '@/i18n';

export interface CreateTablespaceValues {
  name: string;
  dataFile: string;
  fileBlockSize?: number;
}

interface IProps {
  form: FormInstance<CreateTablespaceValues>;
  /**
   * When set, the form is in rename mode: only the new-name field is shown (the datafile and
   * file-block-size inputs are hidden). The value is the current tablespace name, displayed as
   * the label's context.
   */
  renameFrom?: string;
}

/**
 * Form body for creating an InnoDB General Tablespace. The engine is fixed to InnoDB (not a
 * field); the data-file path is user-supplied and belongs to the MySQL server filesystem — it is
 * emitted verbatim and never validated or canonicalized here. In rename mode (`renameFrom` set)
 * only the new name is collected.
 */
const CreateTablespaceContent = ({ form, renameFrom }: IProps) => {
  return (
    <Form form={form} layout="vertical">
      <Form.Item
        name="name"
        label={renameFrom ? i18n('workspace.tablespace.newName') : i18n('workspace.tablespace.name')}
        rules={[{ required: true }]}
      >
        <Input autoComplete="off" />
      </Form.Item>
      {!renameFrom && (
        <>
          <Form.Item
            name="dataFile"
            label={i18n('workspace.tablespace.dataFile')}
            rules={[{ required: true }]}
            tooltip={i18n('workspace.tablespace.dataFileTooltip')}
          >
            <Input autoComplete="off" placeholder="ts_data.ibd" />
          </Form.Item>
          <Form.Item
            name="fileBlockSize"
            label={i18n('workspace.tablespace.fileBlockSize')}
            tooltip={i18n('workspace.tablespace.fileBlockSizeTooltip')}
          >
            <InputNumber autoComplete="off" min={0} style={{ width: '100%' }} />
          </Form.Item>
        </>
      )}
    </Form>
  );
};

export default CreateTablespaceContent;
