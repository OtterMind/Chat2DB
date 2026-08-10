import { ImportExportTaskStatus } from '@/constants/importExport';
import { ImportExportTaskDetails, ImportExportTaskEvent } from '@/typings/importExport';

export const TASK_LIST_PAGE_SIZE = 100;
export const TASK_CENTER_PAGE_SIZE = 10;
export const TASK_EVENT_INITIAL_PAGE_SIZE = 10;
export const TASK_EVENT_PAGE_SIZE = 200;
export const ACTIVE_TASK_POLL_INTERVAL = 1000;
export const FAILED_TASK_POLL_INTERVAL = 1500;

export type TaskStatusById = Record<string, ImportExportTaskStatus>;

export interface TaskNotificationCursor {
  createdAt: number;
  taskId: number;
}

const TERMINAL_TASK_STATUSES = new Set([
  ImportExportTaskStatus.SUCCESS,
  ImportExportTaskStatus.FAILED,
  ImportExportTaskStatus.CANCELLED,
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

export const mergeTasks = (...taskGroups: ImportExportTaskDetails[][]) => {
  const tasksById = new Map<number, ImportExportTaskDetails>();
  taskGroups.flat().forEach((task) => tasksById.set(task.id, task));
  return [...tasksById.values()].sort((left, right) => {
    const leftCreatedAt = new Date(left.createdAt).getTime() || 0;
    const rightCreatedAt = new Date(right.createdAt).getTime() || 0;
    return rightCreatedAt - leftCreatedAt;
  });
};

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
