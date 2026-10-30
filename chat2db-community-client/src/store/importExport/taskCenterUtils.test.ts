import assert from 'node:assert/strict';
import { ImportExportTaskStatus, ImportExportTaskType } from '@/constants/importExport';
import { ImportExportTaskDetails, ImportExportTaskEvent } from '@/typings/importExport';
import {
  canCancelTask,
  FAILED_TASK_POLL_INTERVAL,
  getTaskPollingDelay,
  listAllTasksByStatus,
  loadMissingTrackedTasks,
  markTaskCancelled,
  mergeTaskEvents,
  mergeTasks,
  reconcileCompletedTaskNotifications,
  shouldKeepTaskPolling,
  shouldRefreshImportTargetTable,
  shouldRetryTaskPolling,
  shouldScanActiveTasks,
} from './taskCenterUtils';
import { ErrorCode } from '@/constants/request';

const event = (sequence: number, message: string): ImportExportTaskEvent => ({
  eventId: sequence,
  taskId: 1,
  sequence,
  level: sequence === 3 ? 'ERROR' : 'INFO',
  code: `EVENT_${sequence}`,
  message,
  createdAt: sequence,
});

const task = (
  id: number,
  status: ImportExportTaskStatus,
  createdAt: string,
  progress = 0,
): ImportExportTaskDetails => ({
  id,
  name: `task-${id}`,
  type: ImportExportTaskType.TABLE_DATA_EXPORT,
  status,
  progress,
  createdAt,
});

async function testActiveTaskPagination() {
  const requestedPages: number[] = [];
  const tasks = await listAllTasksByStatus(async ({ pageNo }) => {
    requestedPages.push(pageNo);
    return pageNo === 1
      ? {
          data: [task(1, ImportExportTaskStatus.RUNNING, '2026-08-06T10:00:00Z')],
          hasNextPage: true,
        }
      : {
          data: [task(2, ImportExportTaskStatus.RUNNING, '2026-08-06T11:00:00Z')],
          hasNextPage: false,
        };
  }, ImportExportTaskStatus.RUNNING);

  assert.deepEqual(requestedPages, [1, 2]);
  assert.deepEqual(
    tasks.map((item) => item.id),
    [1, 2],
  );
}

async function testCompletedTrackedTaskOutsideRecentPage() {
  const runningTask = task(1, ImportExportTaskStatus.RUNNING, '2026-08-06T10:00:00Z', 80);
  const newerTask = task(2, ImportExportTaskStatus.SUCCESS, '2026-08-06T12:00:00Z', 100);
  const completedTask = { ...runningTask, status: ImportExportTaskStatus.SUCCESS, progress: 100 };
  const loadedTaskIds: number[] = [];

  const recovered = await loadMissingTrackedTasks([runningTask.id], [newerTask], async ({ taskId }) => {
    loadedTaskIds.push(taskId);
    return completedTask;
  });

  assert.deepEqual(loadedTaskIds, [1]);
  assert.deepEqual(recovered.tasks, [completedTask]);
  assert.deepEqual(recovered.unresolvedTaskIds, []);
  const notification = reconcileCompletedTaskNotifications(
    {
      [runningTask.id]: ImportExportTaskStatus.RUNNING,
      [newerTask.id]: ImportExportTaskStatus.SUCCESS,
    },
    mergeTasks([newerTask], recovered.tasks),
    true,
  );
  assert.deepEqual(notification.newlyCompletedTaskIds, [1]);
}

function testTaskMerge() {
  const staleTask = task(1, ImportExportTaskStatus.PENDING, '2026-08-06T10:00:00Z');
  const freshTask = task(1, ImportExportTaskStatus.RUNNING, '2026-08-06T10:00:00Z', 50);
  const newerTask = task(2, ImportExportTaskStatus.SUCCESS, '2026-08-06T12:00:00Z', 100);
  const tasks = mergeTasks([staleTask, newerTask], [freshTask]);

  assert.deepEqual(
    tasks.map((item) => item.id),
    [2, 1],
  );
  assert.equal(tasks[1].status, ImportExportTaskStatus.RUNNING);
  assert.equal(tasks[1].progress, 50);
}

