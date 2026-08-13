import i18n from '@/i18n';
import {
  type AIProvider,
  type IAIModelConfigItem,
  type IAIModelConfigSaveRequest,
  deleteAIModelConfig,
  listAIModelConfigs,
  saveAIModelConfig,
  testAIModelConfig,
} from '@/service/aiModelConfig';
import feedback from '@/utils/feedback';
import { Alert, Button, Empty, Form, Input, InputNumber, Popconfirm, Segmented, Switch, Tag } from 'antd';
import { Plus, Trash2 } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useStyles } from './style';

const emptyValues: IAIModelConfigSaveRequest = {
  name: '', provider: 'OPENAI', model: '', apiKey: '', baseUrl: '', enabled: true, defaultConfig: false,
};

const MODEL_CONFIG_CHANGED_EVENT = 'chat2db:model-config-changed';

export default function ModelConfigSetting() {
  const { styles, cx } = useStyles();
  const [form] = Form.useForm<IAIModelConfigSaveRequest>();
  const [configs, setConfigs] = useState<IAIModelConfigItem[]>([]);
  const [editingId, setEditingId] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [testing, setTesting] = useState(false);
  const current = useMemo(() => configs.find((item) => item.id === editingId), [configs, editingId]);
  const provider = Form.useWatch('provider', form) as AIProvider | undefined;

  async function load(preferredId?: string) {
    const list = await listAIModelConfigs();
    setConfigs(list || []);
    const next = list.find((item) => item.id === preferredId) || list[0];
    if (next) select(next);
    else createNew();
  }

  useEffect(() => { void load(); }, []);

  function select(config: IAIModelConfigItem) {
    setEditingId(config.id);
    form.setFieldsValue({ ...emptyValues, ...config, apiKey: '' });
  }

  function createNew() {
    setEditingId(undefined);
    form.resetFields();
    form.setFieldsValue(emptyValues);
  }

  async function save() {
    const values = await form.validateFields();
    setLoading(true);
    try {
      const saved = await saveAIModelConfig({ ...values, id: editingId });
      feedback.success(i18n('setting.modelConfig.saveSuccess'));
      await load(saved.id);
      window.dispatchEvent(new Event(MODEL_CONFIG_CHANGED_EVENT));
    } finally { setLoading(false); }
  }

  async function test() {
    const values = await form.validateFields();
    setTesting(true);
    try {
      const result = await testAIModelConfig({ ...values, id: editingId });
      if (result?.success) feedback.success(i18n('setting.modelConfig.testSuccess'));
      else feedback.error(result?.message || i18n('setting.modelConfig.testFailed'));
    } finally { setTesting(false); }
  }

  async function remove(id: string) {
    await deleteAIModelConfig(id);
    feedback.success(i18n('setting.modelConfig.deleteSuccess'));
    await load();
    window.dispatchEvent(new Event(MODEL_CONFIG_CHANGED_EVENT));
  }

  return (
    <div className={styles.layout}>
      <aside className={styles.listPane}>
        <div className={styles.listHeader}>
          <strong>{i18n('setting.modelConfig.listTitle')}</strong>
          <Button size="small" icon={<Plus size={14} />} onClick={createNew}>{i18n('setting.modelConfig.new')}</Button>
        </div>
        <div className={styles.list}>
          {configs.map((config) => (
            <button
              type="button"
              key={config.id}
              className={cx(styles.item, editingId === config.id && styles.itemActive)}
              onClick={() => select(config)}
            >
              <span className={styles.itemTop}><strong>{config.name}</strong>{config.defaultConfig && <Tag color="blue">{i18n('setting.modelConfig.default')}</Tag>}</span>
              <span>{config.provider === 'CLAUDE' ? 'Anthropic' : 'OpenAI'} · {config.model}</span>
            </button>
          ))}
          {!configs.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        </div>
      </aside>
      <main className={styles.editor}>
        <Alert type="info" showIcon message={i18n('setting.modelConfig.protocolHint')} />
        <Form form={form} layout="vertical" initialValues={emptyValues} disabled={loading}>
          <Form.Item
            name="provider"
            data-setting-search-id="modelConfig.protocol"
            label={i18n('setting.modelConfig.protocol')}
            rules={[{ required: true }]}
          >
            <Segmented
              block
              options={[
                { value: 'OPENAI', label: i18n('setting.modelConfig.openAiProtocol') },
                { value: 'CLAUDE', label: i18n('setting.modelConfig.anthropicProtocol') },
              ]}
            />
          </Form.Item>
          <div className={styles.row}>
            <Form.Item name="name" label={i18n('setting.modelConfig.name')} rules={[{ required: true }]}>
              <Input placeholder={i18n('setting.modelConfig.placeholder.name')} />
            </Form.Item>
            <Form.Item name="model" label={i18n('setting.modelConfig.model')} rules={[{ required: true }]}>
              <Input placeholder={provider === 'CLAUDE' ? 'claude-sonnet-4-5' : 'gpt-5'} />
            </Form.Item>
          </div>
          <Form.Item
            name="apiKey"
            label={i18n('setting.modelConfig.apiKey')}
            rules={[{ required: !current?.hasApiKey, message: i18n('setting.modelConfig.validation.apiKey') }]}
          >
            <Input.Password
              autoComplete="new-password"
              placeholder={current?.apiKeyMasked || i18n('setting.modelConfig.placeholder.apiKey')}
            />
          </Form.Item>
          <Form.Item name="baseUrl" data-setting-search-id="modelConfig.endpoint" label={i18n('setting.modelConfig.baseUrl')}>
            <Input placeholder={provider === 'CLAUDE' ? 'https://api.anthropic.com' : 'https://api.openai.com'} />
          </Form.Item>
          <div className={styles.row}>
            <Form.Item name="temperature" label={i18n('setting.modelConfig.temperature')}>
              <InputNumber min={0} max={2} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="maxTokens" label={i18n('setting.modelConfig.maxTokens')}>
              <InputNumber min={1} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </div>
          <div className={styles.switches}>
            <Form.Item name="enabled" valuePropName="checked" label={i18n('setting.modelConfig.enabled')}><Switch /></Form.Item>
            <Form.Item name="defaultConfig" valuePropName="checked" label={i18n('setting.modelConfig.default')}><Switch /></Form.Item>
          </div>
        </Form>
        <div className={styles.actions}>
          {editingId && <Popconfirm title={i18n('setting.modelConfig.deleteConfirm')} onConfirm={() => void remove(editingId)}><Button danger icon={<Trash2 size={14} />}>{i18n('common.button.delete')}</Button></Popconfirm>}
          <span />
          <Button loading={testing} onClick={() => void test()}>{i18n('setting.modelConfig.testConnection')}</Button>
          <Button type="primary" loading={loading} onClick={() => void save()}>{i18n('common.button.save')}</Button>
        </div>
      </main>
    </div>
  );
}
