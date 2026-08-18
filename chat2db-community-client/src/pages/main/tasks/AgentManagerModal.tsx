import i18n from '@/i18n';
import { type IAIModelConfigItem, listAIModelConfigs } from '@/service/aiModelConfig';
import agentService, { type AgentDefinition, type AgentRuntimeOption } from '@/service/agent';
import connectionService from '@/service/connection';
import type { IConnectionDetails } from '@/typings';
import feedback from '@/utils/feedback';
import { Alert, Button, Checkbox, Form, Input, Modal, Popconfirm, Segmented, Select, Space, Tag, Upload } from 'antd';
import { Bot, ChevronRight, Pencil, Plus, Search, ShieldCheck, Sparkles, Trash2, UploadCloud } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';

import AgentDataScopeEditor from './AgentDataScopeEditor';
import ApprovalModeTag from './ApprovalModeTag';
import AgentOutputContractEditor from './AgentOutputContractEditor';
import AgentRuntimePicker from './AgentRuntimePicker';
import {
  agentRuntimeAvatar,
  CHAT2DB_AGENT_AVATAR,
  isDefaultAgentAvatar,
  runtimeProviderName,
} from './RuntimeProviderLogo';
import { AgentAvatar, CapabilityChips, RuntimeBadge } from './TaskPrimitives';
import { useStyles } from './style';
import { readAgentAvatar } from './agentAvatar';
import { parseAgentOutputContract, serializeAgentOutputContract } from './agentOutputContract';
import { dataSourceDisplayName } from './taskDataSource';

interface Props {
  open: boolean;
  agents: AgentDefinition[];
  onClose: () => void;
  onChanged: (agent: AgentDefinition, removed?: boolean) => void;
}

type ManagerView = 'list' | 'edit';

const capabilities = ['METADATA_READ', 'DATA_READ', 'DATA_WRITE', 'DDL', 'EXPORT', 'IMPORT'];

