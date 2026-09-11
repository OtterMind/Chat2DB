import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ExternalLink } from 'lucide-react';
import styles from './index.less';
import i18n from '@/i18n';
import {
  ActiveTransactionLockMetadataState,
  ActiveTransactionQueryState,
  MYSQL_ACTIVE_TRANSACTION_STATE,
} from '@/constants/activeTransaction';
import sqlService, { type IActiveTransactionItem } from '@/service/sql';
import { RequestGenerationRef } from '@/utils/latestRequest';
import {
  beginActiveTransactionRefresh,
  type ActiveTransactionLoadError,
  ActiveTransactionLoadErrorKind,
  classifyActiveTransactionLoadError,
  formatActiveTransactionStartedAt,
  getActiveTransactionRowKey,
  getLiveTransactionAge,
  getTransactionConnectionInspection,
  type TransactionConnectionInspection,
  invalidateActiveTransactionRefresh,
  isLatestActiveTransactionRefresh,
} from './activeTransactionUtils';

/**
 * Active InnoDB transaction list (MYSQL-OPS-002). Read-only; full visibility of other
 * users' transactions and their SQL requires the PROCESS privilege, which is surfaced as
 * an explicit unavailable state instead of a misleading blank list.
 */
interface ActiveTransactionsContentProps {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  onInspectConnection?: (inspection: TransactionConnectionInspection) => void;
}

