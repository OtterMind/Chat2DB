import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Collapse, Modal, Spin } from 'antd';
import { TriangleAlert } from 'lucide-react';
import {
  ImportExportFileType,
  ImportPreviewErrorCode,
  ImportUnmappedTarget,
  SKIP_IMPORT_SOURCE_FIELD,
} from '@/constants/importExport';
import i18n from '@/i18n';
import sqlService, { IImportPreview } from '@/service/sql';
import type { ICsvOptions } from '@/typings/importExport';
import {
  buildInitialImportMapping,
  getDuplicateImportMappings,
  getImportPreviewErrorMessage,
} from './mapping';
import { useStyles } from './style';
import type { FileUrl } from '@/components/UploadLocalFile';
import { stageSelectedImportFile } from './fileStaging';
import CsvOptionsSections from './CsvOptionsSections';
import useImportDataSections from './ImportDataSections';
import {
  buildCsvOptionsForTaskSubmit,
  DEFAULT_CSV_OPTIONS,
  inferImportFileFormat,
} from '../../utils/csvOptions';

interface IProps {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
  tableName: string;
  file: FileUrl;
  onSubmitted: (taskId: number) => void;
}

/**
 * Database-independent import preview and column mapping. Loads a bounded preview of the
 * file, lets the user remap source fields to target columns (or skip them), chooses how
 * unmapped target columns are filled (DEFAULT or NULL), executes the import, and reports
 * task progress. Preview and execution share the backend parser.
 */