function testTaskMergePreservesLatestRevision() {
  const oldTask = { ...task(1, ImportExportTaskStatus.RUNNING, '2026-08-06T10:00:00Z', 20), updatedAt: 1000 };
  const latestTask = { ...oldTask, progress: 80, updatedAt: '1970-01-01T00:00:02.000Z' };
  assert.deepEqual(mergeTasks([latestTask], [oldTask]), [latestTask]);
  assert.deepEqual(mergeTasks([oldTask], [latestTask]), [latestTask]);

  const terminalStatuses = [
    ImportExportTaskStatus.SUCCESS, ImportExportTaskStatus.FAILED, ImportExportTaskStatus.CANCELLED,
  ];
  for (const status of terminalStatuses) {
    const terminal = { ...oldTask, status, progress: 100 };
    assert.deepEqual(mergeTasks([terminal], [latestTask]), [terminal]);
    assert.deepEqual(mergeTasks([latestTask], [terminal]), [terminal]);
  }

  const withoutRevision = task(1, ImportExportTaskStatus.SUCCESS, '2026-08-06T10:00:00Z', 100);
  assert.deepEqual(mergeTasks([withoutRevision], [oldTask]), [withoutRevision]);
}

function testEventMerge() {
  const events = mergeTaskEvents([event(1, 'created'), event(2, 'old')], [event(3, 'finished'), event(2, 'running')]);

  assert.deepEqual(
    events.map((item) => item.sequence),
    [1, 2, 3],
  );
  assert.equal(events[1].message, 'running');
}

function testPollingDelay() {
  assert.equal(getTaskPollingDelay(1), 1000);
  assert.equal(getTaskPollingDelay(0), null);
  assert.equal(getTaskPollingDelay(0, true), FAILED_TASK_POLL_INTERVAL);
  assert.equal(shouldKeepTaskPolling(true, 0), true);
  assert.equal(shouldKeepTaskPolling(false, 1), true);
  assert.equal(shouldKeepTaskPolling(false, 0), false);
}

function testActiveTaskScanIsOnlyNeededWithoutTrackedTasks() {
  assert.equal(shouldScanActiveTasks([]), true, 'the first round must discover active tasks outside the recent page');
  assert.equal(shouldScanActiveTasks([1]), false, 'tracked active tasks are refreshed without a status sweep');
  assert.equal(shouldScanActiveTasks([1, 2]), false);
}

function testPollingRetryPolicy() {
  assert.equal(shouldRetryTaskPolling(new Error('temporary failure')), true);
  assert.equal(shouldRetryTaskPolling({ errorCode: ErrorCode.NetworkError }), true);
  assert.equal(shouldRetryTaskPolling({ errorCode: ErrorCode.NeedLoggedIn }), false);
  assert.equal(shouldRetryTaskPolling({ errorCode: ErrorCode.OfflineInvalidTrial }), false);
  assert.equal(shouldRetryTaskPolling({ errorCode: ErrorCode.OfflineTrialExpired }), false);
  assert.equal(shouldRetryTaskPolling({ errorCode: ErrorCode.OfflineLicenseExpired }), false);
}

function testCompletedTaskNotifications() {
  const historicalTask = task(1, ImportExportTaskStatus.SUCCESS, '2026-08-06T10:00:00Z', 100);
  const baseline = reconcileCompletedTaskNotifications({}, [historicalTask], false);
  assert.equal(baseline.newlyCompletedCount, 0);

  const runningTask = task(2, ImportExportTaskStatus.RUNNING, '2026-08-06T11:00:00Z', 50);
  const withRunningTask = reconcileCompletedTaskNotifications(
    baseline.statuses,
    [historicalTask, runningTask],
    true,
    baseline.cursor,
  );
  assert.equal(withRunningTask.newlyCompletedCount, 0);

  const failedTask = task(2, ImportExportTaskStatus.FAILED, '2026-08-06T11:00:00Z', 50);
  const fastSuccessfulTask = task(3, ImportExportTaskStatus.SUCCESS, '2026-08-06T12:00:00Z', 100);
  const completed = reconcileCompletedTaskNotifications(
    withRunningTask.statuses,
    [historicalTask, failedTask, fastSuccessfulTask],
    true,
    withRunningTask.cursor,
  );
  assert.equal(completed.newlyCompletedCount, 2);
  assert.deepEqual(completed.newlyCompletedTaskIds, [2, 3]);

  const unchanged = reconcileCompletedTaskNotifications(
    completed.statuses,
    [historicalTask, failedTask, fastSuccessfulTask],
    true,
    completed.cursor,
  );
  assert.equal(unchanged.newlyCompletedCount, 0);

  const historicalBackfill = task(4, ImportExportTaskStatus.SUCCESS, '2026-08-06T09:00:00Z', 100);
  const afterDeletion = reconcileCompletedTaskNotifications(
    completed.statuses,
    [historicalTask, failedTask, fastSuccessfulTask, historicalBackfill],
    true,
    completed.cursor,
  );
  assert.equal(afterDeletion.newlyCompletedCount, 0);
  assert.deepEqual(afterDeletion.newlyCompletedTaskIds, []);
}

