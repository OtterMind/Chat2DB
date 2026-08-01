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
import SubscriptionAccountPanel from '@/blocks/AI/subscription/SubscriptionAccountPanel';
import { useSubscriptionAiStore } from '@/store/aiSubscription';
import { isCommunityEnv, isPackagedJcefDesktop } from '@/utils/env';
import { useStyles } from './style';

interface AIModelConfigModalProps {
  open: boolean;
  onClose: () => void;
  onChanged?: () => void;
}

const providerOptions = [
  { label: 'OpenAI', value: 'OPENAI' },
  { label: 'Claude', value: 'CLAUDE' },
  { label: 'Gemini', value: 'GEMINI' },
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

/**
 * Unified model config (product B, layout A):
 * 1) Multi-provider subscription block
 * 2) Full API Key list + form
 */
export default function AIModelConfigModal({ open, onClose, onChanged }: AIModelConfigModalProps) {
  const { styles, cx } = useStyles();
  const [modal, modalContextHolder] = Modal.useModal();
  const [form] = Form.useForm<IAIModelConfigSaveRequest>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [configs, setConfigs] = useState<IAIModelConfigItem[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  /** True when user clicked 新建 or list is empty and form is open for create. */
  const [isCreating, setIsCreating] = useState(false);
  const subscriptionSnapshots = useSubscriptionAiStore((state) => state.snapshots);

  const currentConfig = useMemo(() => configs.find((item) => item.id === editingId) || null, [configs, editingId]);
  const showForm = isCreating || !!editingId;

  const currentProvider = Form.useWatch('provider', form) as AIProvider | undefined;

  const resetForm = (asCreate: boolean) => {
    setEditingId(null);
    setIsCreating(asCreate);
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
        setIsCreating(false);
        const activeConfig = list.find((item) => item.id === activeId);
        form.setFieldsValue({
          ...emptyFormValues,
          ...activeConfig,
          apiKey: '',
        });
      } else {
        resetForm(false);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) {
      void loadConfigs();
    }
  }, [open]);

  useEffect(() => {
    if (!open || !subscriptionSnapshots.length) {
      return;
    }
    onChanged?.();
  }, [open, subscriptionSnapshots.length, onChanged]);

  const handleSelectConfig = (config: IAIModelConfigItem) => {
    setEditingId(config.id);
    setIsCreating(false);
    form.setFieldsValue({
      ...emptyFormValues,
      ...config,
      apiKey: '',
    });
  };

  const handleNew = () => {
    resetForm(true);
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
        setIsCreating(false);
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
        content: error?.errorMessage || error?.message || String(error),
      });
    } finally {
      setTesting(false);
    }
  };

  const showSubscriptionSection = isCommunityEnv && isPackagedJcefDesktop();

  return (
    <Modal
      open={open}
      title={i18n('setting.modelConfig.title')}
      onCancel={onClose}
      footer={null}
      width={1000}
      destroyOnClose
      maskClosable={!saving}
    >
      {modalContextHolder}
      <div className={styles.layout}>
        {showSubscriptionSection ? (
          <section className={styles.section}>
            <div className={styles.sectionLabel}>{i18n('ai.subscription.config.sectionSubscribe')}</div>
            <SubscriptionAccountPanel />
          </section>
        ) : null}

        <section className={styles.section}>
          <div className={styles.sectionLabel}>{i18n('ai.subscription.config.sectionApiKey')}</div>
          <div className={styles.apiKeyLayout}>
            <div className={styles.sidebar}>
              <div className={styles.sidebarHeader}>
                <div>{i18n('setting.modelConfig.apiKeyModelsTitle')}</div>
                <Button size="small" type="primary" onClick={handleNew}>
                  {i18n('setting.modelConfig.new')}
                </Button>
              </div>
              <div className={styles.list}>
                {loading
                  ? null
                  : configs.length
                    ? configs.map((config) => (
                        <div
                          key={config.id}
                          className={cx(styles.listItem, editingId === config.id && !isCreating && styles.listItemActive)}
                          onClick={() => handleSelectConfig(config)}
                        >
                          <div className={styles.listItemTitle}>
                            <span>{config.name || config.model}</span>
                          </div>
                          <div className={styles.tagRow}>
                            {config.defaultConfig ? (
                              <Tag color="gold">{i18n('setting.modelConfig.default')}</Tag>
                            ) : null}
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
                          <div className={styles.listItemActions}>
                            <Popconfirm
                              title={i18n('setting.modelConfig.deleteConfirm')}
                              onConfirm={(e) => {
                                e?.stopPropagation();
                                void handleDelete(config.id);
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
                        </div>
                      ))
                    : (
                        <div className={styles.sidebarEmpty}>{i18n('ai.subscription.config.apiKeyListEmpty')}</div>
                      )}
              </div>
            </div>

            <div className={styles.right}>
              {!showForm ? (
                <div className={styles.empty}>
                  <div>{i18n('ai.subscription.config.apiKeyEmptyTitle')}</div>
                  <div className={styles.emptyHint}>{i18n('ai.subscription.config.apiKeyEmptyHint')}</div>
                  <Button type="primary" style={{ marginTop: 12 }} onClick={handleNew}>
                    {i18n('setting.modelConfig.new')}
                  </Button>
                </div>
              ) : (
                <>
                  <div className={styles.formTitle}>
                    {isCreating
                      ? i18n('ai.subscription.config.apiKeyCreateTitle')
                      : i18n('ai.subscription.config.apiKeyEditTitle')}
                  </div>
                  <Form
                    form={form}
                    layout="horizontal"
                    initialValues={emptyFormValues}
                    disabled={saving}
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
                            (currentProvider === 'OPENAI' || currentProvider === 'CLAUDE') &&
                            !currentConfig?.hasApiKey,
                          message: i18n('setting.modelConfig.validation.apiKey'),
                        },
                      ]}
                    >
                      <Input.Password
                        autoComplete="new-password"
                        placeholder={
                          currentConfig?.apiKeyMasked || i18n('setting.modelConfig.placeholder.apiKey')
                        }
                        visibilityToggle={false}
                      />
                    </Form.Item>
                    <Form.Item name="baseUrl" label={i18n('setting.modelConfig.baseUrl')}>
                      <Input autoComplete="off" placeholder={i18n('setting.modelConfig.placeholder.baseUrl')} />
                    </Form.Item>
                    <Form.Item name="projectId" label={i18n('setting.modelConfig.projectId')}>
                      <Input
                        autoComplete="off"
                        placeholder={i18n('setting.modelConfig.placeholder.projectId')}
                      />
                    </Form.Item>
                    <Form.Item name="location" label={i18n('setting.modelConfig.location')}>
                      <Input
                        autoComplete="off"
                        placeholder={i18n('setting.modelConfig.placeholder.location')}
                      />
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
                    {!isCreating && editingId ? (
                      <Popconfirm
                        title={i18n('setting.modelConfig.deleteConfirm')}
                        onConfirm={() => void handleDelete(editingId)}
                      >
                        <Button danger loading={saving}>
                          {i18n('common.button.delete')}
                        </Button>
                      </Popconfirm>
                    ) : null}
                    <Button onClick={onClose}>{i18n('common.button.cancel')}</Button>
                    <Button loading={testing} onClick={() => void handleTestConnection()}>
                      {i18n('setting.modelConfig.testConnection')}
                    </Button>
                    <Button type="primary" loading={saving} onClick={() => void handleSave()}>
                      {i18n('common.button.save')}
                    </Button>
                  </div>
                </>
              )}
            </div>
          </div>
        </section>
      </div>
    </Modal>
  );
}
