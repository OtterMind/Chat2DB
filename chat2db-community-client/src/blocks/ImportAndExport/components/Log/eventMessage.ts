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
  | 'workspace.task.event.batchExecuted'
  | 'workspace.task.event.importingRecords';

type Translate = (key: TaskEventMessageKey, ...args: Array<string | number>) => string;

export const formatTaskEventMessage = (event: ImportExportTaskEvent, translate: Translate) => {
  const message = event.message?.replace(LEGACY_EVENT_TIMESTAMP, '').trim() || '-';
  const lifecycleKey = lifecycleMessageKeys[event.code as keyof typeof lifecycleMessageKeys];
  if (lifecycleKey) {
    return translate(lifecycleKey);
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
