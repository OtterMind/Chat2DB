// enum type
export enum ImportExportType {
  IMPORT = 'import',
  EXPORT = 'export',
}

export enum ImportExportFileType {
  CSV = 'CSV',
  XLS = 'XLS',
  XLSX = 'XLSX',
  JSON = 'JSON',
  SQL = 'SQL',
}

export enum ImportExportTaskType {
  QUERY_RESULT_EXPORT = 'QUERY_RESULT_EXPORT',
  SQL_EXPORT = 'SQL_EXPORT',
  TABLE_DATA_EXPORT = 'TABLE_DATA_EXPORT',
  DATA_FILE_IMPORT = 'DATA_FILE_IMPORT',
  SQL_FILE_IMPORT = 'SQL_FILE_IMPORT',
  PI_RUNTIME_INSTALL = 'PI_RUNTIME_INSTALL',
}

export enum ImportExportTaskStatus {
  PENDING = 'PENDING',
  RUNNING = 'RUNNING',
  SUCCESS = 'SUCCESS',
  FAILED = 'FAILED',
  CANCELLED = 'CANCELLED',
}

export const ACTIVE_TASK_STATUSES: ImportExportTaskStatus[] = [
  ImportExportTaskStatus.PENDING,
  ImportExportTaskStatus.RUNNING,
];

export const SKIP_IMPORT_SOURCE_FIELD = '__skip__';

export enum ImportUnmappedTarget {
  DEFAULT = 'DEFAULT',
  NULL = 'NULL',
}

export enum ImportPreviewErrorCode {
  DUPLICATE_SOURCE_COLUMNS = 'import.preview.duplicateSourceColumns',
}
