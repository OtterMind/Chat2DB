import i18n from '@/i18n';
import type { AgentDefinition } from '@/service/agent';
import { Alert, Button, Form, Input, Select, type FormInstance } from 'antd';
import { ArrowLeft, Plus } from 'lucide-react';
import type { IConnectionDetails } from '@/typings';
import { AgentIdentity } from './TaskPrimitives';
import { dataSourceDisplayName } from './taskDataSource';
import { useStyles } from './style';

interface Props {
  form: FormInstance;
  agents: AgentDefinition[];
  dataSources: IConnectionDetails[];
  submitting: boolean;
  onCancel: () => void;
  onSubmit: () => void;
  onDirtyChange: (dirty: boolean) => void;
}

export default function TaskCreatePage({
  form,
  agents,
  dataSources,
  submitting,
  onCancel,
  onSubmit,
  onDirtyChange,
}: Props) {
  const { styles } = useStyles();
  const agentById = new Map(agents.map((agent) => [agent.id, agent]));

  return (
    <div className={styles.fullPageForm}>
      <header className={styles.detailPageHeader}>
        <Button type="text" icon={<ArrowLeft size={16} />} onClick={onCancel}>
          {i18n('task.detail.back')}
        </Button>
        <Button type="primary" icon={<Plus size={15} />} loading={submitting} onClick={onSubmit}>
          {i18n('task.create.action')}
        </Button>
      </header>
      <main className={styles.fullPageFormBody}>
        <div className={styles.taskCreateHeader}>
          <div>
            <span>{i18n('task.title')}</span>
            <h2>{i18n('task.create.title')}</h2>
          </div>
        </div>
        <Form
          form={form}
          layout="vertical"
          className={styles.taskCreatePageForm}
          initialValues={{ priority: 0, scopeIndexes: [] }}
          onValuesChange={() => onDirtyChange(true)}
        >
          <Form.Item name="title" label={i18n('task.field.title')} rules={[{ required: true, max: 256 }]}>
            <Input autoFocus placeholder={i18n('task.create.titlePlaceholder')} />
          </Form.Item>
          <Form.Item name="description" label={i18n('task.field.description')}>
            <Input.TextArea rows={6} placeholder={i18n('task.create.descriptionPlaceholder')} />
          </Form.Item>
          <div className={styles.taskPropertyBar}>
            <Form.Item name="assigneeAgentId" label={i18n('task.field.agent')} rules={[{ required: true }]}>
              <Select
                style={{ minWidth: 220 }}
                showSearch
                optionFilterProp="label"
                options={agents.map((agent) => ({ value: agent.id, label: agent.name }))}
                optionRender={(option) => (
                  <AgentIdentity agent={agentById.get(String(option.value))} fallback={option.label} />
                )}
                labelRender={({ value, label }) => (
                  <AgentIdentity agent={agentById.get(String(value))} fallback={label} />
                )}
                onChange={(agentId) => {
                  const nextAgent = agentById.get(agentId);
                  form.setFieldValue('scopeIndexes', (nextAgent?.dataScopes || []).map((_, index) => index));
                }}
                placeholder={i18n('task.agent.select')}
              />
            </Form.Item>
            <Form.Item name="priority" label={i18n('task.field.priority')}>
              <Select
                style={{ width: 160 }}
                options={[0, 10, 20, 30].map((value) => ({
                  value,
                  label: i18n(`task.priority.${value}` as Parameters<typeof i18n>[0]),
                }))}
              />
            </Form.Item>
          </div>
          <Form.Item noStyle shouldUpdate={(before, after) => before.assigneeAgentId !== after.assigneeAgentId}>
            {({ getFieldValue }) => {
              const formAgent = agentById.get(getFieldValue('assigneeAgentId'));
              return (
                <>
                  <Form.Item name="scopeIndexes" label={i18n('task.scope.select')}>
                    <Select
                      mode="multiple"
                      options={(formAgent?.dataScopes || []).map((scope, index) => ({
                        value: index,
                        label: `${dataSourceDisplayName(
                          scope.dataSourceId,
                          dataSources,
                          i18n('task.scope.datasourceUnavailable', scope.dataSourceId),
                        )} / ${scope.databaseName || '*'} / ${scope.schemaName || '*'} · ${i18n(
                          'task.scope.approvalShort',
                          scope.approvalMode || 'RISK_BASED',
                        )}`,
                      }))}
                      placeholder={i18n('task.scope.selectPlaceholder')}
                    />
                  </Form.Item>
                  {formAgent && !formAgent.dataScopes.length && (
                    <Alert type="warning" showIcon message={i18n('task.agent.scopeBindingRequired')} />
                  )}
                </>
              );
            }}
          </Form.Item>
          <Form.Item name="acceptanceCriteria" label={i18n('task.field.acceptanceCriteria')}>
            <Input.TextArea rows={4} placeholder={i18n('task.create.criteriaPlaceholder')} />
          </Form.Item>
        </Form>
      </main>
    </div>
  );
}
