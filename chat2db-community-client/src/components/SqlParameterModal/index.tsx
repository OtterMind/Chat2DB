import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Form, Input, Modal, Select, Tooltip, type FormInstance } from 'antd';
import { staticMessage } from '@chat2db/ui';
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

/**
 * What the dialog resolves with: the entered values, `RUN_AS_WRITTEN` to run the
 * SQL without binding (for example when `?` is PostgreSQL's jsonb operator), or
 * `undefined` when the user cancels.
 */
export const RUN_AS_WRITTEN = 'runAsWritten' as const;
export type SqlParameterPromptResult = ISqlParameterValues | typeof RUN_AS_WRITTEN | undefined;

interface SqlParameterModalProps {
  open: boolean;
  parameters: SqlParameterDescriptor[];
  onRun: (values: ISqlParameterValues) => void;
  onRunAsWritten: () => void;
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
  // Send exactly what is shown; a number with spaces is reported, not silently trimmed.
  return { type, value: field?.value ?? '' };
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
const SqlParameterModal = ({ open, parameters, onRun, onRunAsWritten, onCancel }: SqlParameterModalProps) => {
  const { styles } = useStyles();
  const [form] = Form.useForm<ParameterFormValues>();

  const handleFinish = (formValues: ParameterFormValues) => {
    const inputs: SqlParameterInput = {};
    parameters.forEach((parameter) => {
      inputs[parameter.key] = toParameterValue(formValues[parameter.key]);
    });
    let values: ISqlParameterValues;
    try {
      values = buildSqlParameterValues(parameters, inputs);
    } catch {
      // The field validators run first, so this only guards against them drifting apart.
      staticMessage.error(i18n('sqlEditor.parameter.invalidValues'));
      return;
    }
    onRun(values);
  };

  return (
    <Modal
      open={open}
      title={i18n('sqlEditor.parameter.title')}
      width={520}
      maskClosable={false}
      destroyOnClose
      onCancel={onCancel}
      footer={[
        <Button key="cancel" onClick={onCancel}>
          {i18n('common.button.cancel')}
        </Button>,
        <Tooltip key="asWritten" title={i18n('sqlEditor.parameter.runAsWrittenHint')}>
          <Button onClick={onRunAsWritten}>{i18n('sqlEditor.parameter.runAsWritten')}</Button>
        </Tooltip>,
        <Button key="run" type="primary" onClick={() => form.submit()}>
          {i18n('common.button.execute')}
        </Button>,
      ]}
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
  resolve: (result: SqlParameterPromptResult) => void;
}

/**
 * Opens the parameter dialog and resolves with the entered values,
 * `RUN_AS_WRITTEN`, or `undefined` when the user cancels.
 */
export function useSqlParameterPrompt() {
  const [pendingPrompt, setPendingPrompt] = useState<PendingPrompt>();
  const pendingPromptRef = useRef<PendingPrompt>();

  const settle = useCallback((result: SqlParameterPromptResult) => {
    const prompt = pendingPromptRef.current;
    pendingPromptRef.current = undefined;
    setPendingPrompt(undefined);
    prompt?.resolve(result);
  }, []);

  const promptSqlParameters = useCallback(
    (parameters: SqlParameterDescriptor[]) =>
      new Promise<SqlParameterPromptResult>((resolve) => {
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
      onRunAsWritten={() => settle(RUN_AS_WRITTEN)}
      onCancel={() => settle(undefined)}
    />
  );

  return { promptSqlParameters, parameterModal };
}

export default SqlParameterModal;
