import { useCallback, useEffect, useRef, useState } from 'react';
import { Form, Input, Modal, Select, type FormInstance } from 'antd';
import i18n from '@/i18n';
import type { ISqlParameterValue, ISqlParameterValues, SqlParameterType } from '@/service/dmlRequest';
import {
  buildSqlParameterValues,
  isValidSqlParameterValue,
  SQL_PARAMETER_TYPES,
  type SqlParameterDescriptor,
  type SqlParameterInput,
} from '@/utils/sqlParameters';
import { useStyles } from './style';

interface ParameterFieldValue {
  type?: SqlParameterType;
  value?: string;
}

type ParameterFormValues = Record<string, ParameterFieldValue | undefined>;

interface SqlParameterModalProps {
  open: boolean;
  parameters: SqlParameterDescriptor[];
  onRun: (values: ISqlParameterValues) => void;
  onCancel: () => void;
}

const DEFAULT_TYPE: SqlParameterType = 'STRING';

function getSqlParameterLabel(parameter: SqlParameterDescriptor) {
  return parameter.name ?? i18n('sqlEditor.parameter.positionalLabel', parameter.index);
}

/** Turns one form entry into the value sent to the backend; `NULL` never carries text. */
function toParameterValue(field: ParameterFieldValue | undefined): ISqlParameterValue {
  const type = field?.type ?? DEFAULT_TYPE;
  if (type === 'NULL') {
    return { type, value: null };
  }
  const value = field?.value ?? '';
  // Only numbers drop surrounding spaces; text keeps exactly what was typed.
  return { type, value: type === 'NUMBER' ? value.trim() : value };
}

const ParameterField = ({
  form,
  parameter,
  autoFocus,
  onSubmit,
}: {
  form: FormInstance<ParameterFormValues>;
  parameter: SqlParameterDescriptor;
  autoFocus: boolean;
  onSubmit: () => void;
}) => {
  const { styles } = useStyles();
  const type: SqlParameterType = Form.useWatch([parameter.key, 'type'], form) ?? DEFAULT_TYPE;
  const label = getSqlParameterLabel(parameter);

  return (
    <Form.Item
      label={<span className={parameter.name !== undefined ? styles.namedLabel : undefined}>{label}</span>}
      required
    >
      <div className={styles.valueRow}>
        <Form.Item noStyle name={[parameter.key, 'type']} initialValue={DEFAULT_TYPE}>
          <Select
            className={styles.typeSelect}
            aria-label={i18n('sqlEditor.parameter.type')}
            options={SQL_PARAMETER_TYPES.map((value) => ({
              value,
              label: i18n(`sqlEditor.parameter.type.${value}`),
            }))}
            onChange={() => form.setFieldValue([parameter.key, 'value'], undefined)}
          />
        </Form.Item>
        <Form.Item
          noStyle
          name={[parameter.key, 'value']}
          dependencies={[[parameter.key, 'type']]}
          rules={[
            ({ getFieldValue }) => ({
              validator() {
                const value = toParameterValue(getFieldValue([parameter.key]));
                if (isValidSqlParameterValue(value)) {
                  return Promise.resolve();
                }
                return Promise.reject(
                  new Error(
                    i18n(
                      value.type === 'BOOLEAN'
                        ? 'sqlEditor.parameter.invalidBoolean'
                        : 'sqlEditor.parameter.invalidNumber',
                      label,
                    ),
                  ),
                );
              },
            }),
          ]}
        >
          {type === 'BOOLEAN' ? (
            <Select
              className={styles.valueInput}
              aria-label={label}
              autoFocus={autoFocus}
              placeholder={i18n('sqlEditor.parameter.valuePlaceholder')}
              options={[
                { value: 'true', label: 'true' },
                { value: 'false', label: 'false' },
              ]}
            />
          ) : (
            <Input
              className={styles.valueInput}
              aria-label={label}
              placeholder={
                type === 'NULL'
                  ? i18n('sqlEditor.parameter.type.NULL')
                  : type === 'STRING'
                    ? i18n('sqlEditor.parameter.emptyStringPlaceholder')
                    : i18n('sqlEditor.parameter.valuePlaceholder')
              }
              disabled={type === 'NULL'}
              autoFocus={autoFocus}
              onPressEnter={onSubmit}
            />
          )}
        </Form.Item>
      </div>
    </Form.Item>
  );
};

/**
 * Collects a type and a value for each `:name` or `?` parameter of the SQL about to run.
 */
const SqlParameterModal = ({ open, parameters, onRun, onCancel }: SqlParameterModalProps) => {
  const { styles } = useStyles();
  const [form] = Form.useForm<ParameterFormValues>();

  const handleFinish = (formValues: ParameterFormValues) => {
    const inputs: SqlParameterInput = {};
    parameters.forEach((parameter) => {
      inputs[parameter.key] = toParameterValue(formValues[parameter.key]);
    });
    onRun(buildSqlParameterValues(parameters, inputs));
  };

  return (
    <Modal
      open={open}
      title={i18n('sqlEditor.parameter.title')}
      width={520}
      okText={i18n('common.button.execute')}
      cancelText={i18n('common.button.cancel')}
      maskClosable={false}
      destroyOnClose
      onOk={() => form.submit()}
      onCancel={onCancel}
    >
      <Form
        form={form}
        layout="vertical"
        requiredMark={false}
        preserve={false}
        className={styles.form}
        onFinish={handleFinish}
      >
        {parameters.map((parameter, position) => (
          <ParameterField
            key={parameter.key}
            form={form}
            parameter={parameter}
            autoFocus={position === 0}
            onSubmit={() => form.submit()}
          />
        ))}
      </Form>
    </Modal>
  );
};

interface PendingPrompt {
  parameters: SqlParameterDescriptor[];
  resolve: (values: ISqlParameterValues | undefined) => void;
}

/**
 * Opens the parameter dialog and resolves with the entered values, or with
 * `undefined` when the user cancels.
 */
export function useSqlParameterPrompt() {
  const [pendingPrompt, setPendingPrompt] = useState<PendingPrompt>();
  const pendingPromptRef = useRef<PendingPrompt>();

  const settle = useCallback((values: ISqlParameterValues | undefined) => {
    const prompt = pendingPromptRef.current;
    pendingPromptRef.current = undefined;
    setPendingPrompt(undefined);
    prompt?.resolve(values);
  }, []);

  const promptSqlParameters = useCallback(
    (parameters: SqlParameterDescriptor[]) =>
      new Promise<ISqlParameterValues | undefined>((resolve) => {
        // A newer run request replaces a prompt that is still open.
        pendingPromptRef.current?.resolve(undefined);
        const prompt = { parameters, resolve };
        pendingPromptRef.current = prompt;
        setPendingPrompt(prompt);
      }),
    [],
  );

  useEffect(() => () => pendingPromptRef.current?.resolve(undefined), []);

  const parameterModal = (
    <SqlParameterModal
      open={!!pendingPrompt}
      parameters={pendingPrompt?.parameters ?? []}
      onRun={(values) => settle(values)}
      onCancel={() => settle(undefined)}
    />
  );

  return { promptSqlParameters, parameterModal };
}

export default SqlParameterModal;
