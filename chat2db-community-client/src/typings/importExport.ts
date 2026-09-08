import { IDatabaseBaseInfo } from '@/typings/database';
import {
  ImportExportFileType,
  ImportExportType,
  ImportExportTaskType,
  ImportExportTaskStatus,
} from '@/constants/importExport';

export type ImportExportTargetScope = 'DATA_SOURCE' | 'DATABASE' | 'SCHEMA' | 'TABLE';

export type SqlExportScope = 'ALL' | 'SCHEMA' | 'TABLE';

export interface ImportExportDataBoundInfo extends IDatabaseBaseInfo {
  targetScope?: ImportExportTargetScope;
  type: ImportExportType;
  fileType?: ImportExportFileType;
  sqlExportScope?: SqlExportScope;
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
  sourceColumn: string;
  targetColumn: string;
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

/** Execution mode of bulk import/export tasks; absent resolves to STANDARD on the backend. */
export type ImportExecutionMode = 'ULTRA_FAST' | 'STANDARD';

export interface IImportAdmissionFinding {
  code: string;
  severity: 'BLOCKER' | 'DEGRADATION';
  message: string;
  evidence?: string;
  remediation?: string;
}

export interface IImportAdmissionReport {
  verdict: 'PARALLEL_SAFE' | 'PARALLEL_DEGRADED' | 'PARALLEL_FORBIDDEN';
  requestedMode: ImportExecutionMode;
  effectiveMode: ImportExecutionMode;
  parallelAllowed: boolean;
  fileFormat: string;
  fileSizeBytes: number;
  dataRows: number;
  fullScan: boolean;
  relationshipRiskAccepted: boolean;
  findings: IImportAdmissionFinding[];
}

export interface IImportColumnMatch {
  fileColumn: string;
  tableColumn?: string;
  matched: boolean;
}

export interface IImportPreview {
  targetColumns?: import('@/service/sql').IImportPreview['targetColumns'];
  fileColumns: string[];
  columnMatches: IImportColumnMatch[];
  missingTableColumns: string[];
  sampleRows: string[][];
  detectedCharset?: string;
  detectedDelimiter?: string;
  parallelAdmission?: IImportAdmissionReport;
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
