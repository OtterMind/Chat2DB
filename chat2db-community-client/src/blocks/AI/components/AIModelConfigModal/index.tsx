import { useEffect, useMemo, useState } from 'react';
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Switch, Tag } from 'antd';
import i18n from '@/i18n';
import feedback from '@/utils/feedback';
import {
  AIProvider,
  IAIModelConfigItem,
  IAIModelConfigSaveRequest,
  deleteAIModelConfig,
  listAIModelConfigs,
  saveAIModelConfig,
  testAIModelConfig,
} from '@/service/aiModelConfig';
import { resolveBaseUrlOnProviderChange, resolveProviderBaseUrl } from './modelConfigDefaults';
import { useStyles } from './style';
import { usePermission } from '@/hooks/usePermission';
import { clientRuntime } from '@client-runtime';

interface AIModelConfigModalProps {
  open: boolean;
  onClose: () => void;
  onChanged?: () => void;
}

const providerOptions = [
  { label: 'OpenAI', value: 'OPENAI' },
  { label: 'Claude', value: 'CLAUDE' },
  { label: 'Gemini', value: 'GEMINI' },
  { label: 'MiniMax', value: 'MINIMAX' },
];

const emptyFormValues: IAIModelConfigSaveRequest = {
  name: '',
  provider: 'OPENAI',
  model: '',
  apiKey: '',
  baseUrl: '',
  projectId: '',
  location: '',
  temperature: undefined,
  maxTokens: undefined,
  enabled: true,
  defaultConfig: false,
};

const toFormValues = (config?: Partial<IAIModelConfigSaveRequest>): IAIModelConfigSaveRequest => {
  const values = {
    ...emptyFormValues,
    ...config,
    apiKey: '',
  };
  return {
    ...values,
    baseUrl: resolveProviderBaseUrl(values.provider, values.baseUrl),
  };
};

