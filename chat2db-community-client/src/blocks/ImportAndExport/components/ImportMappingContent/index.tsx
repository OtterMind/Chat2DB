import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Checkbox, InputNumber, Modal, Select, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import i18n from '@/i18n';
import sqlService, { IImportPreview, IImportParserOptions } from '@/service/sql';
import {
  DEFAULT_IMPORT_OPTIONS,
  getImportValidationIssues,
  importOptionsKey,
  ImportValidationIssue,
  isPreviewCurrent,
} from '@/blocks/ImportAndExport/functions/importParserOptions';

interface IProps {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
  tableName: string;
  file: File;
  onSubmitted: (taskId: number) => void;
}

const SKIP = '__skip__';

interface ImportSourceColumn {
  name: string;
  sampleValues: { value: string; type: string }[];
}

/**
 * Import preview and column mapping (MYSQL-IMPORT-001). Loads a bounded preview of the
 * file, lets the user remap source fields to target columns (or skip them), chooses how
 * unmapped target columns are filled (DEFAULT or NULL), executes the import, and reports
 * row-level errors. Preview and execution share the backend parser.
 */
const validationMessage = (issue: ImportValidationIssue) => {
  const values = (issue.values || []).join(', ');
  switch (issue.code) {
    case 'duplicateHeaders':
      return i18n('workspace.importExport.duplicateHeaders', values);
    case 'duplicateTargetMapping':
      return i18n('workspace.importExport.duplicateTargetMapping', values);
    case 'invalidRange':
      return i18n('workspace.importExport.invalidRange');
    case 'noMappedColumns':
      return i18n('workspace.importExport.noMappedColumns');
    case 'requiredUnmapped':
    default:
      return i18n('workspace.importExport.requiredUnmapped');
  }
};

