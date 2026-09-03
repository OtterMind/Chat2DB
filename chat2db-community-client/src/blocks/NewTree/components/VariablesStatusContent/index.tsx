import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Form, Input, Modal, Select, Table, Tabs } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import i18n from '@/i18n';
import sqlService, { IVariableItem, IVariableEditMeta } from '@/service/sql';

const SCOPE_LABELS = {
  SESSION: 'workspace.ops.scopeSession',
  GLOBAL: 'workspace.ops.scopeGlobal',
  PERSIST: 'workspace.ops.scopePersist',
  PERSIST_ONLY: 'workspace.ops.scopePersistOnly',
};

export const getVariableScopeOptions = (meta: IVariableEditMeta) =>
  [...(meta.dynamicScopes || []), ...(meta.persistScopes || [])].map((scope) => ({
    value: scope,
    label: i18n(SCOPE_LABELS[scope]),
  }));

const VIEWS: { key: string; scope: 'GLOBAL' | 'SESSION'; kind: 'VARIABLES' | 'STATUS' }[] = [
  { key: 'globalVariables', scope: 'GLOBAL', kind: 'VARIABLES' },
  { key: 'sessionVariables', scope: 'SESSION', kind: 'VARIABLES' },
  { key: 'globalStatus', scope: 'GLOBAL', kind: 'STATUS' },
  { key: 'sessionStatus', scope: 'SESSION', kind: 'STATUS' },
];

/**
 * MySQL Variables and Status view (MYSQL-OPS-004). Status views are read-only; only
 * variables registered in the editable registry offer a SET action, with a preview
 * confirmation before execution. High-risk variables require typing the variable name.
 */
