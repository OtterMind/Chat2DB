import { ACTIVE_TASK_STATUSES, ImportExportTaskStatus, ImportExportTaskType } from '@/constants/importExport';
import { ErrorCode } from '@/constants/request';
import { ImportExportTaskDetails, ImportExportTaskEvent } from '@/typings/importExport';

export const TASK_LIST_PAGE_SIZE = 100;
export const TASK_CENTER_PAGE_SIZE = 10;
export const TASK_EVENT_INITIAL_PAGE_SIZE = 10;
export const TASK_EVENT_PAGE_SIZE = 200;
export const ACTIVE_TASK_POLL_INTERVAL = 1000;
export const FAILED_TASK_POLL_INTERVAL = 1500;
export const IMPORT_TARGET_TABLE_REFRESH_EVENT = 'chat2db:import-target-table-refresh';

export type TaskStatusById = Record<string, ImportExportTaskStatus>;

export interface TaskNotificationCursor {
  createdAt: number;
  taskId: number;
}

export interface TaskListLoadMoreRequest {
  stateGeneration: number;
  requestGeneration: number;
}

export const createTaskListRequestCoordinator = () => {
  let stateGeneration = 0;
  let loadMoreRequestGeneration = 0;

  return {
    invalidateState: () => {
      stateGeneration += 1;
    },
    beginLoadMoreRequest: (): TaskListLoadMoreRequest => {
      loadMoreRequestGeneration += 1;
      return {
        stateGeneration,
        requestGeneration: loadMoreRequestGeneration,
      };
    },
    canApplyLoadMoreResponse: (request: TaskListLoadMoreRequest) =>
      request.stateGeneration === stateGeneration && request.requestGeneration === loadMoreRequestGeneration,
    isLatestLoadMoreRequest: (request: TaskListLoadMoreRequest) =>
      request.requestGeneration === loadMoreRequestGeneration,
  };
};

const TERMINAL_TASK_STATUSES = new Set([
  ImportExportTaskStatus.SUCCESS,
  ImportExportTaskStatus.FAILED,
  ImportExportTaskStatus.CANCELLED,
]);

const IMPORT_TASK_TYPES = new Set([ImportExportTaskType.DATA_FILE_IMPORT, ImportExportTaskType.SQL_FILE_IMPORT]);

const NON_RETRYABLE_POLLING_ERRORS = new Set<string>([
  ErrorCode.NeedLoggedIn,
  ErrorCode.OfflineInvalidTrial,
  ErrorCode.OfflineInvalidDevice,
  ErrorCode.OfflineTrialExpired,
  ErrorCode.OfflineLicenseExpired,
  ErrorCode.LicenseNotSupported,
]);

interface TaskPage {
  data?: ImportExportTaskDetails[];
  hasNextPage?: boolean;
}

type TaskPageLoader = (params: {
  pageNo: number;
  pageSize: number;
  status: ImportExportTaskStatus;
}) => Promise<TaskPage>;

type TaskDetailsLoader = (params: { taskId: number }) => Promise<ImportExportTaskDetails>;

export const listAllTasksByStatus = async (
  loadPage: TaskPageLoader,
  status: ImportExportTaskStatus,
  pageSize = TASK_LIST_PAGE_SIZE,
) => {
  const tasks: ImportExportTaskDetails[] = [];
  let pageNo = 1;
  let hasNextPage = true;

  while (hasNextPage) {
    const page = await loadPage({ pageNo, pageSize, status });
    tasks.push(...(page.data || []));
    hasNextPage = page.hasNextPage === true;
    pageNo += 1;
  }
  return tasks;
};

/**
 * A status sweep discovers active tasks that are not on the first page. Once the active tasks are
 * tracked, the recent page refreshes the ones still on it and the rest are recovered by id, so the
 * sweep only runs when nothing is tracked yet (first load or an explicit refresh). Steady-state
 * polling therefore costs a single request per round instead of three.
 */
export const shouldScanActiveTasks = (trackedActiveTaskIds: number[]) => trackedActiveTaskIds.length === 0;

/** Only a pending or running task can still be cancelled; the backend answers false for terminal ones. */
export const canCancelTask = (status: ImportExportTaskStatus) => ACTIVE_TASK_STATUSES.includes(status);

/**
 * Applies the terminal state the backend already persisted before answering a successful cancel, so
 * the row leaves the active set immediately instead of waiting for the next scheduled poll. The
 * client clock is deliberately not stamped onto `updatedAt`: a locally newer timestamp would win
 * the comparison in `mergeTasks` and hide the authoritative record of the follow-up refresh.
 */
export const markTaskCancelled = (
  taskList: ImportExportTaskDetails[],
  taskId: number,
  cancelledAt: number,
): ImportExportTaskDetails[] =>
  taskList.map((task) =>
    task.id === taskId
      ? { ...task, status: ImportExportTaskStatus.CANCELLED, finishedAt: task.finishedAt ?? cancelledAt }
      : task,
  );