const ImportMappingContent = ({ dataSourceId, databaseName, schemaName, tableName, file, onSubmitted }: IProps) => {
  const [preview, setPreview] = useState<IImportPreview | null>(null);
  const [fileId, setFileId] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [unmappedTarget, setUnmappedTarget] = useState<'DEFAULT' | 'NULL'>('DEFAULT');
  const [executing, setExecuting] = useState(false);
  const [importOptions, setImportOptions] = useState<IImportParserOptions>(DEFAULT_IMPORT_OPTIONS);
  const [previewOptionsKey, setPreviewOptionsKey] = useState<string>();
  const previewRequestIdRef = useRef(0);
  const isCsv = file.name.toLowerCase().endsWith('.csv');
  const isExcel = /\.xlsx?$/i.test(file.name);

  const load = useCallback((stagedFileId: string, options: IImportParserOptions) => {
    const requestId = previewRequestIdRef.current + 1;
    previewRequestIdRef.current = requestId;
    const requestedOptionsKey = importOptionsKey(options);
    setLoading(true);
    setError(null);
    sqlService
      .getImportPreview({
        dataSourceId,
        databaseName,
        schemaName,
        tableName,
        fileId: stagedFileId,
        importOptions: options,
      })
      .then((data) => {
        if (requestId !== previewRequestIdRef.current) {
          return;
        }
        setPreview(data);
        setPreviewOptionsKey(requestedOptionsKey);
        const auto: Record<string, string> = {};
        data.suggestedMapping.forEach((m) => {
          auto[m.sourceColumn] = m.targetColumn;
        });
        setMapping(auto);
      })
      .catch((e) => {
        if (requestId !== previewRequestIdRef.current) {
          return;
        }
        setPreviewOptionsKey(undefined);
        setError(e?.message || i18n('common.text.failure'));
      })
      .finally(() => {
        if (requestId === previewRequestIdRef.current) {
          setLoading(false);
        }
      });
  }, [dataSourceId, databaseName, schemaName, tableName]);

  useEffect(() => {
    previewRequestIdRef.current += 1;
    setFileId(undefined);
    setPreview(null);
    setPreviewOptionsKey(undefined);
    setMapping({});
    setImportOptions(DEFAULT_IMPORT_OPTIONS);
    setLoading(true);
    setError(null);
    sqlService
      .uploadImportFile({ file })
      .then((id) => {
        setFileId(id);
        load(id, DEFAULT_IMPORT_OPTIONS);
      })
      .catch((e) => {
        setError(e?.message || i18n('common.text.failure'));
        setLoading(false);
      });
  }, [file, load]);

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

  const validationMessages = useMemo(() => {
    return getImportValidationIssues({
      duplicateHeaders: preview?.duplicateHeaders,
      mapping,
      blockedColumns: blockedColumns.map((column) => column.name),
      options: importOptions,
      skipValue: SKIP,
    }).map(validationMessage);
  }, [blockedColumns, importOptions, mapping, preview]);
  const previewCurrent = isPreviewCurrent(previewOptionsKey, importOptions);
  const canExecute = !!fileId && !!preview && previewCurrent && !loading && validationMessages.length === 0;

  const columns: ColumnsType<ImportSourceColumn> = [
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
    if (!canExecute) {
      Modal.error({
        title: i18n('workspace.importExport.validationFailed'),
        content: (previewCurrent ? validationMessages : [i18n('workspace.importExport.previewStale')]).join('\n'),
      });
      return;
    }
    setExecuting(true);
    setError(null);
    sqlService
      .executeImportWithMapping({
        dataSourceId,
        databaseName,
        schemaName,
        tableName,
        fileId,
        mappings: Object.entries(mapping)
          .filter(([, target]) => target && target !== SKIP)
          .map(([source, target]) => ({ sourceColumn: source, targetColumn: target })),
        unmappedTarget,
        importOptions,
      })
      .then((response) => onSubmitted(response.taskId))
      .catch((e) => setError(e?.message || i18n('common.text.failure')))
      .finally(() => setExecuting(false));
  };

  return (
    <div>
      {error && <div style={{ color: 'var(--text-color-danger)', marginBottom: 8 }}>{error}</div>}
      {preview && isExcel && (
        <div style={{ marginBottom: 8, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <Select
            style={{ width: 160 }}
            value={importOptions.sheetName || preview.selectedSheet}
            placeholder={i18n('workspace.importExport.selectSheet')}
            onChange={(v) => setImportOptions((prev) => ({ ...prev, sheetName: v }))}
            options={(preview.sheets || []).filter((s) => s.visible).map((s) => ({ value: s.name, label: s.name }))}
          />
          <Checkbox
            checked={importOptions.hasHeader}
            onChange={(e) =>
              setImportOptions((prev) => ({
                ...prev,
                hasHeader: e.target.checked,
                headerRow: e.target.checked ? prev.headerRow || 1 : 0,
              }))
            }
          >
            {i18n('workspace.importExport.hasHeader')}
          </Checkbox>
          <span>{i18n('workspace.importExport.startRow')}</span>
          <InputNumber
            min={0}
            value={importOptions.startRow ?? 0}
            onChange={(v) => setImportOptions((prev) => ({ ...prev, startRow: v ?? 0 }))}
            style={{ width: 70 }}
          />
          {importOptions.hasHeader && (
            <>
              <span>{i18n('workspace.importExport.headerRow')}</span>
              <InputNumber
                min={1}
                value={importOptions.headerRow ?? 1}
                onChange={(v) => setImportOptions((prev) => ({ ...prev, headerRow: v ?? 1 }))}
                style={{ width: 70 }}
              />
            </>
          )}
          <span>{i18n('workspace.importExport.endRow')}</span>
          <InputNumber
            min={0}
            value={importOptions.endRow ?? 0}
            onChange={(v) => setImportOptions((prev) => ({ ...prev, endRow: v ?? 0 }))}
            style={{ width: 80 }}
          />
          <Checkbox
            checked={importOptions.emptyAsNull}
            onChange={(e) => setImportOptions((prev) => ({ ...prev, emptyAsNull: e.target.checked }))}
          >
            {i18n('workspace.importExport.emptyAsNull')}
          </Checkbox>
          <Select
            style={{ width: 180 }}
            value={importOptions.formulaMode}
            onChange={(v) => setImportOptions((prev) => ({ ...prev, formulaMode: v }))}
            options={[
              { value: 'CACHED_VALUE', label: i18n('workspace.importExport.formulaCached') },
              { value: 'REJECT', label: i18n('workspace.importExport.formulaReject') },
            ]}
          />
        </div>
      )}
      {preview && isCsv && (
        <div style={{ marginBottom: 8, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <Select
            style={{ width: 120 }}
            value={importOptions.encoding}
            onChange={(v) => setImportOptions((prev) => ({ ...prev, encoding: v }))}
            options={['UTF-8', 'GB18030', 'ISO-8859-1'].map((e) => ({ value: e, label: e }))}
          />
          <Select
            style={{ width: 90 }}
            value={importOptions.delimiter}
            onChange={(v) => setImportOptions((prev) => ({ ...prev, delimiter: v }))}
            options={[
              { value: ',', label: i18n('workspace.importExport.delimiterComma') },
              { value: ';', label: i18n('workspace.importExport.delimiterSemicolon') },
              { value: '\t', label: i18n('workspace.importExport.delimiterTab') },
              { value: '|', label: i18n('workspace.importExport.delimiterPipe') },
            ]}
          />
          <Checkbox
            checked={importOptions.hasHeader}
            onChange={(e) => setImportOptions((prev) => ({ ...prev, hasHeader: e.target.checked }))}
          >
            {i18n('workspace.importExport.hasHeader')}
          </Checkbox>
          <Checkbox
            checked={importOptions.emptyAsNull}
            onChange={(e) => setImportOptions((prev) => ({ ...prev, emptyAsNull: e.target.checked }))}
          >
            {i18n('workspace.importExport.emptyAsNull')}
          </Checkbox>
          <span style={{ color: 'var(--text-color-secondary)' }}>{i18n('workspace.importExport.csvOptionsHint')}</span>
        </div>
      )}
      {preview && (
        <>
          <div style={{ marginBottom: 8, display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
            <span>{i18n('workspace.importExport.previewHint', preview.previewRows)}</span>
            {!!preview.skippedCount && (
              <span>{i18n('workspace.importExport.previewSkippedRows', preview.skippedCount)}</span>
            )}
            {preview.hasMoreRows && <span>{i18n('workspace.importExport.largeFilePreviewLimited')}</span>}
            {!!preview.invalidHeaders?.length && (
              <span style={{ color: 'var(--text-color-warning)' }}>
                {i18n('workspace.importExport.invalidHeaders', preview.invalidHeaders.join(', '))}
              </span>
            )}
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
            {validationMessages.length > 0 && (
              <span style={{ color: 'var(--text-color-danger)' }}>{validationMessages[0]}</span>
            )}
            {!previewCurrent && (
              <span style={{ color: 'var(--text-color-warning)' }}>
                {i18n('workspace.importExport.previewStale')}
              </span>
            )}
            <Button size="small" onClick={() => fileId && load(fileId, importOptions)} loading={loading}>
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
            <Button type="primary" loading={executing} disabled={!canExecute} onClick={execute}>
              {i18n('common.button.execute')}
            </Button>
          </div>
        </>
      )}
    </div>
  );
};

export default ImportMappingContent;
