import { IDatabaseBaseInfo } from '@/typings/database';
import { ImportExportType, ImportExportTaskType, ImportExportTaskStatus } from '@/constants/importExport';

export interface ImportExportDataBoundInfo extends IDatabaseBaseInfo {
  tableName: string;
  type: ImportExportType;
}

export interface ImportExportTaskDetails {
  id: number;
  name: string;
  type: ImportExportTaskType;
  status: ImportExportTaskStatus;
  progress: number;
  stage?: string;
  progressMessage?: string;
  target?: {
    dataSourceId?: number;
    databaseName?: string;
    schemaName?: string;
    tableName?: string;
  };
  errorCode?: string;
  errorMessage?: string;
  artifactId?: string;
  createdAt: number | string;
  startedAt?: number | string;
  finishedAt?: number | string;
  updatedAt?: number | string;
}

/** CSV import execution mode; absent resolves to STANDARD on the backend. */
export type ImportExecutionMode = 'FAST' | 'STANDARD';

export interface ImportExportTaskEvent {
  eventId: number;
  taskId: number;
  sequence: number;
  level: 'INFO' | 'WARN' | 'ERROR';
  code: string;
  stage?: string;
  message: string;
  details?: Record<string, unknown>;
  createdAt: number | string;
}

export interface IImportValueOptions {
  dateOrder: 'YMD' | 'YDM' | 'MDY' | 'MYD' | 'DMY' | 'DYM';
  dateTimeOrder: 'DATE_TIME' | 'TIME_DATE' | 'DATE_TIME_TIMEZONE' | 'TIME_DATE_TIMEZONE' | 'TIME_TIMEZONE_DATE';
  dateDelimiter: string;
  yearDelimiter: string;
  timeDelimiter: string;
  decimalSymbol: '.' | ',';
}

export interface ISourceRowOptions {
  hasHeader: boolean;
  headerRow: number;
  dataStartRow: number;
  dataEndRow?: number;
}

export interface ICsvOptions extends IImportValueOptions, ISourceRowOptions {
  encoding: string;
  delimiter: string;
  quote: string;
  escape: string;
  newline: 'LF' | 'CRLF' | 'CR';
  emptyAsNull: boolean;
}

export interface IExcelOptions extends IImportValueOptions, ISourceRowOptions {
  sheetIndex: number;
  columnRange: string;
  emptyAsNull: boolean;
}

export interface IJsonOptions extends IImportValueOptions {
  encoding: string;
  structure: 'ARRAY' | 'OBJECT' | 'LINES';
  dataPath: string;
  emptyAsNull: boolean;
}

export interface ISqlImportOptions {
  encoding: string;
}
