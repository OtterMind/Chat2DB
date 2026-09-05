import { Alert, Button, Table, Tabs, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useCallback, useEffect, useRef, useState } from 'react';

import i18n from '@/i18n';
import sqlService, {
  type IDataLock,
  type ILockSession,
  type ILockView,
  type ILockWaitChain,
  type IMetadataLock,
  LockViewErrorCode,
  LockViewSource,
} from '@/service/sql';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

interface LockWaitsContentProps {
  dataSourceId: number;
  onOpenSession?: (target: LockSessionNavigationTarget) => void;
}

export interface LockSessionNavigationTarget {
  dataSourceId: number;
  sessionId: string;
  engineThreadId?: string | null;
  databaseName?: string | null;
  user?: string | null;
  host?: string | null;
  query?: string | null;
}

const sourceLabel = (source: ILockView['source']) => {
  if (source === LockViewSource.PERFORMANCE_SCHEMA) {
    return 'Performance Schema (8.0)';
  }
  if (source === LockViewSource.INFORMATION_SCHEMA) {
    return 'information_schema (5.7)';
  }
  return i18n('workspace.ops.lockSourceUnavailable');
};

const valueText = (value: unknown) =>
  value == null || value === '' ? i18n('workspace.ops.valueUnavailable') : String(value);

export const lockObjectText = (
  objectSchema: string | null,
  objectName: string | null,
  fallback?: string | null,
) => {
  const object = [objectSchema, objectName].filter(Boolean).join('.');
  return object || fallback || null;
};

export const metadataLockRowKey = (row: IMetadataLock) =>
  [
    row.objectInstanceId,
    row.objectType,
    row.objectSchema,
    row.objectName,
    row.lockType,
    row.lockDuration,
    row.lockStatus,
    row.ownerThreadId,
    row.ownerEventId,
  ]
    .map((value) => value ?? '')
    .join(':');

export const lockViewErrorText = (errors: ILockView['errors']) => {
  const messages = new Set<string>();
  for (const { code } of errors) {
    messages.add(
      code === LockViewErrorCode.PRIVILEGE_REQUIRED
        ? i18n('workspace.ops.lockPrivilegeRequired')
        : i18n('workspace.ops.lockMetadataUnavailable'),
    );
  }
  return messages.size ? Array.from(messages).join('; ') : null;
};

const waitChainRowKey = (row: ILockWaitChain, dataSourceId: number) =>
  [
    dataSourceId,
    row.lockKind,
    row.lockObject,
    row.waiterTransactionId,
    row.blockerTransactionId,
    row.waiterLockId,
    row.blockerLockId,
    row.waiterEngineThreadId,
    row.blockerEngineThreadId,
  ]
    .map((value) => value ?? '')
    .join(':');

const dataLockRowKey = (row: IDataLock) =>
  [row.lockId, row.transactionId, row.engineThreadId, row.eventId, row.objectSchema, row.objectName, row.lockType]
    .map((value) => value ?? '')
    .join(':');

