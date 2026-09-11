import { DatePicker, Form, Radio, type DatePickerProps, type FormItemProps } from 'antd';
import type { Rule } from 'antd/es/form';
import type { ReactNode } from 'react';

type ValidityValue = boolean | number | string;

export interface PermissionValidityFieldProps<T extends ValidityValue> {
  datePickerProps?: DatePickerProps;
  disabled?: boolean;
  permanentLabel?: ReactNode;
  permanentName?: FormItemProps['name'];
  permanentText?: ReactNode;
  permanentValue: T;
  readOnly?: boolean;
  temporaryText?: ReactNode;
  temporaryValue: T;
  validUntilLabel?: ReactNode;
  validUntilName?: FormItemProps['name'];
  validUntilRules?: Rule[];
}

const PermissionValidityField = <T extends ValidityValue>({
  datePickerProps,
  disabled = false,
  permanentLabel = '是否永久有效',
  permanentName = 'noExpire',
  permanentText = '是',
  permanentValue,
  readOnly = false,
  temporaryText = '否',
  temporaryValue,
  validUntilLabel = '权限到期时间',
  validUntilName = 'validUntil',
  validUntilRules = [{ required: true, message: '请选择到期时间' }],
}: PermissionValidityFieldProps<T>) => {
  const form = Form.useFormInstance();
  const currentValue = Form.useWatch(permanentName, { form, preserve: true });
  const isPermanent = currentValue === permanentValue;
  const fieldDisabled = disabled || readOnly;

  return (
    <>
      <Form.Item label={permanentLabel} name={permanentName}>
        <Radio.Group
          disabled={fieldDisabled}
          onChange={(event) => {
            if (event.target.value === permanentValue) {
              form.setFieldValue(validUntilName, undefined);
            }
          }}
        >
          <Radio value={permanentValue}>{permanentText}</Radio>
          <Radio value={temporaryValue}>{temporaryText}</Radio>
        </Radio.Group>
      </Form.Item>
      {isPermanent ? null : (
        <Form.Item label={validUntilLabel} name={validUntilName} rules={validUntilRules}>
          <DatePicker
            showTime
            {...datePickerProps}
            disabled={fieldDisabled || datePickerProps?.disabled}
            style={{ width: '100%', ...datePickerProps?.style }}
          />
        </Form.Item>
      )}
    </>
  );
};

export default PermissionValidityField;
