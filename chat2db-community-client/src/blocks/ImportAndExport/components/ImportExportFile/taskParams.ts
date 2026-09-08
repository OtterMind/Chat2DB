import { ImportExportFileType, ImportExportTaskType, ImportExportType } from '@/constants/importExport';
import type { ExportTaskParams, ImportTaskParams } from '@/service/importExport';
import type {
  IImportColumnMapping,
  IImportOptions,
  IImportPreview,
  ImportExecutionMode,
  ImportExportDataBoundInfo,
} from '@/typings/importExport';

export interface ImportExportFormValue {
  exportType: ImportExportFileType;
  containsHeader: boolean;
  compression?: string;
  checkpointRows?: number;
  charset?: string;
  delimiter?: string;
  quoteChar?: string;
  skipRows?: number;
  nullString?: string;
  onError?: 'ABORT' | 'SKIP';
  maxErrors?: number;
  unmappedTarget?: 'DEFAULT' | 'NULL';
}

interface BuildTaskParamsInput {
  boundInfo: ImportExportDataBoundInfo;
  formValue: ImportExportFormValue;
  mode: ImportExecutionMode;
  sourceFile: string;
  exportLocation: string;
  desktop: boolean;
  importPreview: IImportPreview | null;
  columnMappings: Record<string, string | undefined>;
  checkpointableFormats: ImportExportFileType[];
  confirmedNoStrongRelations?: boolean;
}

export function initialFileType(boundInfo: ImportExportDataBoundInfo): ImportExportFileType {
  if (boundInfo.fileType) return boundInfo.fileType;
  return boundInfo.targetScope === 'TABLE' ? ImportExportFileType.CSV : ImportExportFileType.SQL;
}

export function buildTaskParams({
  boundInfo,
  formValue,
  mode,
  sourceFile,
  exportLocation,
  desktop,
  importPreview,
  columnMappings,
  checkpointableFormats,
  confirmedNoStrongRelations = false,
}: BuildTaskParamsInput): ExportTaskParams | ImportTaskParams {
  const { dataSourceId, databaseName, schemaName, tableName } = boundInfo;
  const commonValues = {
    dataSourceId,
    databaseName,
    schemaName,
    format: formValue.exportType,
    mode,
  };

  if (boundInfo.type === ImportExportType.EXPORT) {
    if (boundInfo.sqlExportScope) {
      return {
        ...commonValues,
        taskType: ImportExportTaskType.SQL_EXPORT,
        format: ImportExportFileType.SQL,
        tableNames: tableName ? [tableName] : undefined,
        scope: boundInfo.sqlExportScope,
        containData: boundInfo.sqlExportScope === 'ALL',
        containsHeader: formValue.containsHeader,
        exportPath: desktop ? exportLocation : undefined,
      };
    }
    return {
      ...commonValues,
      taskType: ImportExportTaskType.TABLE_DATA_EXPORT,
      tableNames: tableName ? [tableName] : undefined,
      containsHeader: formValue.containsHeader,
      exportPath: desktop ? exportLocation : undefined,
      compression: formValue.compression || undefined,
      checkpointRows:
        !formValue.compression && checkpointableFormats.includes(formValue.exportType)
          ? formValue.checkpointRows || undefined
          : undefined,
    };
  }

  const mappingList: IImportColumnMapping[] | undefined = importPreview
    ? Object.entries(columnMappings)
        .filter((entry) => !!entry[1])
        .map(([sourceColumn, targetColumn]) => ({ sourceColumn, targetColumn: targetColumn as string }))
    : undefined;
  const options: IImportOptions | undefined =
    formValue.exportType === ImportExportFileType.SQL
      ? undefined
      : {
          ...(formValue.exportType === ImportExportFileType.CSV
            ? {
                charset: formValue.charset || undefined,
                delimiter: formValue.delimiter || undefined,
                quoteChar: formValue.quoteChar || undefined,
                skipRows: formValue.skipRows || undefined,
                nullString: formValue.nullString || undefined,
                onError: formValue.onError || undefined,
                maxErrors: formValue.onError === 'SKIP' ? formValue.maxErrors || undefined : undefined,
              }
            : {}),
          ...(mappingList ? { columnMappings: mappingList } : {}),
        };
  return {
    ...commonValues,
    taskType:
      formValue.exportType === ImportExportFileType.SQL
        ? ImportExportTaskType.SQL_FILE_IMPORT
        : ImportExportTaskType.DATA_FILE_IMPORT,
    tableName: boundInfo.targetScope === 'TABLE' ? tableName : undefined,
    sourceFile,
    ...(mode === 'ULTRA_FAST' && confirmedNoStrongRelations ? { confirmedNoStrongRelations: true } : {}),
    options,
    ...(importPreview ? { unmappedTarget: formValue.unmappedTarget || 'DEFAULT' } : {}),
  };
}