const ImportMappingContent = ({ dataSourceId, databaseName, schemaName, tableName, file, onSubmitted }: IProps) => {
  const { styles } = useStyles();
  const [modal, modalContextHolder] = Modal.useModal();
  const [preview, setPreview] = useState<IImportPreview | null>(null);
  const [fileId, setFileId] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [unmappedTarget, setUnmappedTarget] = useState(ImportUnmappedTarget.DEFAULT);
  const [executing, setExecuting] = useState(false);
  const [activeSections, setActiveSections] = useState<string[]>(['mapping', 'preview']);
  const [csvOptions, setCsvOptions] = useState<ICsvOptions>(DEFAULT_CSV_OPTIONS);
  const selectedFileName = file.fileName || file.file?.name || file.filePath || '';
  const isCsv = inferImportFileFormat(selectedFileName) === ImportExportFileType.CSV;
  const currentPreviewKey = JSON.stringify({
    dataSourceId,
    databaseName,
    schemaName,
    tableName,
    fileId,
    csvOptions: isCsv ? csvOptions : undefined,
  });
  const [loadedPreviewKey, setLoadedPreviewKey] = useState<string>();
  const resolveErrorMessage = useCallback(
    (requestError: unknown) =>
      getImportPreviewErrorMessage(requestError, i18n('common.text.failure'), {
        [ImportPreviewErrorCode.DUPLICATE_SOURCE_COLUMNS]: i18n('workspace.importExport.duplicateSourceColumns'),
        [ImportPreviewErrorCode.INVALID_CSV_OPTIONS]: i18n('workspace.importExport.invalidCsvOptions'),
      }),
    [],
  );

  useEffect(() => {
    let active = true;
    setFileId(undefined);
    setLoading(true);
    setError(null);
    stageSelectedImportFile(file, sqlService.uploadImportFile, sqlService.stageDesktopImportFile)
      .then((id) => {
        if (active) {
          setFileId(id);
        }
      })
      .catch((e) => {
        if (active) {
          setError(resolveErrorMessage(e));
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [file, resolveErrorMessage]);

  useEffect(() => {
    if (!fileId) {
      return;
    }
    let active = true;
    let validatedCsvOptions: ICsvOptions | undefined;
    try {
      validatedCsvOptions = buildCsvOptionsForTaskSubmit(isCsv, csvOptions);
    } catch (e) {
      setPreview(null);
      setLoadedPreviewKey(undefined);
      setMapping({});
      setError(resolveErrorMessage(e));
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    sqlService
      .getImportPreview({
        dataSourceId,
        databaseName,
        schemaName,
        tableName,
        fileId,
        csvOptions: validatedCsvOptions,
      })
      .then((data) => {
        if (!active) {
          return;
        }
        setPreview(data);
        setLoadedPreviewKey(currentPreviewKey);
        setMapping(buildInitialImportMapping(data.sourceColumns, data.suggestedMapping));
      })
      .catch((e) => {
        if (active) {
          setPreview(null);
          setLoadedPreviewKey(undefined);
          setMapping({});
          setError(resolveErrorMessage(e));
        }
      })
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [
    currentPreviewKey,
    csvOptions,
    dataSourceId,
    databaseName,
    fileId,
    isCsv,
    resolveErrorMessage,
    schemaName,
    tableName,
  ]);

  const blockedColumns = useMemo(() => {
    if (!preview) return [];
    return preview.targetColumns.filter(
      (column) =>
        !column.nullable &&
        !column.autoIncrement &&
        !Object.values(mapping).includes(column.name) &&
        (unmappedTarget === ImportUnmappedTarget.NULL ||
          (column.defaultValue === null && unmappedTarget === ImportUnmappedTarget.DEFAULT)),
    );
  }, [preview, mapping, unmappedTarget]);
  const duplicateMappings = getDuplicateImportMappings(mapping);
  const dataSectionItems = useImportDataSections({
    preview,
    mapping,
    duplicateMappings,
    blockedColumnNames: new Set(blockedColumns.map(({ name }) => name)),
    unmappedTarget,
    csvOptions,
    isCsv,
    loading,
    onMappingChange: (sourceColumn, targetColumn) =>
      setMapping((current) => ({ ...current, [sourceColumn]: targetColumn })),
    onUnmappedTargetChange: setUnmappedTarget,
    onCsvOptionsChange: setCsvOptions,
  });

  const execute = () => {
    const duplicateMapping = Object.values(duplicateMappings)[0];
    if (duplicateMapping) {
      modal.error({
        title: i18n('workspace.importExport.duplicateMappingTitle'),
        content: i18n(
          'workspace.importExport.duplicateMappingContent',
          duplicateMapping.targetColumn,
          duplicateMapping.mappedSource,
        ),
      });
      return;
    }
    if (blockedColumns.length > 0) {
      modal.error({
        title: i18n('workspace.importExport.requiredUnmapped'),
        content: blockedColumns.map((c) => `${c.name} (${c.dataType})`).join(', '),
      });
      return;
    }
    setExecuting(true);
    setError(null);
    if (!fileId) {
      setExecuting(false);
      return;
    }
    let taskCsvOptions: ICsvOptions | undefined;
    try {
      taskCsvOptions = buildCsvOptionsForTaskSubmit(isCsv, csvOptions);
    } catch (e) {
      setExecuting(false);
      setError(resolveErrorMessage(e));
      return;
    }
    sqlService
      .executeImportWithMapping({
        dataSourceId,
        databaseName,
        schemaName,
        tableName,
        fileId,
        mappings: Object.entries(mapping)
          .filter(([, target]) => target && target !== SKIP_IMPORT_SOURCE_FIELD)
          .map(([source, target]) => ({ sourceColumn: source, targetColumn: target })),
        unmappedTarget,
        csvOptions: taskCsvOptions,
      })
      .then((result) => onSubmitted(result.taskId))
      .catch((e) => setError(resolveErrorMessage(e)))
      .finally(() => setExecuting(false));
  };

  return (
    <div className={styles.container}>
      {modalContextHolder}
      <div className={styles.scrollContent}>
        {error && preview && <div className={styles.error}>{error}</div>}
        {isCsv && (
          <div className={styles.csvOptions}>
            <CsvOptionsSections
              value={csvOptions}
              activeKeys={activeSections}
              disabled={executing}
              dataItems={dataSectionItems}
              onChange={setCsvOptions}
              onActiveKeysChange={setActiveSections}
            />
          </div>
        )}
        {!preview && (
          <div className={styles.previewState} role={error ? undefined : 'status'}>
            {error ? (
              <div className={styles.previewError} role="alert">
                <TriangleAlert size={24} />
                <span>{error}</span>
              </div>
            ) : (
              <Spin />
            )}
          </div>
        )}
        {!isCsv && preview && (
          <Collapse
            className={styles.sections}
            ghost
            size="small"
            activeKey={activeSections}
            onChange={(keys) => setActiveSections(Array.isArray(keys) ? keys : [keys])}
            items={dataSectionItems}
          />
        )}
      </div>
      {preview && (
        <div className={styles.actions}>
          <Button
            type="primary"
            loading={executing}
            disabled={loading || !fileId || loadedPreviewKey !== currentPreviewKey}
            onClick={execute}
          >
            {i18n('common.button.execute')}
          </Button>
        </div>
      )}
    </div>
  );
};

export default ImportMappingContent;