function testImportTargetRefreshWaitsForTerminalImportResult() {
  const runningImport = {
    ...task(10, ImportExportTaskStatus.RUNNING, '2026-08-06T10:00:00Z', 45),
    type: ImportExportTaskType.DATA_FILE_IMPORT,
    target: {
      dataSourceId: 7,
      databaseName: 'app',
      tableName: 'orders',
    },
  };
  const successfulImport = {
    ...runningImport,
    status: ImportExportTaskStatus.SUCCESS,
    progress: 100,
  };
  const failedImport = {
    ...runningImport,
    status: ImportExportTaskStatus.FAILED,
  };
  const activeExport = {
    ...runningImport,
    type: ImportExportTaskType.TABLE_DATA_EXPORT,
  };

  assert.equal(shouldRefreshImportTargetTable(undefined, runningImport), false);
  assert.equal(shouldRefreshImportTargetTable(undefined, successfulImport), true);
  assert.equal(shouldRefreshImportTargetTable(runningImport, activeExport), false);
  assert.equal(shouldRefreshImportTargetTable(runningImport, failedImport), false);
  assert.equal(shouldRefreshImportTargetTable(runningImport, successfulImport), true);
  assert.equal(shouldRefreshImportTargetTable(successfulImport, successfulImport), false);
}

function testCancelActionVisibility() {
  assert.equal(canCancelTask(ImportExportTaskStatus.PENDING), true);
  assert.equal(canCancelTask(ImportExportTaskStatus.RUNNING), true);
  assert.equal(canCancelTask(ImportExportTaskStatus.SUCCESS), false);
  assert.equal(canCancelTask(ImportExportTaskStatus.FAILED), false);
  assert.equal(canCancelTask(ImportExportTaskStatus.CANCELLED), false);
}

function testTaskCancellationMarksTheRowTerminal() {
  const running = { ...task(1, ImportExportTaskStatus.RUNNING, '2026-08-06T10:00:00Z', 40), updatedAt: 4000 };
  const finished = { ...task(2, ImportExportTaskStatus.SUCCESS, '2026-08-06T09:00:00Z', 100), finishedAt: 5000 };
  const cancelledAt = 1700000000000;
  const updated = markTaskCancelled([running, finished], 1, cancelledAt);
  const cancelled = updated.find((item) => item.id === 1);

  assert.equal(cancelled?.status, ImportExportTaskStatus.CANCELLED);
  assert.equal(cancelled?.finishedAt, cancelledAt);
  assert.equal(cancelled?.updatedAt, running.updatedAt, 'the client clock must not outrank the server record');
  assert.equal(cancelled?.progress, 40, 'cancelling keeps the rest of the row payload intact');
  assert.equal(updated.find((item) => item.id === 2), finished, 'untouched rows keep their identity');
  assert.equal(
    markTaskCancelled([finished], 2, cancelledAt).find((item) => item.id === 2)?.finishedAt,
    finished.finishedAt,
    'an existing finish time is authoritative',
  );
  assert.equal(markTaskCancelled([running], 99, cancelledAt)[0], running, 'an unknown task id changes nothing');
}

void testActiveTaskPagination().then(async () => {
  await testCompletedTrackedTaskOutsideRecentPage();
  testTaskMerge();
  testTaskMergePreservesLatestRevision();
  testEventMerge();
  testPollingDelay();
  testActiveTaskScanIsOnlyNeededWithoutTrackedTasks();
  testCancelActionVisibility();
  testTaskCancellationMarksTheRowTerminal();
  testPollingRetryPolicy();
  testCompletedTaskNotifications();
  testImportTargetRefreshWaitsForTerminalImportResult();
});
