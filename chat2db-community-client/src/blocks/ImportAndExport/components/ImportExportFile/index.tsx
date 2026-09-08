import { memo, useMemo, useState, forwardRef, ForwardedRef, useImperativeHandle, useEffect } from 'react';
import { useStyles } from './style';
import UploadLocalFile, { type FileUrl } from '@/components/UploadLocalFile';
import { Alert, Checkbox, Form, Input, Select, InputNumber, Switch, Table, Tooltip } from 'antd';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import { IconButton } from '@chat2db/ui';
import { ImportExportType, ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import importExportServices, { ExportTaskParams, ImportTaskParams } from '@/service/importExport';
import { IImportPreview, ImportExecutionMode } from '@/typings/importExport';
import { isDesktop } from '@/utils/env';
import jcefApi from '@/jcef';
import { CircleHelp } from 'lucide-react';
import { buildTaskParams, initialFileType, type ImportExportFormValue } from './taskParams';
import sqlService from '@/service/sql';
import { stageSelectedImportFile } from '../ImportMappingContent/fileStaging';
import { getImportPreviewErrorMessage } from '../ImportMappingContent/mapping';
import { getImportMappingIssues, mergeImportColumnMappings } from './mappingValidation';

interface IProps {
  className?: string;
  setIsReady?: (p: boolean) => void;
}

export interface ImportExportFileRef {
  getValues: () => ExportTaskParams | ImportTaskParams | null;
}

const exportTypeOptions = [
  { label: 'CSV', value: ImportExportFileType.CSV, accept: '.csv' },
  { label: 'XLSX', value: ImportExportFileType.XLSX, accept: '.xlsx' },
  { label: 'XLS', value: ImportExportFileType.XLS, accept: '.xls' },
  { label: 'JSON', value: ImportExportFileType.JSON, accept: '.json' },
  { label: 'SQL', value: ImportExportFileType.SQL, accept: '.sql' },
];

// The import backend parses these formats; NDJSON/Markdown are export-only.
const importTypeOptions = exportTypeOptions.filter(
  (option) => option.value !== ImportExportFileType.NDJSON && option.value !== ImportExportFileType.MARKDOWN,
);

// Formats that can be checkpointed for resumable export.
const checkpointableFormats = [
  ImportExportFileType.CSV,
  ImportExportFileType.NDJSON,
  ImportExportFileType.MARKDOWN,
  ImportExportFileType.SQL,
];

const ImportExportFile = forwardRef((props: IProps, ref: ForwardedRef<ImportExportFileRef>) => {
  const { setIsReady } = props;
  const { styles } = useStyles();
  const [form] = Form.useForm();
  const { importExportDataBoundInfo } = useImportExportStore((state) => {
    return {
      importExportDataBoundInfo: state.importExportDataBoundInfo,
    };
  });
  const defaultFileType = importExportDataBoundInfo
    ? initialFileType(importExportDataBoundInfo)
    : ImportExportFileType.CSV;
  const [selectedFile, setSelectedFile] = useState<FileUrl>();
  const [stagedFile, setStagedFile] = useState<{ selection: FileUrl; id: string }>();
  const [previewLoading, setPreviewLoading] = useState(false);
  const [importError, setImportError] = useState<string>();
  const [exportLocation, setExportLocation] = useState<string>('');
  const [importPreview, setImportPreview] = useState<IImportPreview | null>(null);
  const [columnMappings, setColumnMappings] = useState<Record<string, string | undefined>>({});
  const [formValue, setFormValue] = useState<ImportExportFormValue>({
    exportType: defaultFileType,
    containsHeader: true,
  });
  const [mode, setMode] = useState<ImportExecutionMode>('STANDARD');
  const [confirmedNoStrongRelations, setConfirmedNoStrongRelations] = useState(false);

  const isImport = importExportDataBoundInfo?.type === ImportExportType.IMPORT;
  const isExport = importExportDataBoundInfo?.type === ImportExportType.EXPORT;
  const isTableTarget = importExportDataBoundInfo?.targetScope === 'TABLE';
  const fileTypeOptions = importExportDataBoundInfo?.fileType
    ? exportTypeOptions.filter((option) => option.value === importExportDataBoundInfo.fileType)
    : isImport
    ? importTypeOptions
    : exportTypeOptions;

  useEffect(() => {
    if (importExportDataBoundInfo) {
      const { dataSourceName, databaseName, schemaName, tableName } = importExportDataBoundInfo;
      const tableNameDisplay = [dataSourceName, databaseName, schemaName, tableName].filter(Boolean).join('/');
      const exportType = initialFileType(importExportDataBoundInfo);
      const initialValues: ImportExportFormValue = {
        exportType,
        containsHeader: true,
      };
      setFormValue(initialValues);
      setSelectedFile(undefined);
      setStagedFile(undefined);
      setImportError(undefined);
      setExportLocation('');
      setImportPreview(null);
      setColumnMappings({});
      setMode('STANDARD');
      setConfirmedNoStrongRelations(false);
      form.resetFields();
      form.setFieldsValue({
        tableNameDisplay: tableNameDisplay,
        ...initialValues,
      });
    }
  }, [form, importExportDataBoundInfo]);

  // Gets the corresponding file type based on the export type
  const uploadLocalFileAccept = useMemo(() => {
    return formValue.exportType ? exportTypeOptions.find((item) => item.value === formValue.exportType)?.accept : '';
  }, [formValue.exportType]);

  // Both browser and desktop selections use the server's opaque staged-file contract.
  useEffect(() => {
    setStagedFile(undefined);
    setImportPreview(null);
    setImportError(undefined);
    if (!selectedFile) return;
    let cancelled = false;
    stageSelectedImportFile(selectedFile, sqlService.uploadImportFile, sqlService.stageDesktopImportFile)
      .then((id) => {
        if (!cancelled) setStagedFile({ selection: selectedFile, id });
      })
      .catch((error) => {
        if (!cancelled) setImportError(getImportPreviewErrorMessage(error, i18n('common.text.failure')));
      });
    return () => {
      cancelled = true;
    };
  }, [selectedFile]);

  // Previews the selected import file once both the file and the format are known, so the
  // column mapping panel below reflects what the backend will actually import.
  const fileId = stagedFile?.selection === selectedFile ? stagedFile?.id : undefined;
  const previewableFormat =
    isImport &&
    isTableTarget &&
    [ImportExportFileType.CSV, ImportExportFileType.XLS, ImportExportFileType.XLSX].includes(formValue.exportType);
  useEffect(() => {
    setImportPreview(null);
    setPreviewLoading(false);
    if (!previewableFormat || !fileId || !importExportDataBoundInfo) {
      setImportPreview(null);
      return () => {};
    }
    let cancelled = false;
    setPreviewLoading(true);
    setImportError(undefined);
    const { dataSourceId, databaseName, schemaName, tableName } = importExportDataBoundInfo;
    importExportServices
      .previewImport({
        dataSourceId,
        databaseName,
        schemaName,
        taskType: ImportExportTaskType.DATA_FILE_IMPORT,
        format: formValue.exportType,
        tableName,
        fileId,
        mode,
        confirmedNoStrongRelations,
        options:
          formValue.exportType === ImportExportFileType.CSV
            ? {
                charset: formValue.charset || undefined,
                delimiter: formValue.delimiter || undefined,
                quoteChar: formValue.quoteChar || undefined,
              }
            : undefined,
      })
      .then((preview) => {
        if (cancelled) return;
        setImportPreview(preview);
        setColumnMappings((current) => mergeImportColumnMappings(preview, current));
      })
      .catch((error) => {
        if (!cancelled) setImportError(getImportPreviewErrorMessage(error, i18n('common.text.failure')));
      })
      .finally(() => {
        if (!cancelled) setPreviewLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [
    previewableFormat,
    fileId,
    formValue.exportType,
    formValue.charset,
    formValue.delimiter,
    formValue.quoteChar,
    mode,
    confirmedNoStrongRelations,
    importExportDataBoundInfo,
  ]);

  const targetColumnOptions = useMemo(() => {
    if (!importPreview) return [];
    const names = new Set<string>();
    importPreview.columnMatches.forEach((match) => {
      if (match.tableColumn) names.add(match.tableColumn);
    });
    importPreview.missingTableColumns.forEach((name) => names.add(name));
    return Array.from(names)
      .sort()
      .map((name) => ({ label: name, value: name }));
  }, [importPreview]);

  const mappingIssues = getImportMappingIssues(importPreview, columnMappings, formValue.unmappedTarget);
  const admission = importPreview?.parallelAdmission;
  const unsupportedParallelFormat =
    isImport && mode === 'ULTRA_FAST' && formValue.exportType !== ImportExportFileType.CSV;
  const parallelForbidden =
    isImport &&
    mode === 'ULTRA_FAST' &&
    (unsupportedParallelFormat || !confirmedNoStrongRelations || admission?.verdict === 'PARALLEL_FORBIDDEN');
  const importReady =
    !!fileId &&
    !importError &&
    !previewLoading &&
    !parallelForbidden &&
    (!previewableFormat ||
      (!!importPreview && !mappingIssues.duplicate && !mappingIssues.empty && !mappingIssues.required.length));
  useEffect(() => {
    if (isImport) setIsReady?.(importReady);
  }, [isImport, importReady, setIsReady]);

  useEffect(() => {
    if (isExport) {
      setIsReady?.(!isDesktop || !!exportLocation);
    }
  }, [exportLocation, isExport, setIsReady, importExportDataBoundInfo]);

  const handleFileUrlListChange = (files: FileUrl[]) => {
    setIsReady?.(false);
    setColumnMappings({});
    setSelectedFile(files[0]);
  };

  useImperativeHandle(ref, () => ({
    getValues: () => {
      if (!importExportDataBoundInfo) return null;
      if (isImport && !importReady) return null;
      const params = buildTaskParams({
        boundInfo: importExportDataBoundInfo,
        formValue,
        mode,
        sourceFile: '',
        exportLocation,
        desktop: isDesktop,
        importPreview,
        columnMappings,
        checkpointableFormats,
        confirmedNoStrongRelations,
      });
      return isImport
        ? ({
            ...params,
            sourceFile: undefined,
            fileId,
            displayFileName: selectedFile?.fileName || selectedFile?.file?.name,
          } as ImportTaskParams)
        : params;
    },
  }));

  const handleFormChange = (changedValues, allValues) => {
    if (changedValues.exportType) {
      setSelectedFile(undefined);
      setColumnMappings({});
      setIsReady?.(false);
    }
    if (['charset', 'delimiter', 'quoteChar'].some((key) => key in changedValues)) {
      setImportPreview(null);
      setIsReady?.(false);
    }
    const nextValue: ImportExportFormValue = {
      ...formValue,
      ...allValues,
    };
    if (
      changedValues.compression ||
      (changedValues.exportType && !checkpointableFormats.includes(changedValues.exportType))
    ) {
      form.setFieldValue('checkpointRows', undefined);
      nextValue.checkpointRows = undefined;
    }
    setFormValue(nextValue);
  };

  const handleSelectExportLocation = async () => {
    const fileName = await jcefApi?.selectDirectory();
    if (!fileName) return;
    setExportLocation(fileName);
  };

  const handleModeToggle = (checked: boolean) => {
    setMode(checked ? 'ULTRA_FAST' : 'STANDARD');
    if (!checked) setConfirmedNoStrongRelations(false);
  };

  const ultraModeTooltip = (
    <div className={styles.modeTooltip}>
      <div>{i18n('workspace.importExport.ultraModeHint')}</div>
      <div className={styles.modeTooltipTitle}>{i18n('workspace.importExport.ultraModeConfirmIntro')}</div>
      <ul>
        <li>{i18n('workspace.importExport.ultraModeBenefit1')}</li>
        <li>{i18n('workspace.importExport.ultraModeBenefit2')}</li>
        <li>{i18n('workspace.importExport.ultraModeBenefit3')}</li>
        <li>{i18n('workspace.importExport.ultraModeRisk1')}</li>
        <li>{i18n('workspace.importExport.ultraModeRisk2')}</li>
        <li>{i18n('workspace.importExport.ultraModeRisk3')}</li>
      </ul>
    </div>
  );

  return (
    <Form
      className={styles.form}
      layout="vertical"
      form={form}
      autoComplete="off"
      onValuesChange={handleFormChange}
      initialValues={formValue}
    >
      <Form.Item label={`${i18n('workspace.importExport.fileType')}:`} name="exportType">
        <Select disabled={!!importExportDataBoundInfo?.fileType} options={fileTypeOptions} />
      </Form.Item>
      {isExport && isDesktop && (
        <Form.Item label={`${i18n('workspace.importExport.exportLocation')}:`} name="exportLocation">
          <div className={styles.exportLocationBox}>
            <Input
              autoComplete="off"
              disabled
              placeholder={i18n('workspace.importExport.exportLocation')}
              value={exportLocation}
            />
            <IconButton
              className={styles.iconButton}
              size={{ boxSize: 30, iconSize: 22, borderRadius: 6 }}
              code="icon-folder"
              title={i18n('workspace.importExport.exportLocation')}
              onClick={handleSelectExportLocation}
            />
          </div>
        </Form.Item>
      )}
      {isImport && (
        <Form.Item className={styles.fullWidth} label={`${i18n('workspace.importExport.sourceFile')}:`}>
          <UploadLocalFile fileUrlListChange={handleFileUrlListChange} accept={uploadLocalFileAccept} fileSize={50} />
        </Form.Item>
      )}
      {isImport && formValue.exportType === ImportExportFileType.CSV && (
        <>
          <Form.Item label={`${i18n('workspace.importExport.charset')}:`} name="charset">
            <Input autoComplete="off" placeholder={i18n('workspace.importExport.auto')} />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.delimiter')}:`} name="delimiter">
            <Input autoComplete="off" maxLength={1} placeholder={i18n('workspace.importExport.auto')} />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.quoteChar')}:`} name="quoteChar">
            <Input autoComplete="off" maxLength={1} placeholder='"' />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.skipRows')}:`} name="skipRows">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.nullString')}:`} name="nullString">
            <Input autoComplete="off" placeholder="\\N" />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.onError')}:`} name="onError">
            <Select
              allowClear
              placeholder="ABORT"
              options={[
                { label: 'ABORT', value: 'ABORT' },
                { label: 'SKIP', value: 'SKIP' },
              ]}
            />
          </Form.Item>
          {formValue.onError === 'SKIP' && (
            <Form.Item label={`${i18n('workspace.importExport.maxErrors')}:`} name="maxErrors">
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
          )}
        </>
      )}
      {isImport && importError && <Alert type="error" showIcon message={importError} />}
      {isImport && importPreview && (
        <div className={styles.previewPanel}>
          <div className={styles.previewTitle}>{i18n('workspace.importExport.preview.columnMapping')}</div>
          <Select
            aria-label={i18n('workspace.importExport.unmapped')}
            value={formValue.unmappedTarget || 'DEFAULT'}
            options={[
              { value: 'DEFAULT', label: i18n('workspace.importExport.unmappedDefault') },
              { value: 'NULL', label: i18n('workspace.importExport.unmappedNull') },
            ]}
            onChange={(unmappedTarget) => setFormValue((previous) => ({ ...previous, unmappedTarget }))}
          />
          {(importPreview.detectedCharset || importPreview.detectedDelimiter) && (
            <div className={styles.previewMeta}>
              {importPreview.detectedCharset && (
                <span>
                  {i18n('workspace.importExport.charset')}: {importPreview.detectedCharset}
                </span>
              )}
              {importPreview.detectedDelimiter && (
                <span>
                  {i18n('workspace.importExport.delimiter')}: {importPreview.detectedDelimiter}
                </span>
              )}
            </div>
          )}
          {importPreview.columnMatches.map((match) => (
            <div key={match.fileColumn} className={styles.previewRow}>
              <span className={styles.previewFileColumn} title={match.fileColumn}>
                {match.fileColumn}
              </span>
              <Select
                allowClear
                size="small"
                placeholder={i18n('workspace.importExport.preview.ignore')}
                value={columnMappings[match.fileColumn]}
                options={targetColumnOptions}
                status={mappingIssues.duplicate ? 'error' : undefined}
                onChange={(value) => setColumnMappings((previous) => ({ ...previous, [match.fileColumn]: value }))}
              />
            </div>
          ))}
          {mappingIssues.duplicate && (
            <Alert type="error" message={i18n('workspace.importExport.duplicateMappingTitle')} />
          )}
          {mappingIssues.required.length > 0 && (
            <Alert
              type="error"
              message={`${i18n('workspace.importExport.requiredUnmapped')}: ${mappingIssues.required
                .map((column) => column.name)
                .join(', ')}`}
            />
          )}
          {mappingIssues.unmapped.length > 0 && (
            <div className={styles.previewWarning}>
              {i18n('workspace.importExport.preview.unmatchedColumns')}:{' '}
              {mappingIssues.unmapped.map((column) => column.name).join(', ')}
            </div>
          )}
          <div className={styles.previewTitle}>
            {i18n('workspace.importExport.dataPreview', importPreview.sampleRows.length)}
          </div>
          <Table<{ key: number; values: string[] }>
            size="small"
            pagination={false}
            scroll={{ x: 'max-content', y: 200 }}
            rowKey="key"
            dataSource={importPreview.sampleRows.map((values, key) => ({ key, values }))}
            columns={importPreview.fileColumns.map((title, index) => ({
              title,
              width: 160,
              render: (_, row) => row.values[index],
            }))}
          />
        </div>
      )}
      <Form.Item
        className={styles.fullWidth}
        label={`${
          isTableTarget
            ? i18n('workspace.importExport.targetTable')
            : i18n('workspace.importExport.executionEnvironment')
        }:`}
        name="tableNameDisplay"
      >
        <Input autoComplete="off" disabled />
      </Form.Item>
      <Form.Item className={styles.fullWidth} label={i18n('workspace.importExport.ultraMode')}>
        <div className={styles.modeControl}>
          <Switch checked={mode === 'ULTRA_FAST'} onChange={handleModeToggle} />
          <Tooltip title={ultraModeTooltip} mouseEnterDelay={0.2} styles={{ root: { maxWidth: 440 } }}>
            <button
              aria-label={i18n('workspace.importExport.ultraModeHint')}
              className={styles.modeHelpButton}
              type="button"
            >
              <CircleHelp aria-hidden size={16} />
            </button>
          </Tooltip>
        </div>
      </Form.Item>
      {isImport && mode === 'ULTRA_FAST' && (
        <div className={styles.admissionPanel}>
          <Alert
            type={parallelForbidden ? 'error' : admission?.verdict === 'PARALLEL_DEGRADED' ? 'warning' : 'info'}
            showIcon
            message={
              unsupportedParallelFormat
                ? i18n('workspace.importExport.parallelFormatUnavailable')
                : admission
                ? `${i18n('workspace.importExport.parallelAdmission')}: ${admission.verdict}`
                : i18n('workspace.importExport.parallelAdmissionPending')
            }
            description={
              admission?.findings.length
                ? `${i18n('workspace.importExport.parallelAdmissionRules')}: ${admission.findings
                    .map((finding) => `[${finding.code}]`)
                    .join(', ')}. ${i18n('workspace.importExport.parallelAdmissionEffectiveMode')}: ${
                    admission.effectiveMode
                  }`
                : undefined
            }
          />
          <Checkbox
            checked={confirmedNoStrongRelations}
            onChange={(event) => setConfirmedNoStrongRelations(event.target.checked)}
          >
            {i18n('workspace.importExport.ultraModeAcknowledge')}
          </Checkbox>
        </div>
      )}
      <div className={styles.modeIndicator}>
        {mode === 'ULTRA_FAST'
          ? i18n('workspace.importExport.modeBadgeUltra')
          : i18n('workspace.importExport.modeBadgeStandard')}
      </div>
      {/* <Form.Item name="containsHeader" valuePropName="checked">
        <Checkbox>{i18n('workspace.importExport.containsHeader')}</Checkbox>
      </Form.Item> */}
    </Form>
  );
});

export default memo(ImportExportFile);
