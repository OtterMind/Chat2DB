import { ImportExportTaskType } from '@/constants/importExport';
import type { ImportExportTaskEvent } from '@/typings/importExport';

const LEGACY_EVENT_TIMESTAMP = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}:?\s*/;

const lifecycleMessageKeys = {
  TASK_CREATED: 'workspace.task.event.taskCreated',
  TASK_STARTED: 'workspace.task.event.taskStarted',
  TASK_SUCCEEDED: 'workspace.task.event.taskSucceeded',
  TASK_CANCELLED: 'workspace.task.event.taskCancelled',
  IMPORT_PREPARING: 'workspace.task.event.importPreparing',
  TARGET_METADATA_LOADED: 'workspace.task.event.targetMetadataLoaded',
  IMPORT_COMPLETED: 'workspace.task.event.importCompleted',
  IMPORT_BATCH_FAILED: 'workspace.task.event.importBatchFailed',
} as const;

const fixedMessageKeys = {
  'Reading import file': 'workspace.task.event.fileReadStarted',
  'Import file read completed': 'workspace.task.event.fileReadCompleted',
  'Reading SQL import file': 'workspace.task.event.sqlFileReadStarted',
  'SQL file parsed': 'workspace.task.event.sqlFileParsed',
  'SQL batch executed': 'workspace.task.event.sqlBatchExecuted',
  'SQL statement executed': 'workspace.task.event.sqlStatementExecuted',
  'Error artifact published': 'workspace.task.event.errorArtifactPublished',
  'Table data export started': 'workspace.task.event.exportStartedTable',
  'Query result export started': 'workspace.task.event.exportStartedQuery',
  'Database SQL export started': 'workspace.task.event.exportStartedSql',
  'Reading query result': 'workspace.task.event.readingQueryResult',
  'Export file written': 'workspace.task.event.exportFileWritten',
  'Saving export file': 'workspace.task.event.savingExportFile',
  'Artifact prepared': 'workspace.task.event.artifactPrepared',
  'Artifact published': 'workspace.task.event.artifactPublished',
  'Interrupted task artifacts cleaned': 'workspace.task.event.artifactCleanupCompleted',
  'Task submission rejected': 'workspace.task.event.taskSubmissionRejected',
  'Finalizing ZIP export archive': 'workspace.task.event.finalizingZip',
} as const;

export type TaskEventMessageKey =
  | (typeof lifecycleMessageKeys)[keyof typeof lifecycleMessageKeys]
  | (typeof fixedMessageKeys)[keyof typeof fixedMessageKeys]
  | 'workspace.task.event.sqlBatchStarted'
  | 'workspace.task.event.sqlBatchResult'
  | 'workspace.task.event.sqlBatchFailed'
  | 'workspace.task.event.sqlImportSummary'
  | 'workspace.task.event.csvImportFinished'
  | 'workspace.task.event.csvImportSummary'
  | 'workspace.task.event.batchCompletedTotal'
  | 'workspace.task.event.batchCompletedWithSkipped'
  | 'workspace.task.event.batchCompletedRows'
  | 'workspace.task.event.batchExecuted'
  | 'workspace.task.event.importingRecords'
  | 'workspace.task.event.importRowsSkipped'
  | 'workspace.task.event.importRejectedRowsRecorded'
  | 'workspace.task.event.importBatchRowsSkipped'
  | 'workspace.task.event.importResumeStarted'
  | 'workspace.task.event.importBatchRetry'
  | 'workspace.task.event.readingTableData'
  | 'workspace.task.event.rowsExported'
  | 'workspace.task.event.rowsExportedFromTable'
  | 'workspace.task.event.queryCompleted'
  | 'workspace.task.event.finalizingExport'
  | 'workspace.task.event.finalizingZip'
  | 'workspace.task.event.tableExportStarted'
  | 'workspace.task.event.tableExportCompleted';

type Translate = (key: TaskEventMessageKey, ...args: Array<string | number>) => string;

const isCount = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

