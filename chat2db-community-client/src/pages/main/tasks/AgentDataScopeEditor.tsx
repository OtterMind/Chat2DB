import i18n from '@/i18n';
import connectionService from '@/service/connection';
import sqlService from '@/service/sql';
import type { IConnectionDetails } from '@/typings';
import { Alert, Button, Form, InputNumber, Radio, Select, Switch } from 'antd';
import { Database, Plus, ShieldCheck, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';

import { useStyles } from './style';
import { dataSourceDisplayName } from './taskDataSource';

interface ScopeRowProps {
  field: { key: number; name: number };
  form: ReturnType<typeof Form.useForm>[0];
  dataSources: IConnectionDetails[];
  onRemove: () => void;
}

function ScopeRow({ field, form, dataSources, onRemove }: ScopeRowProps) {
  const { styles } = useStyles();
  const dataSourceId = Form.useWatch(['dataScopes', field.name, 'dataSourceId'], form);
  const databaseName = Form.useWatch(['dataScopes', field.name, 'databaseName'], form);
  const schemaName = Form.useWatch(['dataScopes', field.name, 'schemaName'], form);
  const accessLevel = Form.useWatch(['dataScopes', field.name, 'accessLevel'], form) || 'NAMESPACE';
  const [databases, setDatabases] = useState<string[]>([]);
  const [schemas, setSchemas] = useState<string[]>([]);
  const [tables, setTables] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setDatabases([]);
    setSchemas([]);
    setTables([]);
    if (!dataSourceId) return;
    setLoading(true);
    connectionService
      .getDatabaseList({ dataSourceId })
      .then((items) => setDatabases((items || []).map((item) => item.name)))
      .catch(() => setDatabases([]))
      .finally(() => setLoading(false));
  }, [dataSourceId]);

  useEffect(() => {
    setSchemas([]);
    setTables([]);
    if (!dataSourceId) return;
    connectionService
      .getSchemaList({ dataSourceId, databaseName })
      .then((items) => setSchemas((items || []).map((item) => item.name)))
      .catch(() => setSchemas([]));
  }, [dataSourceId, databaseName]);

  useEffect(() => {
    setTables([]);
    if (!dataSourceId || accessLevel !== 'TABLES') return;
    setLoading(true);
    sqlService
      .getTableList({ dataSourceId, databaseName, schemaName, pageNo: 1, pageSize: 1000 })
      .then((result) => setTables((result.data || []).map((item) => item.name)))
      .catch(() => setTables([]))
      .finally(() => setLoading(false));
  }, [accessLevel, dataSourceId, databaseName, schemaName]);

  const source = dataSources.find((item) => item.id === dataSourceId);
  return (
    <section className={styles.scopeEditor}>
      <div className={styles.scopeEditorHeader}>
        <div className={styles.scopeEditorIdentity}>
          <span className={styles.scopeIcon}>
            <Database size={15} />
          </span>
          <div>
            <strong>{source?.alias || i18n('task.scope.new')}</strong>
            <span>{source?.type || i18n('task.scope.configure')}</span>
          </div>
        </div>
        <Button
          type="text"
          danger
          icon={<Trash2 size={15} />}
          aria-label={i18n('task.scope.remove')}
          onClick={onRemove}
        />
      </div>

      <div className={styles.scopePathGrid}>
        <Form.Item
          name={[field.name, 'dataSourceId']}
          label={i18n('task.evidence.datasource')}
          rules={[{ required: true }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder={i18n('task.scope.datasourcePlaceholder')}
            options={dataSources.map((item) => ({
              value: item.id,
              label: dataSourceDisplayName(
                item.id,
                dataSources,
                i18n('task.scope.datasourceUnavailable', item.id),
              ),
            }))}
            onChange={() => form.setFieldValue(['dataScopes', field.name, 'databaseName'], undefined)}
          />
        </Form.Item>
        <Form.Item name={[field.name, 'databaseName']} label={i18n('task.agent.database')}>
          <Select
            allowClear
            showSearch
            loading={loading}
            disabled={!dataSourceId}
            placeholder={i18n('task.scope.allDatabases')}
            options={databases.map((value) => ({ value, label: value }))}
            onChange={() => form.setFieldValue(['dataScopes', field.name, 'schemaName'], undefined)}
          />
        </Form.Item>
        <Form.Item name={[field.name, 'schemaName']} label={i18n('task.agent.schema')}>
          <Select
            allowClear
            showSearch
            disabled={!dataSourceId}
            placeholder={i18n('task.scope.allSchemas')}
            options={schemas.map((value) => ({ value, label: value }))}
          />
        </Form.Item>
      </div>

      <Form.Item name={[field.name, 'accessLevel']} label={i18n('task.scope.accessLevel')}>
        <Radio.Group className={styles.accessChoiceGroup}>
          <Radio.Button value="NAMESPACE">
            <strong>{i18n('task.scope.namespaceWide')}</strong>
            <span>{i18n('task.scope.namespaceWideHint')}</span>
          </Radio.Button>
          <Radio.Button value="TABLES">
            <strong>{i18n('task.scope.selectedTables')}</strong>
            <span>{i18n('task.scope.selectedTablesHint')}</span>
          </Radio.Button>
        </Radio.Group>
      </Form.Item>

      {accessLevel === 'TABLES' && (
        <Form.Item
          name={[field.name, 'tableNames']}
          label={i18n('task.agent.tables')}
          rules={[{ required: true }]}
        >
          <Select
            mode="multiple"
            showSearch
            loading={loading}
            placeholder={i18n('task.scope.tablesPlaceholder')}
            options={tables.map((value) => ({ value, label: value }))}
          />
        </Form.Item>
      )}
      <Form.Item name={[field.name, 'excludedTableNames']} label={i18n('task.agent.excludedTables')}>
        <Select mode="tags" tokenSeparators={[',']} placeholder={i18n('task.scope.excludedPlaceholder')} />
      </Form.Item>

      <div className={styles.scopePolicyGrid}>
        <Form.Item name={[field.name, 'maxRows']} label={i18n('task.agent.maxRows')}>
          <InputNumber min={1} />
        </Form.Item>
        <Form.Item name={[field.name, 'timeoutSeconds']} label={i18n('task.agent.timeout')}>
          <InputNumber min={1} />
        </Form.Item>
        <Form.Item
          name={[field.name, 'approvalMode']}
          label={i18n('task.agent.approvalMode.scope')}
          extra={i18n('task.agent.approvalMode.scopeHint')}
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

interface AgentDataScopeEditorProps {
  form: ReturnType<typeof Form.useForm>[0];
  dataSources: IConnectionDetails[];
}

export default function AgentDataScopeEditor({ form, dataSources }: AgentDataScopeEditorProps) {
  const { styles } = useStyles();
  return (
    <Form.List name="dataScopes">
      {(fields, { add, remove }, { errors }) => (
        <div className={styles.scopeStudio}>
          <div className={styles.studioSectionHeader}>
            <div>
              <h3>
                <ShieldCheck size={16} />
                {i18n('task.scope.title')}
              </h3>
              <p>{i18n('task.scope.designHint')}</p>
            </div>
            <Button
              icon={<Plus size={14} />}
              onClick={() =>
                add({
                  accessLevel: 'NAMESPACE',
                  maxRows: 200,
                  timeoutSeconds: 60,
                  approvalMode: 'RISK_BASED',
                  allowProduction: false,
                })
              }
            >
              {i18n('task.agent.addScope')}
            </Button>
          </div>
          <Alert
            type="info"
            showIcon
            message={i18n('task.agent.approvalMode.scopeNotice')}
            description={i18n('task.agent.approvalMode.capabilityNotice')}
          />
          {fields.map((field) => (
            <ScopeRow
              key={field.key}
              field={field}
              form={form}
              dataSources={dataSources}
              onRemove={() => remove(field.name)}
            />
          ))}
          <Form.ErrorList errors={errors} />
        </div>
      )}
    </Form.List>
  );
}
