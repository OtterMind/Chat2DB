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
  NDJSON = 'NDJSON',
  MARKDOWN = 'MARKDOWN',
  SQL = 'SQL',
}

export enum ImportExportCompression {
  NONE = 'NONE',
  GZIP = 'GZIP',
}

export enum ImportOnError {
  ABORT = 'ABORT',
  SKIP = 'SKIP',
}

export enum ImportExportTaskType {
  QUERY_RESULT_EXPORT = 'QUERY_RESULT_EXPORT',
  SQL_EXPORT = 'SQL_EXPORT',
  TABLE_DATA_EXPORT = 'TABLE_DATA_EXPORT',
  DATA_FILE_IMPORT = 'DATA_FILE_IMPORT',
  SQL_FILE_IMPORT = 'SQL_FILE_IMPORT',
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