export const formatTaskEventMessage = (
  event: ImportExportTaskEvent,
  translate: Translate,
  taskType?: ImportExportTaskType,
) => {
  const message = event.message?.replace(LEGACY_EVENT_TIMESTAMP, '').trim() || '-';
  const importedRows = event.details?.importedRows;
  const elapsedMillis = event.details?.elapsedMillis;
  if (event.details?.sqlScript === true) {
    const d = event.details;
    if (event.code === 'SQL_BATCH_STARTED' && isCount(d.batch) && isCount(d.statementCount)) {
      return translate('workspace.task.event.sqlBatchStarted', d.batch, d.statementCount);
    }
    if ((event.code === 'BATCH_EXECUTED' || event.code === 'IMPORT_BATCH_FAILED') &&
        isCount(d.batch) && isCount(d.statementCount) && isCount(d.affectedRows) &&
        isCount(d.unknownStatements) && isCount(d.completedStatements) && isCount(d.failedStatements)) {
      return translate(event.code === 'BATCH_EXECUTED' ? 'workspace.task.event.sqlBatchResult'
        : 'workspace.task.event.sqlBatchFailed', d.batch, d.statementCount, d.affectedRows,
        d.unknownStatements, d.completedStatements, d.failedStatements);
    }
    if (event.code === 'IMPORT_SUMMARY' && isCount(d.batchCount) && isCount(d.statementCount) &&
        isCount(d.affectedRows) && isCount(d.unknownStatements)) {
      return translate('workspace.task.event.sqlImportSummary', d.batchCount, d.statementCount,
        d.affectedRows, d.unknownStatements);
    }
  }
  if (event.code === 'IMPORT_SUMMARY') {
    if (typeof importedRows === 'number' && Number.isSafeInteger(importedRows) && importedRows >= 0 &&
        typeof elapsedMillis === 'number' && Number.isFinite(elapsedMillis) && elapsedMillis >= 0) {
      return translate('workspace.task.event.csvImportSummary', importedRows, (elapsedMillis / 1000).toFixed(2));
    }
    return translate('workspace.task.event.csvImportFinished');
  }
  const lifecycleKey = lifecycleMessageKeys[event.code as keyof typeof lifecycleMessageKeys];
  if (lifecycleKey) {
    return translate(lifecycleKey);
  }

  const statementCount = event.details?.statementCount;
  const batchImportedRows = event.details?.batchImportedRows;
  const rejectedRows = event.details?.rejectedRows;
  const resumeFromRow = event.details?.resumeFromRow;
  const exportedRows = event.details?.exportedRows;
  const tableName = event.details?.tableName;
  const exportedTables = event.details?.exportedTables;
  const totalTables = event.details?.totalTables;
  if (event.code === 'IMPORT_ROWS_SKIPPED' && isCount(rejectedRows)) {
    if (message === 'Import finished with rejected rows' || message === 'Rejected rows recorded') {
      return translate('workspace.task.event.importRejectedRowsRecorded', rejectedRows);
    }
    if (isCount(importedRows)) {
      return translate('workspace.task.event.importBatchRowsSkipped', importedRows, rejectedRows);
    }
  }
  if (event.code === 'IMPORT_RESUME_STARTED' && isCount(resumeFromRow)) {
    return translate('workspace.task.event.importResumeStarted', resumeFromRow);
  }
  if (event.code === 'IMPORT_BATCH_RETRY' && isCount(statementCount)) {
    return translate('workspace.task.event.importBatchRetry', statementCount);
  }
  if (event.code === 'ROWS_EXPORTED' && isCount(exportedRows)) {
    return typeof tableName === 'string' && tableName
      ? translate('workspace.task.event.rowsExportedFromTable', exportedRows, tableName)
      : translate('workspace.task.event.rowsExported', exportedRows);
  }
  if (event.code === 'QUERY_COMPLETED' && isCount(exportedRows)) {
    return translate('workspace.task.event.queryCompleted', exportedRows);
  }
  if ((event.code === 'TABLE_EXPORT_STARTED' || event.code === 'TABLE_EXPORT_COMPLETED')
      && isCount(exportedTables) && isCount(totalTables)
      && typeof tableName === 'string' && tableName) {
    return event.code === 'TABLE_EXPORT_STARTED'
      ? translate('workspace.task.event.tableExportStarted', exportedTables, totalTables, tableName)
      : translate('workspace.task.event.tableExportCompleted', exportedTables, totalTables, tableName);
  }
  const tableProgress = /^(Exporting table|Table export completed) (\d+)\/(\d+): (.+)$/.exec(message);
  if (tableProgress) {
    const [, action, current, total, table] = tableProgress;
    return action === 'Exporting table'
      ? translate('workspace.task.event.tableExportStarted', current, total, table)
      : translate('workspace.task.event.tableExportCompleted', current, total, table);
  }
  if (event.code === 'QUERY_STARTED') {
    const table = /^Reading table data from (.+)$/.exec(message)?.[1];
    if (table) {
      return translate('workspace.task.event.readingTableData', table);
    }
  }
  if (event.code === 'FILE_FINALIZING') {
    const format = /^Finalizing (.+?) export file(?:\s+from\s+.+)?$/.exec(message)?.[1];
    if (format) {
      return translate('workspace.task.event.finalizingExport', format);
    }
  }
  if (taskType === ImportExportTaskType.DATA_FILE_IMPORT &&
      event.code === 'BATCH_EXECUTED' && message === 'SQL batch executed' && isCount(statementCount)) {
    if (isCount(batchImportedRows)) {
      const cumulative = isCount(importedRows) ? importedRows : batchImportedRows;
      if (batchImportedRows >= statementCount) {
        return translate('workspace.task.event.batchCompletedTotal', batchImportedRows, cumulative);
      }
      return translate('workspace.task.event.batchCompletedWithSkipped', batchImportedRows,
          statementCount - batchImportedRows, cumulative);
    }
    if (isCount(importedRows) && importedRows >= statementCount) {
      return translate('workspace.task.event.batchCompletedTotal', statementCount, importedRows);
    }
    return translate('workspace.task.event.batchCompletedRows', statementCount);
  }

  const fixedMessageKey = fixedMessageKeys[message as keyof typeof fixedMessageKeys];
  if (fixedMessageKey) {
    return translate(fixedMessageKey);
  }

  const batchSize = /^Executing batch insert: (\d+)$/.exec(message)?.[1];
  if (batchSize) {
    return translate('workspace.task.event.batchExecuted', batchSize);
  }

  const recordCount = /^Importing (\d+) records$/.exec(message)?.[1];
  if (recordCount) {
    return translate('workspace.task.event.importingRecords', recordCount);
  }

  return message;
};

export const shouldDisplayTaskEvent = (event: ImportExportTaskEvent): boolean =>
  event.code !== 'SQL_BATCH_STARTED';
