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
