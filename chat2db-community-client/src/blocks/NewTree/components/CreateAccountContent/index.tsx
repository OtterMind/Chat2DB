import { Form, Input, type FormInstance } from 'antd';
import i18n from '@/i18n';

export interface CreateAccountValues {
  user: string;
  host: string;
  password?: string;
}

interface IProps {
  form: FormInstance<CreateAccountValues>;
  passwordRequired?: boolean;
}

const CreateAccountContent = ({ form, passwordRequired = true }: IProps) => {
  return (
    <Form form={form} layout="vertical" initialValues={{ host: '%' }}>
      <Form.Item name="user" label={i18n('workspace.databaseAccount.user')} rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item name="host" label={i18n('workspace.databaseAccount.host')} rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      {passwordRequired && (
        <Form.Item name="password" label={i18n('workspace.databaseAccount.password')} rules={[{ required: true }]}>
          <Input.Password />
        </Form.Item>
      )}
    </Form>
  );
};

export default CreateAccountContent;