const VariablesStatusContent = ({ dataSourceId, consoleId }: { dataSourceId: number; consoleId: number }) => {
  const [activeKey, setActiveKey] = useState('globalVariables');
  const [filter, setFilter] = useState('');
  const [data, setData] = useState<Record<string, IVariableItem[]>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editTarget, setEditTarget] = useState<IVariableItem | null>(null);
  const [editMeta, setEditMeta] = useState<IVariableEditMeta | null>(null);
  const [confirmName, setConfirmName] = useState('');
  const [metaCache, setMetaCache] = useState<Record<string, IVariableEditMeta | null>>({});
  const [form] = Form.useForm<{ value: string; scope: string }>();

  const load = useCallback((view: { key: string; scope: string; kind: string }) => {
    setLoading(true);
    setError(null);
    sqlService
      .getVariableList({ dataSourceId, consoleId, scope: view.scope, kind: view.kind })
      .then((list) => {
        setData((prev) => ({ ...prev, [view.key]: list || [] }));
      })
      .catch((e) => {
        setError(e?.message || i18n('common.text.failure'));
      })
      .finally(() => setLoading(false));
  }, [consoleId, dataSourceId]);

  useEffect(() => {
    const view = VIEWS.find((v) => v.key === activeKey)!;
    load(view);
  }, [activeKey, load]);

  const editMetaByName = useCallback(
    (name: string): IVariableEditMeta | null | undefined => {
      if (!(name in metaCache)) {
        void sqlService.getVariableEditable({ dataSourceId, consoleId, name }).then((m) => {
          setMetaCache((prev) => ({ ...prev, [name]: m }));
        });
        return undefined;
      }
      return metaCache[name];
    },
    [consoleId, dataSourceId, metaCache],
  );

  const currentView = VIEWS.find((v) => v.key === activeKey)!;
  const rows = useMemo(() => {
    const list = data[activeKey] || [];
    const keyword = filter.trim().toLowerCase();
    if (!keyword) {
      return list;
    }
    return list.filter((r) => r.name.toLowerCase().includes(keyword));
  }, [data, activeKey, filter]);

  const openEdit = async (record: IVariableItem) => {
    const meta = await sqlService.getVariableEditable({ dataSourceId, consoleId, name: record.name });
    setEditTarget(record);
    setEditMeta(meta || null);
    setConfirmName('');
    form.resetFields();
    const scopeOptions = meta ? getVariableScopeOptions(meta) : [];
    form.setFieldsValue({ value: record.value ?? '', scope: scopeOptions[0]?.value });
  };

  const submitEdit = () => {
    if (!editTarget || !editMeta) {
      return;
    }
    form.validateFields().then((values) => {
      const scope = values.scope;
      sqlService
        .previewSetVariableSql({ dataSourceId, consoleId, variableName: editTarget.name, value: values.value, scope })
        .then((sql) => {
          setEditTarget(null);
          Modal.confirm({
            title: i18n('workspace.ops.variablePreviewTitle'),
            content: <pre style={{ whiteSpace: 'pre-wrap' }}>{sql}</pre>,
            okText: i18n('common.button.execute'),
            cancelText: i18n('common.button.cancel'),
            onOk: () => sqlService.executeDDL({ dataSourceId, consoleId, sql }).then(() => load(currentView)),
          });
        })
        .catch((e) => {
          Modal.error({ title: i18n('workspace.ops.variableEditFailed'), content: e?.message });
        });
    });
  };

  const columns: ColumnsType<IVariableItem> = [
    { title: i18n('workspace.ops.variableName'), dataIndex: 'name', width: 320 },
    { title: i18n('workspace.ops.variableValue'), dataIndex: 'value', ellipsis: true },
    ...(currentView.kind === 'VARIABLES'
      ? [
          { title: i18n('workspace.ops.variableSource'), dataIndex: 'source', width: 160, ellipsis: true },
          { title: i18n('workspace.ops.variablePath'), dataIndex: 'path', width: 260, ellipsis: true },
        ]
      : []),
    ...(currentView.kind === 'VARIABLES'
      ? [
          {
            title: i18n('workspace.ops.variableAction'),
            width: 140,
            render: (_: unknown, record: IVariableItem) => {
              const meta = editMetaByName(record.name);
              if (meta === undefined) {
                return null;
              }
              return meta ? (
                <Button size="small" onClick={() => void openEdit(record)}>
                  {i18n('workspace.ops.variableEdit')}
                </Button>
              ) : (
                <span style={{ color: 'var(--text-color-secondary)' }}>{i18n('workspace.ops.variableReadOnly')}</span>
              );
            },
          },
        ]
      : []),
  ];

  const isHighRisk = editMeta?.highRisk && editTarget != null;
  const confirmDisabled = isHighRisk && confirmName !== editTarget?.name;

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
        <Input
          placeholder={i18n('workspace.ops.variableFilter')}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          style={{ width: 240 }}
          allowClear
        />
        <Button size="small" onClick={() => load(currentView)} loading={loading}>
          {i18n('common.button.refresh')}
        </Button>
        {error && <span style={{ color: 'var(--text-color-danger)' }}>{error}</span>}
      </div>
      <Tabs
        activeKey={activeKey}
        onChange={setActiveKey}
        items={VIEWS.map((v) => ({ key: v.key, label: i18n(`workspace.ops.${v.key}`) }))}
      />
      <Table
        size="small"
        rowKey="name"
        columns={columns}
        dataSource={rows}
        loading={loading}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        scroll={{ x: 800, y: 420 }}
      />
      <Modal
        open={editTarget != null}
        title={i18n('workspace.ops.variableEditTitle')}
        onCancel={() => setEditTarget(null)}
        onOk={submitEdit}
        okButtonProps={{ disabled: confirmDisabled }}
        destroyOnClose
      >
        {editMeta ? (
          <Form form={form} layout="vertical">
            <Form.Item label={i18n('workspace.ops.variableName')}>
              <Input value={editTarget?.name} disabled />
            </Form.Item>
            <Form.Item
              label={i18n('workspace.ops.variableValue')}
              name="value"
              rules={[{ required: true, message: i18n('workspace.ops.variableValueRequired') }]}
            >
              <Input />
            </Form.Item>
            <Form.Item label={i18n('workspace.ops.variableScope')} name="scope">
              <Select options={getVariableScopeOptions(editMeta)} />
            </Form.Item>
            {isHighRisk && (
              <Form.Item label={i18n('workspace.ops.highRiskConfirm')}>
                <Input
                  value={confirmName}
                  onChange={(e) => setConfirmName(e.target.value)}
                  placeholder={editTarget?.name}
                />
              </Form.Item>
            )}
          </Form>
        ) : (
          <div>{i18n('workspace.ops.variableReadOnly')}</div>
        )}
      </Modal>
    </div>
  );
};

export default VariablesStatusContent;
