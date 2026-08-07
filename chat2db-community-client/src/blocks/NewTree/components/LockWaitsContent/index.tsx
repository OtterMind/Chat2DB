import { useCallback, useEffect, useState } from 'react';
import { Button, Table, Tabs, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import i18n from '@/i18n';
import sqlService, { ILockView } from '@/service/sql';

/**
 * Lock waits and blocking chains (MYSQL-OPS-003). Read-only; InnoDB data locks come from
 * performance_schema (8.0) or information_schema (5.7), metadata locks are shown only
 * when instrumented. The feature never terminates sessions — termination belongs to the
 * session flow (MYSQL-OPS-001).
 */
const LockWaitsContent = () => {
  const [view, setView] = useState<ILockView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    sqlService
      .getLockView({})
      .then(setView)
      .catch((e) => {
        setView(null);
        setError(e?.message || i18n('common.text.failure'));
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const chainColumns: ColumnsType<ILockView['waitChains'][number]> = [
    { title: i18n('workspace.ops.waiterThread'), dataIndex: 'waiterThreadId', width: 100 },
    { title: i18n('workspace.ops.waiterUser'), dataIndex: 'waiterUser', width: 120 },
    { title: i18n('workspace.ops.waiterState'), dataIndex: 'waiterState', width: 100 },
    { title: i18n('workspace.ops.waiterQuery'), dataIndex: 'waiterQuery', ellipsis: true },
    { title: i18n('workspace.ops.blockerThread'), dataIndex: 'blockerThreadId', width: 100 },
    { title: i18n('workspace.ops.blockerUser'), dataIndex: 'blockerUser', width: 120 },
    { title: i18n('workspace.ops.blockerState'), dataIndex: 'blockerState', width: 100 },
    { title: i18n('workspace.ops.blockerQuery'), dataIndex: 'blockerQuery', ellipsis: true },
    {
      title: i18n('workspace.ops.role'),
      width: 110,
      render: (_, record) =>
        record.rootBlocker ? <Tag color="red">{i18n('workspace.ops.rootBlocker')}</Tag> : <Tag>{i18n('workspace.ops.blocker')}</Tag>,
    },
  ];

  const lockColumns: ColumnsType<Record<string, string | null>> = [
    { title: i18n('workspace.ops.lockId'), dataIndex: 'ENGINE_LOCK_ID', width: 200, render: (v, r) => v ?? r.lock_id },
    { title: i18n('workspace.ops.lockObject'), dataIndex: 'OBJECT_SCHEMA', width: 220, render: (v, r) => (v != null ? `${v}.${r.OBJECT_NAME}` : r.lock_table) },
    { title: i18n('workspace.ops.lockType'), dataIndex: 'LOCK_TYPE', width: 110, render: (v, r) => v ?? r.lock_type },
    { title: i18n('workspace.ops.lockMode'), dataIndex: 'LOCK_MODE', width: 110, render: (v, r) => v ?? r.lock_mode },
    { title: i18n('workspace.ops.lockStatus'), dataIndex: 'LOCK_STATUS', width: 110 },
    { title: i18n('workspace.ops.lockData'), dataIndex: 'LOCK_DATA', ellipsis: true, render: (v, r) => v ?? r.lock_data },
  ];

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
        <span>
          {view
            ? i18n('workspace.ops.lockSource', view.source === 'performance_schema' ? 'Performance Schema (8.0)' : 'information_schema (5.7)')
            : ''}
        </span>
        <Button size="small" onClick={load} loading={loading}>
          {i18n('common.button.refresh')}
        </Button>
        {error && <span style={{ color: 'var(--text-color-danger)' }}>{error}</span>}
      </div>
      {view && (
        <Tabs
          items={[
            {
              key: 'chains',
              label: i18n('workspace.ops.blockingChains'),
              children: (
                <Table
                  size="small"
                  rowKey={(r) => `${r.waiterThreadId}-${r.blockerThreadId}`}
                  columns={chainColumns}
                  dataSource={view.waitChains}
                  loading={loading}
                  pagination={false}
                  scroll={{ x: 1400, y: 300 }}
                  locale={{ emptyText: i18n('workspace.ops.noLockWaits') }}
                />
              ),
            },
            {
              key: 'dataLocks',
              label: i18n('workspace.ops.innodbDataLocks', view.dataLocks.length),
              children: (
                <Table
                  size="small"
                  rowKey={(r, index) => r.ENGINE_LOCK_ID ?? r.lock_id ?? `lock-${index}`}
                  columns={lockColumns}
                  dataSource={view.dataLocks}
                  loading={loading}
                  pagination={false}
                  scroll={{ x: 1200, y: 300 }}
                />
              ),
            },
            {
              key: 'metaLocks',
              label: i18n('workspace.ops.metadataLocks', view.metaLocks.length),
              children: (
                <Table
                  size="small"
                  rowKey={(r) => `${r.OBJECT_SCHEMA}.${r.OBJECT_NAME}.${r.LOCK_TYPE}.${r.OWNER_THREAD_ID}`}
                  columns={[
                    { title: i18n('workspace.ops.lockObject'), dataIndex: 'OBJECT_SCHEMA', width: 220, render: (v, r) => `${v}.${r.OBJECT_NAME}` },
                    { title: i18n('workspace.ops.lockType'), dataIndex: 'LOCK_TYPE', width: 120 },
                    { title: i18n('workspace.ops.lockDuration'), dataIndex: 'LOCK_DURATION', width: 120 },
                    { title: i18n('workspace.ops.ownerThread'), dataIndex: 'OWNER_THREAD_ID' },
                  ]}
                  dataSource={view.metaLocks}
                  loading={loading}
                  pagination={false}
                  scroll={{ x: 800, y: 300 }}
                  locale={{ emptyText: i18n('workspace.ops.metadataLocksUnavailable') }}
                />
              ),
            },
          ]}
        />
      )}
    </div>
  );
};

export default LockWaitsContent;
