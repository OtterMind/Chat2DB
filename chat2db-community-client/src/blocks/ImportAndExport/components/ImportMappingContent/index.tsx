import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Modal, Select, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import i18n from '@/i18n';
import sqlService, { IImportPreview, IImportExecuteResult } from '@/service/sql';

interface IProps {
  dataSourceId: number;
  databaseName: string;
  tableName: string;
  filePath: string;
  onDone: () => void;
}

const SKIP = '__skip__';

/**
 * Import preview and column mapping (MYSQL-IMPORT-001). Loads a bounded preview of the
 * file, lets the user remap source fields to target columns (or skip them), chooses how
 * unmapped target columns are filled (DEFAULT or NULL), executes the import, and reports
 * row-level errors. Preview and execution share the backend parser.
 */
const ImportMappingContent = ({ dataSourceId, databaseName, tableName, filePath, onDone }: IProps) => {
  const [preview, setPreview] = useState<IImportPreview | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [unmappedTarget, setUnmappedTarget] = useState<'DEFAULT' | 'NULL'>('DEFAULT');
  const [executing, setExecuting] = useState(false);
  const [result, setResult] = useState<IImportExecuteResult | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    setResult(null);
    sqlService
      .getImportPreview({ dataSourceId, databaseName, tableName, filePath })
      .then((data) => {
        setPreview(data);
        const auto: Record<string, string> = {};
        data.suggestedMapping.forEach((m) => {
          auto[m.sourceColumn] = m.targetColumn;
        });
        setMapping(auto);
      })
      .catch((e) => {
        setError(e?.message || i18n('common.text.failure'));
      })
      .finally(() => setLoading(false));
  }, [dataSourceId, databaseName, tableName, filePath]);

  useEffect(() => {
    load();
  }, [load]);

  const targetOptions = useMemo(() => {
    if (!preview) {
      return [];
    }
    return [
      { value: SKIP, label: i18n('workspace.importExport.skipSourceField') },
      ...preview.targetColumns.map((c) => ({
        value: c.name,
        label: `${c.name} (${c.dataType}${c.nullable ? '' : ', NOT NULL'})`,
      })),
    ];
  }, [preview]);

  const blockedColumns = useMemo(() => {
    if (!preview) {
      return [];
    }
    return preview.targetColumns.filter(
      (c) =>
        !c.nullable &&
        !c.autoIncrement &&
        !Object.values(mapping).includes(c.name) &&
        (unmappedTarget === 'NULL' || (c.defaultValue === null && unmappedTarget === 'DEFAULT')),
    );
  }, [preview, mapping, unmappedTarget]);

  const columns: ColumnsType<{ name: string; sampleValues: string[] }> = [
    { title: i18n('workspace.importExport.sourceField'), dataIndex: 'name', width: 180 },
    {
      title: i18n('workspace.importExport.sampleValues'),
      dataIndex: 'sampleValues',
      render: (values: string[]) => (
        <span style={{ color: 'var(--text-color-secondary)' }}>{values.slice(0, 3).join(', ')}</span>
      ),
    },
    {
      title: i18n('workspace.importExport.targetColumn'),
      width: 260,
      render: (_, record) => (
        <Select
          style={{ width: '100%' }}
          value={mapping[record.name]}
          options={targetOptions}
          onChange={(v) => setMapping((prev) => ({ ...prev, [record.name]: v }))}
        />
      ),
    },
  ];

  const execute = () => {
    if (blockedColumns.length > 0) {
      Modal.error({
        title: i18n('workspace.importExport.requiredUnmapped'),
        content: blockedColumns.map((c) => `${c.name} (${c.dataType})`).join(', '),
      });
      return;
    }
    setExecuting(true);
    setError(null);
    sqlService
      .executeImportWithMapping({
        dataSourceId,
        databaseName,
        tableName,
        filePath,
        mappings: Object.entries(mapping)
          .filter(([, target]) => target && target !== SKIP)
          .map(([source, target]) => ({ sourceColumn: source, targetColumn: target })),
        unmappedTarget,
      })
      .then(setResult)
      .catch((e) => setError(e?.message || i18n('common.text.failure')))
      .finally(() => setExecuting(false));
  };

  return (
    <div>
      {error && <div style={{ color: 'var(--text-color-danger)', marginBottom: 8 }}>{error}</div>}
      {result && (
        <div style={{ marginBottom: 8 }}>
          <span>
            {i18n('workspace.importExport.importSummary', result.totalRows, result.successCount, result.failedCount)}
          </span>
          {result.failedCount > 0 && (
            <Table
              size="small"
              style={{ marginTop: 8 }}
              rowKey={(r) => `${r.row}-${r.column ?? ''}`}
              pagination={{ pageSize: 5 }}
              columns={[
                { title: i18n('workspace.importExport.errorRow'), dataIndex: 'row', width: 80 },
                { title: i18n('workspace.importExport.errorColumn'), dataIndex: 'column', width: 160 },
                { title: i18n('workspace.importExport.errorMessage'), dataIndex: 'message' },
              ]}
              dataSource={result.errors}
            />
          )}
          <div style={{ marginTop: 8 }}>
            <Button type="primary" onClick={onDone}>
              {i18n('common.button.close')}
            </Button>
          </div>
        </div>
      )}
      {!result && preview && (
        <>
          <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
            <span>{i18n('workspace.importExport.previewHint', preview.previewRows)}</span>
            <Select
              style={{ width: 200 }}
              value={unmappedTarget}
              onChange={(v) => setUnmappedTarget(v)}
              options={[
                { value: 'DEFAULT', label: i18n('workspace.importExport.unmappedDefault') },
                { value: 'NULL', label: i18n('workspace.importExport.unmappedNull') },
              ]}
            />
            {blockedColumns.length > 0 && (
              <span style={{ color: 'var(--text-color-danger)' }}>
                {i18n('workspace.importExport.requiredUnmapped')}: {blockedColumns.map((c) => c.name).join(', ')}
              </span>
            )}
            <Button size="small" onClick={load} loading={loading}>
              {i18n('common.button.refresh')}
            </Button>
          </div>
          <Table
            size="small"
            rowKey="name"
            columns={columns}
            dataSource={preview.sourceColumns}
            loading={loading}
            pagination={false}
            scroll={{ x: 700, y: 260 }}
          />
          <div style={{ marginTop: 12, textAlign: 'right' }}>
            <Button type="primary" loading={executing} onClick={execute}>
              {i18n('common.button.execute')}
            </Button>
          </div>
        </>
      )}
    </div>
  );
};

export default ImportMappingContent;
