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

export type ImportTaskScope = 'TABLE' | 'SCHEMA' | 'DATABASE';

export type ImportSourceKind = 'TRUSTED' | 'THIRD_PARTY';

export type ImportCycleStrategy = 'DEFER_CONSTRAINTS' | 'STAGING_TWO_PHASE' | 'REJECT';

export const SQL_EXPORTER_PROFILES = [
  'NAVICAT',
  'DBEAVER',
  'DATAGRIP',
  'HEIDISQL',
  'PHPMYADMIN',
  'MYSQL_WORKBENCH',
  'PGADMIN',
  'SSMS',
  'ORACLE_SQL_DEVELOPER',
] as const;

export type SqlExporterProfile = (typeof SQL_EXPORTER_PROFILES)[number];

export interface IImportOptions {
  charset?: string;
  delimiter?: string;
  quoteChar?: string;
  skipRows?: number;
  nullString?: string;
  columnMappings?: IImportColumnMapping[];
  onError?: 'ABORT' | 'SKIP';
  maxErrors?: number;
  sqlExporterProfile?: SqlExporterProfile;
}

export interface IImportTableSource {
  databaseName?: string;
  schemaName?: string;
  tableName: string;
  sourceFile?: string;
  fileId?: string;
  displayFileName?: string;
  format: ImportExportFileType;
  dataTimeFormat?: string;
  columnMappings?: IImportColumnMapping[];
  unmappedTarget?: 'DEFAULT' | 'NULL';
  options?: IImportOptions;
}

export interface IImportTableDependency {
  parentDatabaseName?: string;
  parentSchemaName?: string;
  parentTable: string;
  parentColumn: string;
  parentTableKey?: string;
  childDatabaseName?: string;
  childSchemaName?: string;
  childTable: string;
  childColumn: string;
  childTableKey?: string;
  constraintName?: string;
  keySequence?: number;
  deferrability?: number;
  logical: boolean;
}

export interface IImportStagingPolicy {
  enabled: boolean;
  allVarchar: boolean;
  twoPhase: boolean;
}

export interface IImportValidationOptions {
  sourceProfiling: boolean;
  rowCount: boolean;
  checksum: boolean;
  orphanCheck: boolean;
}

export interface IImportFinalizationOptions {
  resetSequences: boolean;
  rebuildIndexes: boolean;
  refreshStatistics: boolean;
}

export interface IImportRollbackOptions {
  fullRollback: boolean;
  rehearsal: boolean;
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
