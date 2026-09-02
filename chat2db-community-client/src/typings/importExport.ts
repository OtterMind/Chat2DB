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
  artifacts?: ITaskArtifact[];
  createdAt: number | string;
  startedAt?: number | string;
  finishedAt?: number | string;
  updatedAt?: number | string;
}

export interface ITaskArtifact {
  artifactId: string;
  role: string;
  mediaType?: string;
  sizeBytes?: number;
  createdAt?: number | string;
}

export interface IImportColumnMapping {
  source: string;
  target: string;
}

export interface IImportOptions {
  charset?: string;
  delimiter?: string;
  quoteChar?: string;
  skipRows?: number;
  nullString?: string;
  columnMappings?: IImportColumnMapping[];
  onError?: 'ABORT' | 'SKIP';
  maxErrors?: number;
}

export interface IImportColumnMatch {
  fileColumn: string;
  tableColumn?: string;
  matched: boolean;
}

export interface IImportPreview {
  fileColumns: string[];
  columnMatches: IImportColumnMatch[];
  missingTableColumns: string[];
  sampleRows: string[][];
  detectedCharset?: string;
  detectedDelimiter?: string;
}

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