export const mergeTasks = (...taskGroups: ImportExportTaskDetails[][]) => {
  const tasksById = new Map<number, ImportExportTaskDetails>();
  taskGroups.flat().forEach((task) => {
    const previous = tasksById.get(task.id);
    if (previous) {
      const previousTerminal = TERMINAL_TASK_STATUSES.has(previous.status);
      const incomingTerminal = TERMINAL_TASK_STATUSES.has(task.status);
      if (previousTerminal && !incomingTerminal) return;
      if (previousTerminal === incomingTerminal && taskUpdateTime(task) < taskUpdateTime(previous)) return;
    }
    tasksById.set(task.id, task);
  });
  return [...tasksById.values()].sort((left, right) => {
    const leftCreatedAt = new Date(left.createdAt).getTime() || 0;
    const rightCreatedAt = new Date(right.createdAt).getTime() || 0;
    return rightCreatedAt - leftCreatedAt;
  });
};

const taskUpdateTime = (task: ImportExportTaskDetails) =>
  new Date(task.updatedAt ?? task.finishedAt ?? task.startedAt ?? task.createdAt).getTime() || 0;

export const loadMissingTrackedTasks = async (
  trackedTaskIds: number[],
  visibleTasks: ImportExportTaskDetails[],
  loadDetails: TaskDetailsLoader,
) => {
  const visibleTaskIds = new Set(visibleTasks.map((task) => task.id));
  const missingTaskIds = trackedTaskIds.filter((taskId) => !visibleTaskIds.has(taskId));
  const results = await Promise.all(
    missingTaskIds.map(async (taskId) => {
      try {
        return await loadDetails({ taskId });
      } catch {
        return null;
      }
    }),
  );
  const tasks = results.filter((task): task is ImportExportTaskDetails => task !== null);
  const resolvedTaskIds = new Set(tasks.map((task) => task.id));
  return {
    tasks,
    unresolvedTaskIds: missingTaskIds.filter((taskId) => !resolvedTaskIds.has(taskId)),
  };
};

export const reconcileCompletedTaskNotifications = (
  previousStatuses: TaskStatusById,
  tasks: ImportExportTaskDetails[],
  initialized: boolean,
  previousCursor: TaskNotificationCursor | null = null,
) => {
  const statuses: TaskStatusById = {};
  const newlyCompletedTaskIds: number[] = [];
  let cursor = previousCursor ?? null;

  tasks.forEach((task) => {
    const taskId = String(task.id);
    const previousStatus = previousStatuses[taskId];
    const taskCursor = {
      createdAt: new Date(task.createdAt).getTime() || 0,
      taskId: task.id,
    };
    const isNewerThanPreviousCursor =
      previousCursor == null ||
      taskCursor.createdAt > previousCursor.createdAt ||
      (taskCursor.createdAt === previousCursor.createdAt && taskCursor.taskId > previousCursor.taskId);
    statuses[taskId] = task.status;
    if (
      initialized &&
      TERMINAL_TASK_STATUSES.has(task.status) &&
      ((previousStatus === undefined && isNewerThanPreviousCursor) ||
        (previousStatus !== undefined && !TERMINAL_TASK_STATUSES.has(previousStatus)))
    ) {
      newlyCompletedTaskIds.push(task.id);
    }
    if (
      cursor === null ||
      taskCursor.createdAt > cursor.createdAt ||
      (taskCursor.createdAt === cursor.createdAt && taskCursor.taskId > cursor.taskId)
    ) {
      cursor = taskCursor;
    }
  });

  return { statuses, newlyCompletedCount: newlyCompletedTaskIds.length, newlyCompletedTaskIds, cursor };
};

export const mergeTaskEvents = (currentEvents: ImportExportTaskEvent[], incomingEvents: ImportExportTaskEvent[]) => {
  const eventsBySequence = new Map<number, ImportExportTaskEvent>();
  [...currentEvents, ...incomingEvents].forEach((event) => eventsBySequence.set(event.sequence, event));
  return [...eventsBySequence.values()].sort((left, right) => left.sequence - right.sequence);
};

export const getTaskPollingDelay = (activeTaskCount: number, failed = false) => {
  if (failed) return FAILED_TASK_POLL_INTERVAL;
  return activeTaskCount > 0 ? ACTIVE_TASK_POLL_INTERVAL : null;
};

export const shouldKeepTaskPolling = (taskCenterOpen: boolean, activeTaskCount: number) =>
  taskCenterOpen || activeTaskCount > 0;

export const shouldRetryTaskPolling = (error: unknown): boolean => {
  if (typeof error !== 'object' || error === null || !('errorCode' in error)) {
    return true;
  }
  const errorCode = (error as { errorCode?: unknown }).errorCode;
  return typeof errorCode !== 'string' || !NON_RETRYABLE_POLLING_ERRORS.has(errorCode);
};

export const shouldRefreshImportTargetTable = (
  previousTask: ImportExportTaskDetails | undefined,
  currentTask: ImportExportTaskDetails,
) => {
  if (!IMPORT_TASK_TYPES.has(currentTask.type)) return false;
  if (!currentTask.target?.dataSourceId || !currentTask.target?.tableName) return false;
  if (currentTask.status !== ImportExportTaskStatus.SUCCESS) return false;
  if (previousTask && previousTask.id !== currentTask.id) return false;
  return previousTask?.status !== ImportExportTaskStatus.SUCCESS;
};