const ActiveTransactionsContent = ({
  dataSourceId,
  databaseName,
  schemaName,
  onInspectConnection,
}: ActiveTransactionsContentProps) => {
  const [data, setData] = useState<IActiveTransactionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<ActiveTransactionLoadError | null>(null);
  const [snapshotAtMs, setSnapshotAtMs] = useState(() => Date.now());
  const [nowMs, setNowMs] = useState(() => Date.now());
  const refreshGenerationRef = useRef<RequestGenerationRef>({ current: 0 });

  const load = useCallback(() => {
    const generation = beginActiveTransactionRefresh(refreshGenerationRef.current);
    setLoading(true);
    setError(null);
    sqlService
      .getActiveTransactionList({ dataSourceId, databaseName, schemaName })
      .then((list) => {
        if (!isLatestActiveTransactionRefresh(refreshGenerationRef.current, generation)) {
          return;
        }
        const loadedAt = Date.now();
        setData(list || []);
        setSnapshotAtMs(loadedAt);
        setNowMs(loadedAt);
      })
      .catch((e: unknown) => {
        if (!isLatestActiveTransactionRefresh(refreshGenerationRef.current, generation)) {
          return;
        }
        setData([]);
        setError(classifyActiveTransactionLoadError(e));
      })
      .finally(() => {
        if (isLatestActiveTransactionRefresh(refreshGenerationRef.current, generation)) {
          setLoading(false);
        }
      });
  }, [dataSourceId, databaseName, schemaName]);

  useEffect(() => {
    load();
    return () => invalidateActiveTransactionRefresh(refreshGenerationRef.current);
  }, [load]);

  useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const inspectConnection = useCallback(
    (record: IActiveTransactionItem, target: 'owner' | 'blocker') => {
      const inspection = getTransactionConnectionInspection(record, target);
      if (!inspection || !onInspectConnection) {
        return;
      }
      onInspectConnection(inspection);
    },
    [onInspectConnection],
  );

  const renderUnavailable = (label: string) => <Tag color="default">{label}</Tag>;

  const columns: ColumnsType<IActiveTransactionItem> = [
    { title: i18n('workspace.ops.transactionId'), dataIndex: 'trxId', width: 120 },
    {
      title: i18n('workspace.ops.transactionState'),
      dataIndex: 'state',
      width: 120,
      filters: [
        { text: MYSQL_ACTIVE_TRANSACTION_STATE.RUNNING, value: MYSQL_ACTIVE_TRANSACTION_STATE.RUNNING },
        { text: MYSQL_ACTIVE_TRANSACTION_STATE.LOCK_WAIT, value: MYSQL_ACTIVE_TRANSACTION_STATE.LOCK_WAIT },
      ],
      onFilter: (value, record) => record.state === value,
      sorter: (a, b) => String(a.state || '').localeCompare(String(b.state || '')),
    },
    {
      title: i18n('workspace.ops.transactionStarted'),
      dataIndex: 'startedAt',
      width: 170,
      render: formatActiveTransactionStartedAt,
      sorter: (a, b) => String(a.startedAt || '').localeCompare(String(b.startedAt || '')),
    },
    {
      title: i18n('workspace.ops.transactionAge'),
      dataIndex: 'ageSeconds',
      width: 90,
      render: (v: number | null) => {
        const liveAge = getLiveTransactionAge(v, snapshotAtMs, nowMs);
        return liveAge == null ? '-' : i18n('workspace.ops.secondsFormat', liveAge);
      },
      sorter: (a, b) => (a.ageSeconds || 0) - (b.ageSeconds || 0),
    },
    { title: i18n('workspace.ops.transactionIsolation'), dataIndex: 'isolationLevel', width: 130 },
    {
      title: i18n('workspace.ops.rowsLocked'),
      dataIndex: 'rowsLocked',
      width: 100,
      sorter: (a, b) => (a.rowsLocked || 0) - (b.rowsLocked || 0),
    },
    {
      title: i18n('workspace.ops.rowsModified'),
      dataIndex: 'rowsModified',
      width: 110,
      sorter: (a, b) => (a.rowsModified || 0) - (b.rowsModified || 0),
    },
    {
      title: i18n('workspace.ops.threadId'),
      dataIndex: 'threadId',
      width: 120,
      render: (_value, record) => {
        if (!onInspectConnection || !getTransactionConnectionInspection(record, 'owner')) {
          return record.sessionAvailable === false
            ? renderUnavailable(i18n('workspace.ops.sessionUnavailable'))
            : record.threadId;
        }
        return (
          <Tooltip title={i18n('workspace.ops.openSession')}>
            <Button
              type="link"
              size="small"
              icon={<ExternalLink size={14} />}
              onClick={() => inspectConnection(record, 'owner')}
            >
              {record.threadId}
            </Button>
          </Tooltip>
        );
      },
      sorter: (a, b) => (a.threadId || 0) - (b.threadId || 0),
    },
    { title: i18n('workspace.ops.user'), dataIndex: 'user', width: 110 },
    { title: i18n('workspace.ops.host'), dataIndex: 'host', width: 140 },
    { title: i18n('workspace.ops.database'), dataIndex: 'db', width: 120 },
    {
      title: i18n('workspace.ops.waitedLock'),
      dataIndex: 'waitingLockId',
      width: 220,
      render: (_value, record) => {
        if (record.lockMetadataState === ActiveTransactionLockMetadataState.UNAVAILABLE) {
          return renderUnavailable(i18n('workspace.ops.lockMetadataUnavailable'));
        }
        if (!record.lockWaitAvailable) {
          return '-';
        }
        return (
          <Space direction="vertical" size={0}>
            <Typography.Text copyable>{record.waitingLockId}</Typography.Text>
            <Typography.Text type="secondary">
              {[record.waitingObject, record.waitingIndex, record.waitingLockMode].filter(Boolean).join(' / ')}
            </Typography.Text>
          </Space>
        );
      },
    },
    {
      title: i18n('workspace.ops.blocker'),
      dataIndex: 'blockingTrxId',
      width: 240,
      render: (_value, record) => {
        if (!record.lockWaitAvailable) {
          return '-';
        }
        return (
          <Space direction="vertical" size={0}>
            <Typography.Text copyable>{record.blockingTrxId}</Typography.Text>
            {onInspectConnection && getTransactionConnectionInspection(record, 'blocker') && (
              <Tooltip title={i18n('workspace.ops.openBlockingSession')}>
                <Button
                  type="link"
                  size="small"
                  icon={<ExternalLink size={14} />}
                  onClick={() => inspectConnection(record, 'blocker')}
                >
                  {`${i18n('workspace.ops.threadId')}: ${record.blockingThreadId}`}
                </Button>
              </Tooltip>
            )}
            <Typography.Text type="secondary">
              {[record.blockingUser, record.blockingHost, record.blockingDb].filter(Boolean).join(' / ') ||
                i18n('workspace.ops.sessionUnavailable')}
            </Typography.Text>
          </Space>
        );
      },
    },
    {
      title: i18n('workspace.ops.query'),
      dataIndex: 'query',
      width: 260,
      ellipsis: true,
      render: (value: string | null, record) =>
        value ? (
          <Typography.Text ellipsis={{ tooltip: value }}>{value}</Typography.Text>
        ) : (
          renderUnavailable(
            record.queryState === ActiveTransactionQueryState.UNAVAILABLE
              ? i18n('workspace.ops.queryUnavailable')
              : i18n('workspace.ops.emptyValue'),
          )
        ),
    },
  ];
  const hasUnavailableLockMetadata = data.some(
    (item) => item.lockMetadataState === ActiveTransactionLockMetadataState.UNAVAILABLE,
  );
  const errorMessage = error
    ? error.kind === ActiveTransactionLoadErrorKind.PROCESS_PRIVILEGE_REQUIRED
      ? `${i18n('workspace.ops.permissionRequired')} ${i18n('workspace.ops.processPrivilegeHint')}`
      : error.message || i18n('common.text.failure')
    : null;

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>
          {error ? i18n('common.text.failure') : i18n('workspace.ops.activeTransactionCount', data.length)}
        </span>
        <Button size="small" onClick={load} loading={loading}>
          {i18n('common.button.refresh')}
        </Button>
      </div>
      {!error && hasUnavailableLockMetadata && (
        <div style={{ marginBottom: 8, color: 'var(--text-color-secondary)' }}>
          {i18n('workspace.ops.lockMetadataDegraded')} {i18n('workspace.ops.lockMetadataUnavailable')}
        </div>
      )}
      {error ? (
        <div style={{ color: 'var(--text-color-danger)' }}>{errorMessage}</div>
      ) : (
        <Table
          className={styles.table}
          size="small"
          rowKey={getActiveTransactionRowKey}
          columns={columns}
          dataSource={data}
          loading={loading}
          pagination={false}
          scroll={{ x: 1720 }}
        />
      )}
    </div>
  );
};

export default ActiveTransactionsContent;