export default function AIModelConfigModal({ open, onClose, onChanged }: AIModelConfigModalProps) {
  const { styles, cx } = useStyles();
  const { can } = usePermission();
  const canCreate = clientRuntime.usesLocalPersistence || can('ai', 'model', 'create');
  const canUpdate = clientRuntime.usesLocalPersistence || can('ai', 'model', 'update');
  const canDelete = clientRuntime.usesLocalPersistence || can('ai', 'model', 'delete');
  const [modal, modalContextHolder] = Modal.useModal();
  const [form] = Form.useForm<IAIModelConfigSaveRequest>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [configs, setConfigs] = useState<IAIModelConfigItem[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);

  const currentConfig = useMemo(() => configs.find((item) => item.id === editingId) || null, [configs, editingId]);
  const canSaveCurrent = editingId ? canUpdate : canCreate;

  const currentProvider = Form.useWatch('provider', form) as AIProvider | undefined;

  const resetForm = () => {
    setEditingId(null);
    form.setFieldsValue(emptyFormValues);
    form.resetFields();
    form.setFieldsValue(emptyFormValues);
  };

  const loadConfigs = async () => {
    setLoading(true);
    try {
      const list = await listAIModelConfigs();
      setConfigs(list);

      if (list.length > 0) {
        const activeId = editingId && list.some((item) => item.id === editingId) ? editingId : list[0].id;
        setEditingId(activeId);
        const activeConfig = list.find((item) => item.id === activeId);
        form.setFieldsValue(toFormValues(activeConfig));
      } else {
        resetForm();
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) {
      loadConfigs();
    }
  }, [open]);

  const handleSelectConfig = (config: IAIModelConfigItem) => {
    setEditingId(config.id);
    form.setFieldsValue(toFormValues(config));
  };

  const handleProviderChange = (provider: AIProvider) => {
    form.setFieldValue('baseUrl', resolveBaseUrlOnProviderChange(provider, form.getFieldValue('baseUrl')));
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const result = await saveAIModelConfig({
        ...values,
        id: editingId || undefined,
      });
      feedback.success(i18n('setting.modelConfig.saveSuccess'));
      await loadConfigs();
      if (result?.id) {
        setEditingId(result.id);
      }
      onChanged?.();
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    setSaving(true);
    try {
      await deleteAIModelConfig(id);
      feedback.success(i18n('setting.modelConfig.deleteSuccess'));
      await loadConfigs();
      onChanged?.();
    } finally {
      setSaving(false);
    }
  };

  const formatTestResult = (result?: Awaited<ReturnType<typeof testAIModelConfig>>) => {
    if (!result) {
      return '';
    }
    return [
      result.statusCode ? `HTTP ${result.statusCode}` : '',
      result.endpoint ? `${i18n('setting.modelConfig.testEndpoint')}: ${result.endpoint}` : '',
      result.message || '',
    ]
      .filter(Boolean)
      .join('\n');
  };

  const handleTestConnection = async () => {
    const values = await form.validateFields(['provider', 'model', 'baseUrl', 'temperature']);
    setTesting(true);
    try {
      const result = await testAIModelConfig({
        ...emptyFormValues,
        ...form.getFieldsValue(),
        ...values,
        id: editingId || undefined,
      });
      if (result?.success) {
        feedback.success(i18n('setting.modelConfig.testSuccess'));
        return;
      }
      modal.error({
        title: i18n('setting.modelConfig.testFailed'),
        content: <pre className={styles.testResult}>{formatTestResult(result)}</pre>,
        width: 680,
      });
    } catch (error: any) {
      modal.error({
        title: i18n('setting.modelConfig.testFailed'),
        content: (
          <pre className={styles.testResult}>{error?.errorMessage || error?.message || String(error)}</pre>
        ),
        width: 680,
      });
    } finally {
      setTesting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={i18n('setting.modelConfig.title')}
      onCancel={onClose}
      footer={null}
      width={980}
      destroyOnClose
      maskClosable={!saving}
    >
      {modalContextHolder}
      <div className={styles.layout}>
        <div className={styles.sidebar}>
          <div className={styles.sidebarHeader}>
            <div>{i18n('setting.modelConfig.listTitle')}</div>
            {canCreate ? (
              <Button size="small" onClick={resetForm}>
                {i18n('setting.modelConfig.new')}
              </Button>
            ) : null}
          </div>
          <div className={styles.list}>
            {loading
              ? null
              : configs.length
              ? configs.map((config) => (
                  <div
                    key={config.id}
                    className={cx(styles.listItem, editingId === config.id && styles.listItemActive)}
                    onClick={() => handleSelectConfig(config)}
                  >
                    <div className={styles.listItemTitle}>
                      <span>{config.name || config.model}</span>
                    </div>
                    <div className={styles.tagRow}>
                      {config.defaultConfig ? <Tag color="gold">{i18n('setting.modelConfig.default')}</Tag> : null}
                      {config.enabled ? (
                        <Tag color="green">{i18n('setting.modelConfig.enabled')}</Tag>
                      ) : (
                        <Tag>{i18n('setting.modelConfig.disabled')}</Tag>
                      )}
                    </div>
                    <div className={styles.listItemMeta}>
                      <div>{config.provider}</div>
                      <div>{config.model}</div>
                      {config.apiKeyMasked ? <div>{config.apiKeyMasked}</div> : null}
                    </div>
                    {canDelete ? (
                      <div className={styles.listItemActions}>
                        <Popconfirm
                          title={i18n('setting.modelConfig.deleteConfirm')}
                          onConfirm={(e) => {
                            e?.stopPropagation();
                            handleDelete(config.id);
                          }}
                          onCancel={(e) => e?.stopPropagation()}
                        >
                          <Button
                            size="small"
                            danger
                            onClick={(e) => {
                              e.stopPropagation();
                            }}
                          >
                            {i18n('common.button.delete')}
                          </Button>
                        </Popconfirm>
                      </div>
                    ) : null}
                  </div>
                ))
              : null}
          </div>
        </div>

        <div className={styles.right}>
          <Form
            form={form}
            layout="horizontal"
            initialValues={emptyFormValues}
            disabled={saving || !canSaveCurrent}
            labelCol={{ flex: '120px' }}
            wrapperCol={{ flex: 1 }}
            labelAlign="left"
          >
            <Form.Item
              name="name"
              label={i18n('setting.modelConfig.name')}
              rules={[{ required: true, message: i18n('setting.modelConfig.validation.name') }]}
            >
              <Input autoComplete="off" placeholder={i18n('setting.modelConfig.placeholder.name')} />
            </Form.Item>
            <Form.Item
              name="provider"
              label={i18n('setting.modelConfig.provider')}
              rules={[{ required: true, message: i18n('setting.modelConfig.validation.provider') }]}
            >
              <Select
                options={providerOptions}
                placeholder={i18n('setting.modelConfig.placeholder.provider')}
                onChange={handleProviderChange}
              />
            </Form.Item>
            <Form.Item
              name="model"
              label={i18n('setting.modelConfig.model')}
              rules={[{ required: true, message: i18n('setting.modelConfig.validation.model') }]}
            >
              <Input autoComplete="off" placeholder={i18n('setting.modelConfig.placeholder.model')} />
            </Form.Item>
            <Form.Item
              name="apiKey"
              label={i18n('setting.modelConfig.apiKey')}
              tooltip={currentConfig?.apiKeyMasked || undefined}
              rules={[
                {
                  required:
                    (currentProvider === 'OPENAI' ||
                      currentProvider === 'CLAUDE' ||
                      currentProvider === 'MINIMAX') &&
                    !currentConfig?.hasApiKey,
                  message: i18n('setting.modelConfig.validation.apiKey'),
                },
              ]}
            >
              <Input.Password
                autoComplete="new-password"
                placeholder={currentConfig?.apiKeyMasked || i18n('setting.modelConfig.placeholder.apiKey')}
                visibilityToggle={false}
              />
            </Form.Item>
            <Form.Item name="baseUrl" label={i18n('setting.modelConfig.baseUrl')}>
              <Input autoComplete="off" placeholder={i18n('setting.modelConfig.placeholder.baseUrl')} />
            </Form.Item>
            <Form.Item name="projectId" label={i18n('setting.modelConfig.projectId')}>
              <Input autoComplete="off" placeholder={i18n('setting.modelConfig.placeholder.projectId')} />
            </Form.Item>
            <Form.Item name="location" label={i18n('setting.modelConfig.location')}>
              <Input autoComplete="off" placeholder={i18n('setting.modelConfig.placeholder.location')} />
            </Form.Item>
            <Form.Item name="temperature" label={i18n('setting.modelConfig.temperature')}>
              <InputNumber
                style={{ width: '100%' }}
                min={0}
                max={2}
                step={0.1}
                placeholder={i18n('setting.modelConfig.placeholder.temperature')}
              />
            </Form.Item>
            <Form.Item name="maxTokens" label={i18n('setting.modelConfig.maxTokens')}>
              <InputNumber
                style={{ width: '100%' }}
                min={1}
                step={1}
                placeholder={i18n('setting.modelConfig.placeholder.maxTokens')}
              />
            </Form.Item>
            <div className={styles.switchRow}>
              <div className={styles.switchField}>
                <div className={styles.switchLabel}>{i18n('setting.modelConfig.enabled')}</div>
                <Form.Item name="enabled" valuePropName="checked" noStyle>
                  <Switch aria-label={i18n('setting.modelConfig.enabled')} />
                </Form.Item>
              </div>
              <div className={styles.switchField}>
                <div className={styles.switchLabel}>{i18n('setting.modelConfig.default')}</div>
                <Form.Item name="defaultConfig" valuePropName="checked" noStyle>
                  <Switch aria-label={i18n('setting.modelConfig.default')} />
                </Form.Item>
              </div>
            </div>
          </Form>

          <div className={styles.formActions}>
            <Button onClick={onClose}>{i18n('common.button.cancel')}</Button>
            {canSaveCurrent ? (
              <Button loading={testing} onClick={handleTestConnection}>
                {i18n('setting.modelConfig.testConnection')}
              </Button>
            ) : null}
            {canSaveCurrent ? (
              <Button type="primary" loading={saving} onClick={handleSave}>
                {i18n('common.button.save')}
              </Button>
            ) : null}
          </div>
        </div>
      </div>
    </Modal>
  );
}
