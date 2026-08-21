import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Checkbox, InputNumber, Modal, Select, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import i18n from '@/i18n';
import sqlService, { IImportPreview, IImportExecuteResult, ICsvOptions } from '@/service/sql';

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
  const [csvOptions, setCsvOptions] = useState<ICsvOptions>({
    encoding: 'UTF-8',
    delimiter: ',',
    quote: '"',
    escape: '"',
    hasHeader: true,
    emptyAsNull: true,
  });
  const isCsv = filePath.toLowerCase().endsWith('.csv');
  const isExcel = /\.xlsx?$/i.test(filePath);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    setResult(null);
    sqlService
      .getImportPreview({
        dataSourceId,
        databaseName,
        tableName,
        filePath,
        csvOptions: isExcel ? csvOptions : undefined,
      })
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
  }, [dataSourceId, databaseName, tableName, filePath, isCsv, isExcel, csvOptions]);

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
      render: (values: { value: string; type: string }[]) => (
        <span style={{ color: 'var(--text-color-secondary)' }}>
          {values.slice(0, 3).map((v, i) => (
            <span key={i}>
              {i > 0 && ', '}
              {v.value}
              {v.type !== 'string' && v.type !== 'empty' && (
                <span style={{ color: 'var(--text-color-tertiary)' }}> [{v.type}]</span>
              )}
            </span>
          ))}
        </span>
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
        csvOptions: isExcel ? csvOptions : undefined,
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
      {!result && preview && isExcel && (
        <div style={{ marginBottom: 8, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <Select
            style={{ width: 160 }}
            value={csvOptions.sheetName}
            placeholder={i18n('workspace.importExport.selectSheet')}
            onChange={(v) => setCsvOptions((prev) => ({ ...prev, sheetName: v }))}
            options={(preview.sheets || []).filter((s) => s.visible).map((s) => ({ value: s.name, label: s.name }))}
          />
          <span>{i18n('workspace.importExport.startRow')}</span>
          <InputNumber
            min={0}
            value={csvOptions.startRow ?? 0}
            onChange={(v) => setCsvOptions((prev) => ({ ...prev, startRow: v ?? 0 }))}
            style={{ width: 70 }}
          />
          <span>{i18n('workspace.importExport.headerRow')}</span>
          <InputNumber
            min={0}
            value={csvOptions.headerRow ?? 0}
            onChange={(v) => setCsvOptions((prev) => ({ ...prev, headerRow: v ?? 0 }))}
            style={{ width: 70 }}
          />
          <Checkbox
            checked={csvOptions.emptyAsNull}
            onChange={(e) => setCsvOptions((prev) => ({ ...prev, emptyAsNull: e.target.checked }))}
          >
            {i18n('workspace.importExport.emptyAsNull')}
          </Checkbox>
        </div>
      )}
      {!result && preview && isCsv && (
        <div style={{ marginBottom: 8, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <Select
            style={{ width: 120 }}
            value={csvOptions.encoding}
            onChange={(v) => setCsvOptions((prev) => ({ ...prev, encoding: v }))}
            options={['UTF-8', 'GB18030', 'ISO-8859-1'].map((e) => ({ value: e, label: e }))}
          />
          <Select
            style={{ width: 90 }}
            value={csvOptions.delimiter}
            onChange={(v) => setCsvOptions((prev) => ({ ...prev, delimiter: v }))}
            options={[
              { value: ',', label: i18n('workspace.importExport.delimiterComma') },
              { value: ';', label: i18n('workspace.importExport.delimiterSemicolon') },
              { value: '\t', label: i18n('workspace.importExport.delimiterTab') },
              { value: '|', label: i18n('workspace.importExport.delimiterPipe') },
            ]}
          />
          <Checkbox
            checked={csvOptions.hasHeader}
            onChange={(e) => setCsvOptions((prev) => ({ ...prev, hasHeader: e.target.checked }))}
          >
            {i18n('workspace.importExport.hasHeader')}
          </Checkbox>
          <Checkbox
            checked={csvOptions.emptyAsNull}
            onChange={(e) => setCsvOptions((prev) => ({ ...prev, emptyAsNull: e.target.checked }))}
          >
            {i18n('workspace.importExport.emptyAsNull')}
          </Checkbox>
          <span style={{ color: 'var(--text-color-secondary)' }}>{i18n('workspace.importExport.csvOptionsHint')}</span>
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
