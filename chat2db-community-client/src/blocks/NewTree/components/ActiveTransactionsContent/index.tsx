import { useCallback, useEffect, useState } from 'react';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import i18n from '@/i18n';
import sqlService, { IActiveTransactionItem } from '@/service/sql';

/**
 * Active InnoDB transaction list (MYSQL-OPS-002). Read-only; full visibility of other
 * users' transactions and their SQL requires the PROCESS privilege, which is surfaced as
 * an explicit unavailable state instead of a misleading blank list.
 */
const ActiveTransactionsContent = () => {
  const [data, setData] = useState<IActiveTransactionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    sqlService
      .getActiveTransactionList({})
      .then((list) => setData(list || []))
      .catch((e) => {
        setData([]);
        setError(e?.message || i18n('common.text.failure'));
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const columns: ColumnsType<IActiveTransactionItem> = [
    { title: i18n('workspace.ops.transactionId'), dataIndex: 'trxId', width: 110 },
    { title: i18n('workspace.ops.transactionState'), dataIndex: 'state', width: 100 },
    { title: i18n('workspace.ops.transactionStarted'), dataIndex: 'startedAt', width: 170 },
    {
      title: i18n('workspace.ops.transactionAge'),
      dataIndex: 'ageSeconds',
      width: 90,
      render: (v: number | null) => (v == null ? '-' : i18n('workspace.ops.secondsFormat', v)),
    },
    { title: i18n('workspace.ops.transactionIsolation'), dataIndex: 'isolationLevel', width: 130 },
    { title: i18n('workspace.ops.rowsLocked'), dataIndex: 'rowsLocked', width: 90 },
    { title: i18n('workspace.ops.rowsModified'), dataIndex: 'rowsModified', width: 100 },
    { title: i18n('workspace.ops.threadId'), dataIndex: 'threadId', width: 90 },
    { title: i18n('workspace.ops.user'), dataIndex: 'user', width: 110 },
    { title: i18n('workspace.ops.host'), dataIndex: 'host', width: 140 },
    { title: i18n('workspace.ops.database'), dataIndex: 'db', width: 120 },
    { title: i18n('workspace.ops.query'), dataIndex: 'query', ellipsis: true },
  ];

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>
          {error
            ? `${i18n('workspace.ops.permissionRequired')} ${i18n('workspace.ops.processPrivilegeHint')}`
            : i18n('workspace.ops.activeTransactionCount', data.length)}
        </span>
        <Button size="small" onClick={load} loading={loading}>
          {i18n('common.button.refresh')}
        </Button>
      </div>
      {error ? (
        <div style={{ color: 'var(--text-color-danger)' }}>{error}</div>
      ) : (
        <Table
          size="small"
          rowKey={(record) => `${record.threadId}-${record.trxId ?? 'ro'}`}
          columns={columns}
          dataSource={data}
          loading={loading}
          pagination={false}
          scroll={{ x: 1200 }}
        />
      )}
    </div>
  );
};

export default ActiveTransactionsContent;