const LockWaitsContent = ({ dataSourceId, onOpenSession }: LockWaitsContentProps) => {
  const [view, setView] = useState<ILockView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestGenerationRef = useRef(0);

  const load = useCallback(() => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    setLoading(true);
    setError(null);
    setView((current) => (current?.dataSourceId === dataSourceId ? current : null));
    sqlService
      .getLockView({ dataSourceId })
      .then((result) => {
        if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
        setView(result);
        setError(lockViewErrorText(result.errors));
      })
      .catch((requestError) => {
        if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
        setView(null);
        setError(requestError?.message || i18n('common.text.failure'));
      })
      .finally(() => {
        if (isLatestRequest(requestGenerationRef, requestGeneration)) setLoading(false);
      });
  }, [dataSourceId]);

  useEffect(() => {
    load();
    return () => invalidateLatestRequest(requestGenerationRef);
  }, [load]);

  const renderSessionThread = (
    sessionId: string | null,
    sessionAvailable: boolean,
    engineThreadId: string | null,
    metadataLockCount: number,
    sessionContext: Omit<LockSessionNavigationTarget, 'dataSourceId' | 'sessionId'>,
  ) => {
    const canOpenSession = Boolean(onOpenSession && sessionAvailable && sessionId);
    return (
      <span>
        {canOpenSession ? (
          <Button
            type="link"
            size="small"
            onClick={() =>
              onOpenSession?.({
                ...sessionContext,
                dataSourceId: view?.dataSourceId ?? dataSourceId,
                sessionId: sessionId!,
              })
            }
            aria-label={i18n('workspace.ops.lockOpenSession', sessionId)}
          >
            {valueText(sessionId)}
          </Button>
        ) : (
          valueText(sessionId)
        )}
        {!sessionAvailable && <Tag>{i18n('workspace.ops.sessionStale')}</Tag>}
        {engineThreadId && engineThreadId !== sessionId && (
          <Tag>{i18n('workspace.ops.engineThread', engineThreadId)}</Tag>
        )}
        {metadataLockCount > 0 && (
          <Tag color="blue">{i18n('workspace.ops.metadataLockCount', metadataLockCount)}</Tag>
        )}
      </span>
    );
  };

  const chainColumns: ColumnsType<ILockWaitChain> = [
    { title: i18n('workspace.ops.datasourceId'), dataIndex: 'dataSourceId', width: 110 },
    { title: i18n('workspace.ops.lockType'), dataIndex: 'lockKind', width: 100, render: valueText },
    { title: i18n('workspace.ops.lockObject'), dataIndex: 'lockObject', width: 220, render: valueText },
    {
      title: i18n('workspace.ops.waiterThread'),
      dataIndex: 'waiterThreadId',
      width: 180,
      render: (_, record) =>
        renderSessionThread(
          record.waiterThreadId,
          record.waiterSessionAvailable,
          record.waiterEngineThreadId,
          record.waiterMetadataLockCount,
          {
            engineThreadId: record.waiterEngineThreadId,
            databaseName: record.waiterDatabase,
            user: record.waiterUser,
            host: record.waiterHost,
            query: record.waiterQuery,
          },
        ),
    },
    { title: i18n('workspace.ops.waiterUser'), dataIndex: 'waiterUser', width: 120 },
    { title: i18n('workspace.ops.waiterState'), dataIndex: 'waiterState', width: 100 },
    { title: i18n('workspace.ops.waiterLockMode'), dataIndex: 'waiterLockMode', width: 120, render: valueText },
    { title: i18n('workspace.ops.waiterQuery'), dataIndex: 'waiterQuery', ellipsis: true },
    {
      title: i18n('workspace.ops.blockerThread'),
      dataIndex: 'blockerThreadId',
      width: 180,
      render: (_, record) =>
        renderSessionThread(
          record.blockerThreadId,
          record.blockerSessionAvailable,
          record.blockerEngineThreadId,
          record.blockerMetadataLockCount,
          {
            engineThreadId: record.blockerEngineThreadId,
            databaseName: record.blockerDatabase,
            user: record.blockerUser,
            host: record.blockerHost,
            query: record.blockerQuery,
          },
        ),
    },
    { title: i18n('workspace.ops.blockerUser'), dataIndex: 'blockerUser', width: 120 },
    { title: i18n('workspace.ops.blockerState'), dataIndex: 'blockerState', width: 100 },
    { title: i18n('workspace.ops.blockerLockMode'), dataIndex: 'blockerLockMode', width: 120, render: valueText },
    { title: i18n('workspace.ops.blockerQuery'), dataIndex: 'blockerQuery', ellipsis: true },
    {
      title: i18n('workspace.ops.role'),
      width: 130,
      render: (_, record) =>
        record.cycle ? (
          <Tag color="orange">{i18n('workspace.ops.cycle')}</Tag>
        ) : record.rootBlocker ? (
          <Tag color="red">{i18n('workspace.ops.rootBlocker')}</Tag>
        ) : (
          <Tag>{i18n('workspace.ops.lockBlocking')}</Tag>
        ),
    },
  ];

  const lockColumns: ColumnsType<IDataLock> = [
    { title: i18n('workspace.ops.lockId'), dataIndex: 'lockId', width: 200 },
    {
      title: i18n('workspace.ops.lockObject'),
      width: 220,
      render: (_, record) => valueText(lockObjectText(record.objectSchema, record.objectName)),
    },
    { title: i18n('workspace.ops.lockType'), dataIndex: 'lockType', width: 110 },
    { title: i18n('workspace.ops.lockMode'), dataIndex: 'lockMode', width: 110 },
    { title: i18n('workspace.ops.lockStatus'), dataIndex: 'lockStatus', width: 110, render: valueText },
    { title: i18n('workspace.ops.lockData'), dataIndex: 'lockData', ellipsis: true },
  ];

  const sessionColumns: ColumnsType<ILockSession> = [
    {
      title: i18n('workspace.ops.sessionId'),
      dataIndex: 'sessionId',
      width: 100,
      render: (_, record) => {
        const { sessionId, engineThreadId } = record;
        if (!onOpenSession || !sessionId) {
          return valueText(sessionId ?? engineThreadId);
        }
        return (
          <Button
            type="link"
            size="small"
            onClick={() =>
              onOpenSession({
                dataSourceId: view?.dataSourceId ?? dataSourceId,
                sessionId,
                engineThreadId,
                databaseName: record.databaseName,
                user: record.user,
                host: record.host,
                query: record.query,
              })
            }
            aria-label={i18n('workspace.ops.lockOpenSession', sessionId)}
          >
            {sessionId}
          </Button>
        );
      },
    },
    { title: i18n('workspace.ops.engineThreadId'), dataIndex: 'engineThreadId', width: 110 },
    { title: i18n('workspace.ops.user'), dataIndex: 'user', width: 120, render: valueText },
    { title: i18n('workspace.ops.host'), dataIndex: 'host', width: 160, render: valueText },
    { title: i18n('workspace.ops.database'), dataIndex: 'databaseName', width: 120, render: valueText },
    { title: i18n('workspace.ops.state'), dataIndex: 'state', width: 120, render: valueText },
    { title: i18n('workspace.ops.query'), dataIndex: 'query', ellipsis: true, render: valueText },
  ];

  const metadataColumns: ColumnsType<IMetadataLock> = [
    {
      title: i18n('workspace.ops.lockObject'),
      width: 220,
      render: (_, record) => valueText(lockObjectText(record.objectSchema, record.objectName, record.objectType)),
    },
    { title: i18n('workspace.ops.lockType'), dataIndex: 'lockType', width: 120 },
    { title: i18n('workspace.ops.lockDuration'), dataIndex: 'lockDuration', width: 120 },
    { title: i18n('workspace.ops.lockStatus'), dataIndex: 'lockStatus', width: 100, render: valueText },
    { title: i18n('workspace.ops.ownerThread'), dataIndex: 'ownerThreadId', width: 120 },
    { title: i18n('workspace.ops.sessionId'), dataIndex: 'ownerSessionId', width: 100, render: valueText },
    { title: i18n('workspace.ops.user'), dataIndex: 'ownerUser', width: 120, render: valueText },
    { title: i18n('workspace.ops.state'), dataIndex: 'ownerState', width: 120, render: valueText },
  ];

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
        <span>
          {view
            ? `${i18n('workspace.ops.datasourceId')}: ${view.dataSourceId ?? dataSourceId} · ${i18n('workspace.ops.lockSource', sourceLabel(view.source))}`
            : ''}
        </span>
        <Button size="small" onClick={load} loading={loading}>
          {i18n('common.button.refresh')}
        </Button>
        {error && <span style={{ color: 'var(--text-color-danger)' }}>{error}</span>}
      </div>
      {view && (
        <>
          <Alert
            showIcon
            type="info"
            style={{ marginBottom: 8 }}
            message={i18n('workspace.ops.lockSnapshotNotice')}
          />
          <Tabs
            items={[
              {
                key: 'chains',
                label: i18n('workspace.ops.blockingChains'),
                children: (
                  <Table
                    size="small"
                    rowKey={(row) => waitChainRowKey(row, dataSourceId)}
                    columns={chainColumns}
                    dataSource={view.waitChains}
                    loading={loading}
                    pagination={false}
                    scroll={{ x: 1700, y: 300 }}
                    locale={{ emptyText: i18n('workspace.ops.noLockWaits') }}
                  />
                ),
              },
              {
                key: 'metadataChains',
                label: i18n('workspace.ops.metadataBlockingChains', view.metadataWaitChains.length),
                children: (
                  <Table
                    size="small"
                    rowKey={(row) => waitChainRowKey(row, dataSourceId)}
                    columns={chainColumns}
                    dataSource={view.metadataWaitChains}
                    loading={loading}
                    pagination={false}
                    scroll={{ x: 1700, y: 300 }}
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
                    rowKey={dataLockRowKey}
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
                    rowKey={metadataLockRowKey}
                    columns={metadataColumns}
                    dataSource={view.metaLocks}
                    loading={loading}
                    pagination={false}
                    scroll={{ x: 800, y: 300 }}
                    locale={{ emptyText: i18n('workspace.ops.metadataLocksUnavailable') }}
                  />
                ),
              },
              {
                key: 'sessions',
                label: i18n('workspace.ops.sessions', view.sessions.length),
                children: (
                  <Table
                    size="small"
                    rowKey={(row) => row.sessionId ?? row.engineThreadId ?? row.transactionId ?? 'unknown-session'}
                    columns={sessionColumns}
                    dataSource={view.sessions}
                    loading={loading}
                    pagination={false}
                    scroll={{ x: 1000, y: 300 }}
                    locale={{ emptyText: i18n('workspace.ops.sessionsUnavailable') }}
                  />
                ),
              },
            ]}
          />
        </>
      )}
    </div>
  );
};

export default LockWaitsContent;
