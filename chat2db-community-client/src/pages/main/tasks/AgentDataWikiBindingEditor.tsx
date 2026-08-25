import i18n from '@/i18n';
import type { AgentDataWikiBinding } from '@/service/agent';
import type { DataWikiDefinition } from '@/service/dataWiki';
import { Alert, Button, Form, InputNumber, Select, Switch } from 'antd';
import { BookOpenText, Plus, Trash2 } from 'lucide-react';

import { useStyles } from './style';

interface BindingRowProps {
  field: { key: number; name: number };
  form: ReturnType<typeof Form.useForm>[0];
  dataWikis: DataWikiDefinition[];
  onRemove: () => void;
}

function BindingRow({ field, form, dataWikis, onRemove }: BindingRowProps) {
  const { styles } = useStyles();
  const dataWikiId = Form.useWatch(['dataWikiBindings', field.name, 'dataWikiId'], form);
  const bindings = Form.useWatch('dataWikiBindings', form) as AgentDataWikiBinding[] | undefined;
  const wiki = dataWikis.find((item) => item.id === dataWikiId);
  const selectedIds = new Set((bindings || []).map((item) => item?.dataWikiId).filter(Boolean));
  const dataSourceNames = Array.from(new Set(
    (wiki?.resources || []).map((resource) => resource.dataSourceName).filter(Boolean),
  )).join(', ');

  return (
    <section className={styles.scopeEditor}>
      <div className={styles.scopeEditorHeader}>
        <div className={styles.scopeEditorIdentity}>
          <span className={styles.scopeIcon}>
            <BookOpenText size={15} />
          </span>
          <div>
            <strong>{wiki?.name || i18n('task.agent.dataWikiNew')}</strong>
            <span>
              {wiki
                ? i18n(
                    'task.agent.dataWikiResourceSummary',
                    wiki.resources?.length || 0,
                    dataSourceNames || i18n('task.dataWiki.dataSource'),
                  )
                : i18n('task.agent.dataWikiConfigure')}
            </span>
          </div>
        </div>
        <Button
          type="text"
          danger
          icon={<Trash2 size={15} />}
          aria-label={i18n('task.agent.dataWikiRemove')}
          onClick={onRemove}
        />
      </div>

      <Form.Item
        name={[field.name, 'dataWikiId']}
        label={i18n('task.dataWiki.title')}
        rules={[{ required: true }]}
      >
        <Select
          showSearch
          optionFilterProp="label"
          placeholder={i18n('task.agent.dataWikiBindingPlaceholder')}
          options={dataWikis.map((item) => ({
            value: item.id,
            label: item.name,
            disabled: item.id !== dataWikiId && selectedIds.has(item.id),
          }))}
          notFoundContent={i18n('task.agent.dataWikiEmpty')}
        />
      </Form.Item>

      <div className={styles.scopePolicyGrid}>
        <Form.Item
          name={[field.name, 'maxRows']}
          label={i18n('task.agent.maxRows')}
          rules={[{ required: true }]}
        >
          <InputNumber min={1} />
        </Form.Item>
        <Form.Item
          name={[field.name, 'timeoutSeconds']}
          label={i18n('task.agent.timeout')}
          rules={[{ required: true }]}
        >
          <InputNumber min={1} />
        </Form.Item>
        <Form.Item
          name={[field.name, 'approvalMode']}
          label={i18n('task.agent.approvalMode.scope')}
          extra={i18n('task.agent.dataWikiPolicyHint')}
          rules={[{ required: true }]}
        >
          <Select
            options={(['NEVER', 'RISK_BASED', 'ALWAYS'] as const).map((value) => ({
              value,
              label: `${value} · ${i18n(
                `task.agent.approvalMode.${value.toLowerCase()}` as Parameters<typeof i18n>[0],
              )}`,
            }))}
          />
        </Form.Item>
        <Form.Item
          name={[field.name, 'allowProduction']}
          label={i18n('task.agent.production')}
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>
      </div>
    </section>
  );
}

interface Props {
  form: ReturnType<typeof Form.useForm>[0];
  dataWikis: DataWikiDefinition[];
}

export default function AgentDataWikiBindingEditor({ form, dataWikis }: Props) {
  const { styles } = useStyles();
  return (
    <Form.List name="dataWikiBindings">
      {(fields, { add, remove }) => (
        <div className={styles.scopeStudio}>
          <div className={styles.studioSectionHeader}>
            <div>
              <h3>
                <BookOpenText size={16} />
                {i18n('task.agent.dataWikiBinding')}
              </h3>
              <p>{i18n('task.agent.dataWikiBindingHint')}</p>
            </div>
            <Button
              icon={<Plus size={14} />}
              onClick={() => add({
                maxRows: 200,
                timeoutSeconds: 60,
                approvalMode: 'RISK_BASED',
                allowProduction: false,
              })}
            >
              {i18n('task.agent.dataWikiAdd')}
            </Button>
          </div>
          <Alert type="info" showIcon message={i18n('task.agent.dataWikiScopeHint')} />
          {fields.map((field) => (
            <BindingRow
              key={field.key}
              field={field}
              form={form}
              dataWikis={dataWikis}
              onRemove={() => remove(field.name)}
            />
          ))}
        </div>
      )}
    </Form.List>
  );
}