export default function AgentManagerModal({ open, agents, onClose, onChanged }: Props) {
  const { styles } = useStyles();
  const [form] = Form.useForm();
  const [view, setView] = useState<ManagerView>('list');
  const [query, setQuery] = useState('');
  const [selectedAgentId, setSelectedAgentId] = useState<string>();
  const [dataSources, setDataSources] = useState<IConnectionDetails[]>([]);
  const [modelConfigs, setModelConfigs] = useState<IAIModelConfigItem[]>([]);
  const [runtimeOptions, setRuntimeOptions] = useState<AgentRuntimeOption[]>([]);
  const [runtimeOptionsLoading, setRuntimeOptionsLoading] = useState(false);
  const [runtimeOptionsError, setRuntimeOptionsError] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [editingAgent, setEditingAgent] = useState<AgentDefinition>();
  const [avatarCustomized, setAvatarCustomized] = useState(false);
  const [outputContractExtras, setOutputContractExtras] = useState<Record<string, unknown>>({});
  const avatar = Form.useWatch('avatar', form);
  const agentName = Form.useWatch('name', form);
  const runtimeType = Form.useWatch('runtimeType', form);
  const runtimeProfileId = Form.useWatch('runtimeProfileId', form);

  const refreshRuntimeOptions = useCallback(async () => {
    setRuntimeOptionsLoading(true);
    try {
      setRuntimeOptions(await agentService.listRuntimeOptions());
      setRuntimeOptionsError(false);
    } catch {
      setRuntimeOptionsError(true);
    } finally {
      setRuntimeOptionsLoading(false);
    }
  }, []);

  const filteredAgents = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return agents;
    return agents.filter(
      (agent) => agent.name.toLowerCase().includes(normalized) || agent.description?.toLowerCase().includes(normalized),
    );
  }, [agents, query]);
  const selectedAgent = agents.find((agent) => agent.id === selectedAgentId) || filteredAgents[0];
  const selectedRuntime = runtimeOptions.find((option) => option.profileId === selectedAgent?.runtimeProfileId);

  useEffect(() => {
    if (!open) return;
    void refreshRuntimeOptions();
    void Promise.all([connectionService.getList({ pageNo: 1, pageSize: 500 }), listAIModelConfigs()])
      .then(([connectionResult, models]) => {
        setDataSources(connectionResult.data || []);
        setModelConfigs((models || []).filter((model) => model.enabled !== false));
      })
      .catch(() => {
        setDataSources([]);
        setModelConfigs([]);
      });
  }, [open, refreshRuntimeOptions]);

  useEffect(() => {
    if (!open || runtimeOptions.length || runtimeOptionsError) return;
    const timer = window.setInterval(() => void refreshRuntimeOptions(), 3000);
    return () => window.clearInterval(timer);
  }, [open, refreshRuntimeOptions, runtimeOptions.length, runtimeOptionsError]);

  useEffect(() => {
    if (open && agents.length && !selectedAgentId) setSelectedAgentId(agents[0].id);
  }, [agents, open, selectedAgentId]);

  const save = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const payload = {
        avatar: values.avatar,
        name: values.name,
        description: values.description,
        runtimeType: values.runtimeType,
        runtimeProfileId: values.runtimeProfileId,
        modelConfigId: values.modelConfigId,
        systemPrompt: values.systemPrompt,
        capabilities: values.capabilities,
        dataScopes: values.dataScopes.map((scope) => ({
          dataSourceId: scope.dataSourceId,
          databaseName: scope.databaseName,
          schemaName: scope.schemaName,
          tableNames: scope.accessLevel === 'TABLES' ? scope.tableNames || [] : [],
          excludedTableNames: scope.excludedTableNames || [],
          maxRows: scope.maxRows,
          timeoutSeconds: scope.timeoutSeconds,
          approvalMode: scope.approvalMode,
          allowProduction: !!scope.allowProduction,
        })),
        outputContract: serializeAgentOutputContract(
          values.outputRequirements,
          values.outputRequiredSections,
          outputContractExtras,
        ),
      };
      const agent = editingAgent
        ? await agentService.updateAgent({
            ...payload,
            agentId: editingAgent.id,
            expectedRevision: editingAgent.revision,
          })
        : await agentService.createAgent(payload);
      onChanged(agent);
      setSelectedAgentId(agent.id);
      setView('list');
      form.resetFields();
      setEditingAgent(undefined);
      feedback.success(i18n(editingAgent ? 'task.agent.updateSuccess' : 'task.agent.createSuccess'));
    } finally {
      setSubmitting(false);
    }
  };

  const openEditor = (agent?: AgentDefinition) => {
    const outputContract = parseAgentOutputContract(agent?.outputContract);
    setEditingAgent(agent);
    setAvatarCustomized(!!agent?.avatar && !isDefaultAgentAvatar(agent.avatar));
    setOutputContractExtras(outputContract.extras);
    setView('edit');
    form.setFieldsValue(agent ? {
      ...agent,
      dataScopes: agent.dataScopes.map((scope) => ({ ...scope, accessLevel: scope.tableNames.length ? 'TABLES' : 'NAMESPACE' })),
      outputRequirements: outputContract.outputRequirements,
      outputRequiredSections: outputContract.outputRequiredSections,
    } : {
      avatar: CHAT2DB_AGENT_AVATAR,
      runtimeType: 'EMBEDDED_SPRING_AI', capabilities: ['METADATA_READ', 'DATA_READ'],
      dataScopes: [{ accessLevel: 'NAMESPACE', maxRows: 200, timeoutSeconds: 60, approvalMode: 'RISK_BASED' }],
      outputRequirements: outputContract.outputRequirements,
      outputRequiredSections: outputContract.outputRequiredSections,
    });
  };

  const archive = async (agent: AgentDefinition) => {
    setSubmitting(true);
    try {
      const archived = await agentService.archiveAgent({ agentId: agent.id, expectedRevision: agent.revision });
      onChanged(archived, true);
      setSelectedAgentId(undefined);
      feedback.success(i18n('task.agent.deleteSuccess'));
    } finally { setSubmitting(false); }
  };

  return (
    <Modal
      width="min(1120px, 94vw)"
      open={open}
      title={null}
      onCancel={onClose}
      footer={null}
      destroyOnClose
      className={styles.managerModal}
    >
      <header className={styles.managerHeader}>
        <div>
          <div className={styles.managerTitle}>
            <Bot size={19} />
            <h2>{i18n('task.agent.manage')}</h2>
            <span>{agents.length}</span>
          </div>
          <p>{i18n('task.agent.manageHint')}</p>
        </div>
        <Space>
          <Segmented<ManagerView>
            value={view}
            onChange={(nextView) => {
              if (nextView === 'edit') openEditor(editingAgent);
              else setView('list');
            }}
            options={[
              { value: 'list', label: i18n('task.agent.list') },
              { value: 'edit', label: editingAgent ? i18n('task.agent.edit') : i18n('task.agent.create') },
            ]}
          />
          {view === 'list' && (
            <Button type="primary" icon={<Plus size={15} />} onClick={() => openEditor()}>
              {i18n('task.agent.create')}
            </Button>
          )}
        </Space>
      </header>

      {view === 'list' ? (
        <div className={styles.agentManagerGrid}>
          <section className={styles.agentListPane}>
            <Input
              allowClear
              prefix={<Search size={14} />}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={i18n('task.agent.search')}
            />
            <div className={styles.agentRows}>
              {filteredAgents.map((agent) => (
                <button
                  key={agent.id}
                  type="button"
                  className={`${styles.agentRow} ${selectedAgent?.id === agent.id ? styles.agentRowSelected : ''}`}
                  onClick={() => setSelectedAgentId(agent.id)}
                >
                  <AgentAvatar agent={agent} size={34} />
                  <div className={styles.agentRowCopy}>
                    <strong>{agent.name}</strong>
                    <span>{agent.description || i18n('task.agent.noDescription')}</span>
                  </div>
                  <RuntimeBadge agent={agent} compact />
                  <ChevronRight size={15} />
                </button>
              ))}
            </div>
            {!filteredAgents.length && <Alert type="info" showIcon message={i18n('task.agent.empty')} />}
          </section>

          <aside className={styles.agentInspector}>
            {selectedAgent ? (
              <>
                <div className={styles.agentInspectorHero}>
                  <AgentAvatar agent={selectedAgent} size={52} />
                  <div>
                    <h3>{selectedAgent.name}</h3>
                    <p>{selectedAgent.description || i18n('task.agent.noDescription')}</p>
                  </div>
                  <Tag bordered={false} color={selectedAgent.status === 'ACTIVE' ? 'success' : 'default'}>
                    {selectedAgent.status}
                  </Tag>
                </div>
                <div className={styles.agentInspectorActions}>
                  <Button icon={<Pencil size={14} />} onClick={() => openEditor(selectedAgent)}>{i18n('task.agent.edit')}</Button>
                  <Popconfirm title={i18n('task.agent.deleteConfirm')} onConfirm={() => void archive(selectedAgent)}>
                    <Button danger icon={<Trash2 size={14} />} loading={submitting}>{i18n('common.button.delete')}</Button>
                  </Popconfirm>
                </div>
                <div className={styles.inspectorSection}>
                  <h4>{i18n('task.agent.runtime')}</h4>
                  <div className={styles.runtimeSummary}>
                    <RuntimeBadge agent={selectedAgent} />
                    <span>
                      {selectedAgent.runtimeType === 'EXTERNAL_AGENT'
                        ? selectedRuntime ? runtimeProviderName(selectedRuntime.provider) : i18n('task.runtime.external')
                        : 'Spring AI'}
                    </span>
                  </div>
                </div>
                <div className={styles.inspectorSection}>
                  <h4>{i18n('task.agent.capabilities')}</h4>
                  <div className={styles.chipRow}>
                    <CapabilityChips values={selectedAgent.capabilities} limit={8} />
                  </div>
                </div>
                <div className={styles.inspectorSection}>
                  <h4>
                    <ShieldCheck size={14} />
                    {i18n('task.scope.title')}
                  </h4>
                  <div className={styles.inspectorScopes}>
                    {selectedAgent.dataScopes.map((scope, index) => {
                      return (
                        <div key={`${scope.dataSourceId}-${index}`}>
                          <strong>
                            {dataSourceDisplayName(
                              scope.dataSourceId,
                              dataSources,
                              i18n('task.scope.datasourceUnavailable', scope.dataSourceId),
                            )}
                          </strong>
                          <span>{[scope.databaseName || '*', scope.schemaName || '*'].join(' / ')}</span>
                          <small>
                            {scope.tableNames.length
                              ? i18n('task.scope.tableCount', scope.tableNames.length)
                              : i18n('task.scope.namespaceWide')}
                          </small>
                          <ApprovalModeTag mode={scope.approvalMode} />
                        </div>
                      );
                    })}
                  </div>
                </div>
              </>
            ) : (
              <Alert type="info" showIcon message={i18n('task.agent.empty')} />
            )}
          </aside>
        </div>
      ) : (
        <Form
          form={form}
          layout="vertical"
          className={styles.agentStudio}
        >
          <div className={styles.agentStudioLayout}>
            <div className={styles.agentStudioMain}>
              <section className={styles.studioSection}>
                <div className={styles.studioSectionHeader}>
                  <div>
                    <h3>
                      <Sparkles size={16} />
                      {i18n('task.agent.identity')}
                    </h3>
                    <p>{i18n('task.agent.identityHint')}</p>
                  </div>
                </div>
                <div className={styles.avatarEditor}>
                    <AgentAvatar agent={{ ...editingAgent, avatar, name: agentName || 'Agent' } as AgentDefinition} size={54} />
                    <div>
                      <Form.Item name="avatar" noStyle><Input type="hidden" /></Form.Item>
                      <Upload
                        accept="image/*"
                        showUploadList={false}
                        beforeUpload={async (file) => {
                          try {
                            form.setFieldValue('avatar', await readAgentAvatar(file));
                            setAvatarCustomized(true);
                          }
                          catch (error: any) { feedback.error(error.message); }
                          return false;
                        }}
                      >
                        <Button icon={<UploadCloud size={14} />}>{i18n('task.agent.uploadAvatar')}</Button>
                      </Upload>
                      {avatarCustomized && (
                        <Button
                          type="link"
                          danger
                          onClick={() => {
                            form.setFieldValue(
                              'avatar',
                              agentRuntimeAvatar(runtimeType, runtimeProfileId, runtimeOptions),
                            );
                            setAvatarCustomized(false);
                          }}
                        >
                          {i18n('task.agent.removeAvatar')}
                        </Button>
                      )}
                    </div>
                  </div>
                <Form.Item name="name" label={i18n('task.agent.name')} rules={[{ required: true, max: 128 }]}>
                  <Input autoFocus />
                </Form.Item>
                <Form.Item name="runtimeType" hidden rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item
                  name="runtimeProfileId"
                  hidden
                  rules={[{
                    validator: (_, value) => runtimeType !== 'EXTERNAL_AGENT' || value
                      ? Promise.resolve()
                      : Promise.reject(new Error(i18n('task.agent.runtimeRequired'))),
                  }]}
                >
                  <Input />
                </Form.Item>
                <AgentRuntimePicker
                  runtimeType={runtimeType}
                  runtimeProfileId={runtimeProfileId}
                  options={runtimeOptions}
                  loading={runtimeOptionsLoading}
                  error={runtimeOptionsError}
                  onRefresh={() => void refreshRuntimeOptions()}
                  onChange={(nextRuntimeType, profileId) => {
                    form.setFieldsValue({
                      runtimeType: nextRuntimeType,
                      runtimeProfileId: profileId,
                      ...(!avatarCustomized
                        ? { avatar: agentRuntimeAvatar(nextRuntimeType, profileId, runtimeOptions) }
                        : {}),
                    });
                    void form.validateFields(['runtimeProfileId']);
                  }}
                />
                {runtimeType !== 'EXTERNAL_AGENT' && (
                  <Form.Item
                    name="modelConfigId"
                    label={i18n('task.agent.modelConfig')}
                    extra={i18n('task.agent.modelConfigHint')}
                  >
                    <Select
                      allowClear
                      placeholder={i18n('task.agent.useDefaultModel')}
                      options={modelConfigs.map((model) => ({
                        value: model.id,
                        label: `${model.name} · ${model.model}${
                          model.defaultConfig ? ` · ${i18n('setting.modelConfig.default')}` : ''
                        }`,
                      }))}
                    />
                  </Form.Item>
                )}
                <Form.Item name="description" label={i18n('task.field.description')}>
                  <Input.TextArea rows={2} maxLength={500} showCount />
                </Form.Item>
                <Form.Item name="systemPrompt" label={i18n('task.agent.instructions')}>
                  <Input.TextArea
                    rows={6}
                    maxLength={12000}
                    showCount
                    placeholder={i18n('task.agent.instructionsPlaceholder')}
                  />
                </Form.Item>
              </section>
              <AgentDataScopeEditor form={form} dataSources={dataSources} />
            </div>
            <aside className={styles.agentStudioAside}>
              <section className={styles.studioSection}>
                <div className={styles.studioSectionHeader}>
                  <div>
                    <h3>{i18n('task.agent.capabilities')}</h3>
                    <p>{i18n('task.agent.capabilitiesHint')}</p>
                  </div>
                </div>
                <Form.Item name="capabilities" rules={[{ required: true }]}>
                  <Checkbox.Group
                    className={styles.capabilityGrid}
                    options={capabilities.map((value) => ({
                      value,
                      label: (
                        <span className={styles.capabilityLabel}>
                          <code>{value}</code>
                          <small>
                            {i18n(`task.agent.capability.${value.toLowerCase()}` as Parameters<typeof i18n>[0])}
                          </small>
                        </span>
                      ),
                    }))}
                  />
                </Form.Item>
              </section>
              <section className={styles.studioSection}>
                <div className={styles.studioSectionHeader}>
                  <div>
                    <h3>{i18n('task.agent.outputContract')}</h3>
                    <p>{i18n('task.agent.outputContractHint')}</p>
                  </div>
                </div>
                <AgentOutputContractEditor form={form} />
              </section>
            </aside>
          </div>
          <footer className={styles.studioFooter}>
            <Button onClick={() => setView('list')}>{i18n('task.action.cancel')}</Button>
            <Button type="primary" loading={submitting} onClick={() => void save()}>
              {editingAgent ? i18n('common.button.save') : i18n('task.agent.create')}
            </Button>
          </footer>
        </Form>
      )}
    </Modal>
  );
}
