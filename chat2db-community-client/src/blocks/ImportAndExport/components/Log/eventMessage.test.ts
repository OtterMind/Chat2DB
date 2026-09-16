import { ImportExportTaskType } from '@/constants/importExport';
import zhCN from '@/i18n/zh-CN/workspace';
import enUS from '@/i18n/en-US/workspace';
import jaJP from '@/i18n/ja-JP/workspace';
import koKR from '@/i18n/ko-KR/workspace';
import esES from '@/i18n/es-ES/workspace';
import assert from 'node:assert/strict';
import { formatTaskEventMessage, type TaskEventMessageKey } from './eventMessage';
import type { ImportExportTaskEvent } from '@/typings/importExport';

const event = (code: string, message: string): ImportExportTaskEvent => ({
  eventId: 1,
  taskId: 1,
  sequence: 1,
  level: 'INFO',
  code,
  message,
  createdAt: 1,
});

const translations: Record<'zh' | 'en', Partial<Record<TaskEventMessageKey, string>>> = {
  zh: {
    'workspace.task.event.taskCreated': '任务已创建',
    'workspace.task.event.importPreparing': '正在准备导入数据',
    'workspace.task.event.batchExecuted': '已执行批量导入：{1} 条',
  },
  en: {
    'workspace.task.event.taskCreated': 'Task created',
    'workspace.task.event.batchExecuted': 'Imported batch: {1} rows',
  },
};

const translate = (language: keyof typeof translations) =>
  (key: TaskEventMessageKey, ...args: Array<string | number>) =>
    args.reduce(
      (message, value, index) => message.replace(`{${index + 1}}`, String(value)),
      translations[language][key] || key,
    );

const created = event('TASK_CREATED', 'Task created');
assert.equal(formatTaskEventMessage(created, translate('zh')), '任务已创建');
assert.equal(formatTaskEventMessage(created, translate('en')), 'Task created');
assert.equal(
  formatTaskEventMessage(event('IMPORT_PREPARING', 'Preparing data import'), translate('zh')),
  '正在准备导入数据',
);
assert.equal(
  formatTaskEventMessage(event('BATCH_EXECUTED', 'Executing batch insert: 120'), translate('zh')),
  '已执行批量导入：120 条',
);
assert.equal(
  formatTaskEventMessage(event('TASK_FAILED', 'CSV 存在从第 123 行开始未闭合的引号'), translate('en')),
  'CSV 存在从第 123 行开始未闭合的引号',
);

// Format the same stored events using each locale, including switching languages.
const csvSummary = event('IMPORT_SUMMARY', 'CSV import finished');
const completedBatch = {
  ...event('BATCH_EXECUTED', 'SQL batch executed'),
  details: { batch: 1, statementCount: 20_000 },
};
for (const messages of [zhCN, enUS, jaJP, koKR, esES, zhCN]) {
  const localize = (key: TaskEventMessageKey, ...args: Array<string | number>) =>
    args.reduce((message, value, index) => message.replace(`{${index + 1}}`, String(value)), messages[key]);
  const format = (entry: ImportExportTaskEvent) =>
    formatTaskEventMessage(entry, localize, ImportExportTaskType.DATA_FILE_IMPORT);
  assert.equal(format(csvSummary), messages['workspace.task.event.csvImportFinished']);
  assert.equal(format(completedBatch), messages['workspace.task.event.batchCompletedRows'].replace('{1}', '20000'));
  assert.equal(format({ ...completedBatch, details: { statementCount: 20000, importedRows: 40000 } }),
    messages['workspace.task.event.batchCompletedTotal'].replace('{1}', '20000').replace('{2}', '40000'));
  assert.equal(format({ ...csvSummary, details: { importedRows: 100000, elapsedMillis: 14321 } }),
    messages['workspace.task.event.csvImportSummary'].replace('{1}', '100000').replace('{2}', '14.32'));
  assert.equal(format({ ...csvSummary, details: { importedRows: 0, elapsedMillis: 0 } }),
    messages['workspace.task.event.csvImportSummary'].replace('{1}', '0').replace('{2}', '0.00'));
  for (const count of [undefined, '20000', -1, NaN]) {
    assert.equal(format({ ...completedBatch, details: { statementCount: count } }),
      messages['workspace.task.event.sqlBatchExecuted']);
    assert.equal(format({ ...csvSummary, details: { importedRows: 100, elapsedMillis: count } }),
      messages['workspace.task.event.csvImportFinished']);
  }
  assert.equal(formatTaskEventMessage(completedBatch, localize, ImportExportTaskType.SQL_FILE_IMPORT),
    messages['workspace.task.event.sqlBatchExecuted']);
  assert.equal(format(event('CUSTOM_EVENT', 'driver message')), 'driver message');
}
