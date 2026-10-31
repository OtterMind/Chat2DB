import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { ImportExportTaskStatus } from '@/constants/importExport';
import { ACTIVE_TASK_POLL_INTERVAL, FAILED_TASK_POLL_INTERVAL } from '@/store/importExport/taskCenterUtils';
import { getTaskEventPollDelay } from './pollSchedule';

assert.equal(getTaskEventPollDelay(ImportExportTaskStatus.RUNNING, true, false), ACTIVE_TASK_POLL_INTERVAL);
assert.equal(
  getTaskEventPollDelay(ImportExportTaskStatus.SUCCESS, true, true),
  ACTIVE_TASK_POLL_INTERVAL,
  'a full event page must keep the normal interval instead of busy-polling with a zero delay',
);
assert.equal(getTaskEventPollDelay(ImportExportTaskStatus.SUCCESS, true, false), null, 'polling stops once the log is complete');
assert.equal(getTaskEventPollDelay(ImportExportTaskStatus.CANCELLED, true, false), null);
assert.equal(getTaskEventPollDelay(ImportExportTaskStatus.RUNNING, false, false), FAILED_TASK_POLL_INTERVAL);

const logSource = readFileSync(path.resolve('src/blocks/ImportAndExport/components/Log/index.tsx'), 'utf8');
assert.equal(logSource.includes('setTimeout(poll, 0)'), false, 'the log panel must not busy-poll when the event page is full');
assert.equal(logSource.includes('getTaskEventPollDelay'), true, 'the log panel must use the shared poll schedule');

console.log('task event polling keeps the normal interval while catching up');
