import assert from 'node:assert/strict';
import { ImportExportTaskStatus, ImportExportTaskType } from '@/constants/importExport';
import { ImportExportTaskDetails, ImportExportTaskEvent } from '@/typings/importExport';
import {
  createTaskListRequestCoordinator,
  FAILED_TASK_POLL_INTERVAL,
  getTaskPollingDelay,
  listAllTasksByStatus,
  loadMissingTrackedTasks,
  mergeTaskEvents,
  mergeTasks,
  reconcileCompletedTaskNotifications,
  shouldKeepTaskPolling,
  shouldRetryTaskPolling,
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

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
};

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

async function testStaleLoadMoreCannotResurrectDeletedTask() {
  const coordinator = createTaskListRequestCoordinator();
  const deletedTask = task(1, ImportExportTaskStatus.SUCCESS, '2026-08-06T10:00:00Z', 100);
  const response = deferred<ImportExportTaskDetails[]>();
  const request = coordinator.beginLoadMoreRequest();
  let tasks: ImportExportTaskDetails[] = [deletedTask];
  const loadMore = response.promise.then((incomingTasks) => {
    if (coordinator.canApplyLoadMoreResponse(request)) {
      tasks = mergeTasks(incomingTasks);
    }
  });

  coordinator.invalidateState();
  tasks = [];
  response.resolve([deletedTask]);
  await loadMore;

  assert.deepEqual(tasks, []);
  assert.equal(coordinator.isLatestLoadMoreRequest(request), true);
}

async function testStaleLoadMoreCannotOverwriteNewerPollingState() {
  const coordinator = createTaskListRequestCoordinator();
  const runningTask = task(1, ImportExportTaskStatus.RUNNING, '2026-08-06T10:00:00Z', 75);
  const completedTask = { ...runningTask, status: ImportExportTaskStatus.SUCCESS, progress: 100 };
  const response = deferred<ImportExportTaskDetails[]>();
  const staleRequest = coordinator.beginLoadMoreRequest();
  let tasks: ImportExportTaskDetails[] = [runningTask];
  const loadMore = response.promise.then((incomingTasks) => {
    if (coordinator.canApplyLoadMoreResponse(staleRequest)) {
      tasks = mergeTasks(incomingTasks);
    }
  });

  coordinator.invalidateState();
  tasks = [completedTask];
  response.resolve([runningTask]);
  await loadMore;

  assert.equal(tasks[0].status, ImportExportTaskStatus.SUCCESS);

  const olderRequest = coordinator.beginLoadMoreRequest();
  const latestRequest = coordinator.beginLoadMoreRequest();
  assert.equal(coordinator.canApplyLoadMoreResponse(olderRequest), false);
  assert.equal(coordinator.canApplyLoadMoreResponse(latestRequest), true);
}

void testActiveTaskPagination().then(async () => {
  await testCompletedTrackedTaskOutsideRecentPage();
  await testStaleLoadMoreCannotResurrectDeletedTask();
  await testStaleLoadMoreCannotOverwriteNewerPollingState();
  testTaskMerge();
  testEventMerge();
  testPollingDelay();
  testPollingRetryPolicy();
  testCompletedTaskNotifications();
  console.log('Task center utility tests passed');
});
