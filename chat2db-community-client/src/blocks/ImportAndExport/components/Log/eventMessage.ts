import { ImportExportTaskType } from '@/constants/importExport';
import type { ImportExportTaskEvent } from '@/typings/importExport';

const LEGACY_EVENT_TIMESTAMP = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}:?\s*/;

const lifecycleMessageKeys = {
  TASK_CREATED: 'workspace.task.event.taskCreated',
  TASK_STARTED: 'workspace.task.event.taskStarted',
  TASK_SUCCEEDED: 'workspace.task.event.taskSucceeded',
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
} as const;

export type TaskEventMessageKey =
  | (typeof lifecycleMessageKeys)[keyof typeof lifecycleMessageKeys]
  | (typeof fixedMessageKeys)[keyof typeof fixedMessageKeys]
  | 'workspace.task.event.csvImportFinished'
  | 'workspace.task.event.csvImportSummary'
  | 'workspace.task.event.batchCompletedTotal'
  | 'workspace.task.event.batchCompletedRows'
  | 'workspace.task.event.batchExecuted'
  | 'workspace.task.event.importingRecords';

type Translate = (key: TaskEventMessageKey, ...args: Array<string | number>) => string;

export const formatTaskEventMessage = (
  event: ImportExportTaskEvent,
  translate: Translate,
  taskType?: ImportExportTaskType,
) => {
  const message = event.message?.replace(LEGACY_EVENT_TIMESTAMP, '').trim() || '-';
  const importedRows = event.details?.importedRows;
  const elapsedMillis = event.details?.elapsedMillis;
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
  if (taskType === ImportExportTaskType.DATA_FILE_IMPORT &&
      event.code === 'BATCH_EXECUTED' && message === 'SQL batch executed' &&
      typeof statementCount === 'number' && Number.isSafeInteger(statementCount) && statementCount >= 0) {
    if (typeof importedRows === 'number' && Number.isSafeInteger(importedRows) && importedRows >= statementCount) {
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
