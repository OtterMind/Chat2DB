import { useCallback, useEffect, useState } from 'react';
import { Button, InputNumber, Modal, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import i18n from '@/i18n';
import sqlService, { IPartitionItem } from '@/service/sql';

const RANGE_LIST_METHODS = ['RANGE', 'RANGE COLUMNS', 'LIST', 'LIST COLUMNS'];
const HASH_KEY_METHODS = ['HASH', 'LINEAR HASH', 'KEY', 'LINEAR KEY'];

/**
 * Table partition inspection and maintenance (MYSQL-OBJ-009). Operations are limited per
 * partition type: DROP/TRUNCATE for RANGE/LIST, COALESCE for HASH/KEY, and
 * ANALYZE/CHECK/OPTIMIZE for all — each with a SQL preview and destructive confirmation.
 */
const PartitionsContent = ({
  dataSourceId,
  databaseName,
  tableName,
}: {
  dataSourceId: number;
  databaseName: string;
  tableName: string;
}) => {
  const [data, setData] = useState<IPartitionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [coalesceCount, setCoalesceCount] = useState<number>(1);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    sqlService
      .getPartitionList({ dataSourceId, databaseName, tableName })
      .then((list) => setData(list || []))
      .catch((e) => {
        setData([]);
        setError(e?.message || i18n('common.text.failure'));
      })
      .finally(() => setLoading(false));
  }, [databaseName, tableName]);

  useEffect(() => {
    load();
  }, [load]);

  const confirmAndExecute = (title: string, sql: Promise<string>, destructive: boolean) => {
    sql.then((preview) => {
      Modal.confirm({
        title,
        content: (
          <div>
            {destructive && <div style={{ color: 'var(--text-color-danger)', marginBottom: 8 }}>{i18n('workspace.ops.partitionDestructiveHint')}</div>}
            <pre style={{ whiteSpace: 'pre-wrap' }}>{preview}</pre>
          </div>
        ),
        okText: i18n('common.button.execute'),
        cancelText: i18n('common.button.cancel'),
        onOk: () =>
          sqlService
            .executeDDL({ dataSourceId, sql: preview } as never)
            .then(() => {
              load();
            })
            .catch((e) => Modal.error({ title: i18n('workspace.ops.partitionFailed'), content: e?.message })),
      });
    }).catch((e) => Modal.error({ title: i18n('workspace.ops.partitionFailed'), content: e?.message }));
  };

  const baseRequest = { dataSourceId, databaseName, tableName };

  const columns: ColumnsType<IPartitionItem> = [
    { title: i18n('workspace.ops.partitionName'), dataIndex: 'partitionName', width: 150 },
    { title: i18n('workspace.ops.partitionMethod'), dataIndex: 'method', width: 140 },
    { title: i18n('workspace.ops.partitionExpression'), dataIndex: 'expression', width: 180 },
    { title: i18n('workspace.ops.partitionBoundary'), dataIndex: 'description', width: 160 },
    { title: i18n('workspace.ops.partitionRows'), dataIndex: 'tableRows', width: 90 },
    {
      title: i18n('workspace.ops.partitionSize'),
      width: 100,
      render: (_, r) => {
        const bytes = (r.dataLength ?? 0) + (r.indexLength ?? 0);
        return bytes > 0 ? `${(bytes / 1024 / 1024).toFixed(2)} MB` : '-';
      },
    },
    {
      title: i18n('workspace.ops.partitionAction'),
      width: 260,
      render: (_, r) => {
        const method = (r.method || '').toUpperCase();
        return (
          <>
            {RANGE_LIST_METHODS.includes(method) && (
              <>
                <Button
                  size="small"
                  danger
                  style={{ marginRight: 4 }}
                  onClick={() =>
                    confirmAndExecute(
                      i18n('workspace.ops.partitionDrop'),
                      sqlService.getPartitionDropSql({ ...baseRequest, partitionName: r.partitionName! }),
                      true,
                    )
                  }
                >
                  {i18n('workspace.ops.partitionDrop')}
                </Button>
                <Button
                  size="small"
                  danger
                  style={{ marginRight: 4 }}
                  onClick={() =>
                    confirmAndExecute(
                      i18n('workspace.ops.partitionTruncate'),
                      sqlService.getPartitionTruncateSql({ ...baseRequest, partitionName: r.partitionName! }),
                      true,
                    )
                  }
                >
                  {i18n('workspace.ops.partitionTruncate')}
                </Button>
              </>
            )}
            <Button
              size="small"
              style={{ marginRight: 4 }}
              onClick={() =>
                confirmAndExecute(
                  i18n('workspace.ops.partitionAnalyze'),
                  sqlService.getPartitionMaintainSql({ ...baseRequest, operation: 'ANALYZE', partitionName: r.partitionName! }),
                  false,
                )
              }
            >
              {i18n('workspace.ops.partitionAnalyze')}
            </Button>
            <Button
              size="small"
              style={{ marginRight: 4 }}
              onClick={() =>
                confirmAndExecute(
                  i18n('workspace.ops.partitionCheck'),
                  sqlService.getPartitionMaintainSql({ ...baseRequest, operation: 'CHECK', partitionName: r.partitionName! }),
                  false,
                )
              }
            >
              {i18n('workspace.ops.partitionCheck')}
            </Button>
            <Button
              size="small"
              onClick={() =>
                confirmAndExecute(
                  i18n('workspace.ops.partitionOptimize'),
                  sqlService.getPartitionMaintainSql({ ...baseRequest, operation: 'OPTIMIZE', partitionName: r.partitionName! }),
                  false,
                )
              }
            >
              {i18n('workspace.ops.partitionOptimize')}
            </Button>
          </>
        );
      },
    },
  ];

  const method = (data[0]?.method || '').toUpperCase();
  const isHashKey = HASH_KEY_METHODS.includes(method);

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
        <span>
          {data.length ? i18n('workspace.ops.partitionMethodHint', method) : ''}
          {!data.length && !error ? i18n('workspace.ops.partitionEmpty') : ''}
        </span>
        {isHashKey && (
          <>
            <InputNumber
              min={1}
              value={coalesceCount}
              onChange={(v) => setCoalesceCount(v ?? 1)}
              style={{ width: 80 }}
            />
            <Button
              size="small"
              onClick={() =>
                confirmAndExecute(
                  i18n('workspace.ops.partitionCoalesce'),
                  sqlService.getPartitionCoalesceSql({ ...baseRequest, count: coalesceCount }),
                  false,
                )
              }
            >
              {i18n('workspace.ops.partitionCoalesce')}
            </Button>
          </>
        )}
        <Button size="small" onClick={load} loading={loading}>
          {i18n('common.button.refresh')}
        </Button>
        {error && <span style={{ color: 'var(--text-color-danger)' }}>{error}</span>}
      </div>
      <Table
        size="small"
        rowKey={(r) => `${r.partitionName ?? ''}-${r.subpartitionName ?? ''}`}
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={false}
        scroll={{ x: 1100, y: 380 }}
      />
    </div>
  );
};

export default PartitionsContent;
