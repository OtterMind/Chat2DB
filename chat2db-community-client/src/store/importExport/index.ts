import { devtools } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';
import { StateCreator } from 'zustand/vanilla';
import { IDatabaseBaseInfo } from '@/typings/database';
import { ImportExportDataBoundInfo, ImportExportTaskDetails } from '@/typings/importExport';
import { ACTIVE_TASK_STATUSES, ImportExportTaskStatus } from '@/constants/importExport';
import importExportServices from '@/service/importExport';
import {
  parseTaskSpecForWizard,
  type RestoredTaskSpec,
  type RestoreTaskSpecResult,
} from '@/blocks/ImportAndExport/utils/restoreTaskSpec';
import {
  createTaskListRequestCoordinator,
  getTaskPollingDelay,
  listAllTasksByStatus,
  loadMissingTrackedTasks,
  markTaskCancelled,
  mergeTasks,
  reconcileCompletedTaskNotifications,
  shouldKeepTaskPolling,
  shouldScanActiveTasks,
  shouldRetryTaskPolling,
  TASK_CENTER_PAGE_SIZE,
  TaskNotificationCursor,
  TaskStatusById,
} from './taskCenterUtils';

let taskListRequestGeneration = 0;
const taskListRequestCoordinator = createTaskListRequestCoordinator();

interface ImportExportState {
  runSqlBoundInfo: IDatabaseBaseInfo | null;
  importExportDataBoundInfo: ImportExportDataBoundInfo | null;
  /** Wizard prefill produced by "new from this configuration"; null for a regular import/export. */
  importExportPrefill: RestoredTaskSpec | null;
  taskList: ImportExportTaskDetails[];
  taskListPageNo: number;
  taskListHasNextPage: boolean;
  taskListLoadingMore: boolean;
  getTaskListTimer: ReturnType<typeof setTimeout> | null;
  activeTaskCount: number;
  logModalTaskId: number | null;
  unreadCompletedTaskCount: number;
  unreadCompletedTaskIds: number[];
  taskCenterOpen: boolean;
  taskNotificationsInitialized: boolean;
  taskNotificationCursor: TaskNotificationCursor | null;
  taskStatusById: TaskStatusById;
  activeTaskIds: number[];
  cancellingTaskIds: number[];
}

const initialState: ImportExportState = {
  runSqlBoundInfo: null,
  importExportDataBoundInfo: null,
  importExportPrefill: null,
  taskList: [],
  taskListPageNo: 1,
  taskListHasNextPage: false,
  taskListLoadingMore: false,
  getTaskListTimer: null,
  activeTaskCount: 0,
  logModalTaskId: null,
  unreadCompletedTaskCount: 0,
  unreadCompletedTaskIds: [],
  taskCenterOpen: false,
  taskNotificationsInitialized: false,
  taskNotificationCursor: null,
  taskStatusById: {},
  activeTaskIds: [],
  cancellingTaskIds: [],
};

export interface ImportExportAction {
  setRunSqlBoundInfo: (data: ImportExportState['runSqlBoundInfo']) => void;
  setImportExportDataBoundInfo: (data: ImportExportState['importExportDataBoundInfo']) => void;
  /** Restores a historical task's saved configuration into the wizard; see `parseTaskSpecForWizard`. */
  reuseTaskConfiguration: (task: ImportExportTaskDetails) => RestoreTaskSpecResult;
  getTaskList: () => Promise<void>;
  loadMoreTasks: () => Promise<void>;
  stopTaskListPolling: () => void;
  removeTask: (taskId: number) => void;
  cancelTask: (taskId: number) => Promise<boolean>;
  openLogModal: (taskId: number | null) => void;
  setTaskCenterOpen: (open: boolean) => void;
}

export type ImportExportStore = ImportExportState & ImportExportAction;

// Cancelling is idempotent on the backend, but a double click must still send a single request.
const inFlightCancelRequests = new Map<number, Promise<boolean>>();

const applyCancelledTask = (state: ImportExportStore, taskId: number, cancelledAt: number) => {
  const taskList = markTaskCancelled(state.taskList, taskId, cancelledAt);
  const activeTaskIds = state.activeTaskIds.filter((id) => id !== taskId);
  const unreadCompletedTaskIds = state.unreadCompletedTaskIds.filter((id) => id !== taskId);
  return {
    taskList,
    activeTaskIds,
    activeTaskCount: activeTaskIds.length,
    // The user cancelled this task on purpose, so its terminal status must not raise a completion notification.
    taskStatusById: { ...state.taskStatusById, [String(taskId)]: ImportExportTaskStatus.CANCELLED },
    unreadCompletedTaskIds,
    unreadCompletedTaskCount: unreadCompletedTaskIds.length,
  };
};

const applyRefreshedTask = (state: ImportExportStore, task: ImportExportTaskDetails) => {
  const taskList = mergeTasks(state.taskList, [task]);
  const activeTaskIds = ACTIVE_TASK_STATUSES.includes(task.status)
    ? state.activeTaskIds
    : state.activeTaskIds.filter((id) => id !== task.id);
  return { taskList, activeTaskIds, activeTaskCount: activeTaskIds.length };
};

export const createImportExportAction: StateCreator<
  ImportExportStore,
  [['zustand/devtools', never]],
  [],
  ImportExportAction
> = (set, get) => ({
  setRunSqlBoundInfo: (_runSqlBoundInfo) => {
    set({
      runSqlBoundInfo: _runSqlBoundInfo,
    });
  },
  setImportExportDataBoundInfo: (_importExportDataBoundInfo) => {
    set({
      importExportDataBoundInfo: _importExportDataBoundInfo,
      // A prefill belongs to the wizard session a task opened; a regular open or a close drops it.
      importExportPrefill: null,
    });
  },
  reuseTaskConfiguration: (task) => {
    const result = parseTaskSpecForWizard(task);
    if (result.ok) {
      // Opening the wizard and carrying the prefill are one update: the modal reads both.
      set({
        importExportDataBoundInfo: result.restore.boundInfo,
        importExportPrefill: result.restore,
      });
    }
    return result;
  },
  getTaskList: () => {
    const requestGeneration = ++taskListRequestGeneration;
    const previousActiveTaskIds = get().activeTaskIds;
    // clear timer
    const { getTaskListTimer } = get();
    if (getTaskListTimer) {
      clearTimeout(getTaskListTimer);
      set({ getTaskListTimer: null });
    }
    // Only the first round of a polling session scans by status; later rounds reuse the tracked
    // active task ids and refresh them from the recent page or by id.
    const activeTasksRequest = shouldScanActiveTasks(previousActiveTaskIds)
      ? Promise.all([
          listAllTasksByStatus(importExportServices.getTaskList, ImportExportTaskStatus.PENDING),
          listAllTasksByStatus(importExportServices.getTaskList, ImportExportTaskStatus.RUNNING),
        ]).then(([pendingTasks, runningTasks]) => mergeTasks(pendingTasks, runningTasks))
      : Promise.resolve<ImportExportTaskDetails[]>([]);
    return Promise.all([
      importExportServices.getTaskList({ pageNo: 1, pageSize: TASK_CENTER_PAGE_SIZE }),
      activeTasksRequest,
    ])
      .then(async ([recentPage, activeTasks]) => {
        if (requestGeneration !== taskListRequestGeneration) return;
        const visibleTasks = mergeTasks(recentPage.data || [], activeTasks);
        const recovered = await loadMissingTrackedTasks(
          previousActiveTaskIds,
          visibleTasks,
          importExportServices.getTaskDetails,
        );
        if (requestGeneration !== taskListRequestGeneration) return;
        const currentState = get();
        const taskList = mergeTasks(currentState.taskList, visibleTasks, recovered.tasks);
        const knownTaskIds = new Set(taskList.map((task) => task.id));
        const activeTaskIds = taskList
          .filter((task) => ACTIVE_TASK_STATUSES.includes(task.status))
          .map((task) => task.id);
        activeTaskIds.push(...recovered.unresolvedTaskIds.filter((id) => !knownTaskIds.has(id)));
        const pollDelay = getTaskPollingDelay(activeTaskIds.length);
        const notificationUpdate = reconcileCompletedTaskNotifications(
          currentState.taskStatusById,
          taskList,
          currentState.taskNotificationsInitialized,
          currentState.taskNotificationCursor,
        );
        const unreadCompletedTaskIds = currentState.taskCenterOpen
          ? []
          : [...new Set([...currentState.unreadCompletedTaskIds, ...notificationUpdate.newlyCompletedTaskIds])];
        set({
          activeTaskCount: activeTaskIds.length,
          taskList,
          taskListHasNextPage:
            currentState.taskListPageNo === 1 ? recentPage.hasNextPage === true : currentState.taskListHasNextPage,
          unreadCompletedTaskCount: unreadCompletedTaskIds.length,
          unreadCompletedTaskIds,
          taskNotificationsInitialized: true,
          taskNotificationCursor: notificationUpdate.cursor,
          taskStatusById: notificationUpdate.statuses,
          activeTaskIds,
          getTaskListTimer: pollDelay === null ? null : setTimeout(() => get().getTaskList(), pollDelay),
        });
      })
      .catch((error) => {
        if (requestGeneration !== taskListRequestGeneration) return;
        const state = get();
        const retryDelay =
          shouldRetryTaskPolling(error) && shouldKeepTaskPolling(state.taskCenterOpen, state.activeTaskIds.length)
            ? getTaskPollingDelay(0, true)
            : null;
        set({
          getTaskListTimer: retryDelay === null ? null : setTimeout(() => get().getTaskList(), retryDelay),
        });
      });
  },
  loadMoreTasks: () => {
    const { taskListHasNextPage, taskListLoadingMore, taskList, taskListPageNo } = get();
    if (!taskListHasNextPage || taskListLoadingMore || taskList.length === 0) {
      return Promise.resolve();
    }
    // Cursor by the oldest loaded id, not an offset: a new task inserted while the user scrolls
    // cannot shift the window and repeat or skip rows.
    const beforeId = Math.min(...taskList.map((task) => task.id));
    const nextPageNo = taskListPageNo + 1;
    const loadMoreRequest = taskListRequestCoordinator.beginLoadMoreRequest();
    set({ taskListLoadingMore: true });
    return importExportServices
      .getTaskList({ pageNo: 1, pageSize: TASK_CENTER_PAGE_SIZE, beforeId })
      .then((page) => {
        if (!taskListRequestCoordinator.canApplyLoadMoreResponse(loadMoreRequest)) return;
        set({
          taskList: mergeTasks(page.data || [], get().taskList),
          taskListPageNo: nextPageNo,
          taskListHasNextPage: page.hasNextPage === true,
        });
      })
      .catch((error) => {
        if (taskListRequestCoordinator.canApplyLoadMoreResponse(loadMoreRequest)) throw error;
      })
      .finally(() => {
        if (taskListRequestCoordinator.isLatestLoadMoreRequest(loadMoreRequest)) {
          set({ taskListLoadingMore: false });
        }
      });
  },
  stopTaskListPolling: () => {
    taskListRequestGeneration += 1;
    taskListRequestCoordinator.invalidateState();
    const { getTaskListTimer } = get();
    if (getTaskListTimer) {
      clearTimeout(getTaskListTimer);
    }
    set({ getTaskListTimer: null, taskListLoadingMore: false });
  },
  removeTask: (taskId) => {
    taskListRequestGeneration += 1;
    taskListRequestCoordinator.invalidateState();
    const state = get();
    const taskStatusById = { ...state.taskStatusById };
    delete taskStatusById[String(taskId)];
    const unreadCompletedTaskIds = state.unreadCompletedTaskIds.filter((id) => id !== taskId);
    set({
      taskList: state.taskList.filter((task) => task.id !== taskId),
      taskListLoadingMore: false,
      unreadCompletedTaskIds,
      unreadCompletedTaskCount: unreadCompletedTaskIds.length,
      taskStatusById,
      activeTaskIds: state.activeTaskIds.filter((id) => id !== taskId),
    });
  },
  cancelTask: (taskId) => {
    const inFlightRequest = inFlightCancelRequests.get(taskId);
    if (inFlightRequest) {
      return inFlightRequest;
    }
    set((state) => ({ cancellingTaskIds: [...state.cancellingTaskIds, taskId] }));
    const request = importExportServices
      .cancelTask({ taskId })
      .then((cancelled) => {
        if (cancelled) {
          set((state) => applyCancelledTask(state, taskId, Date.now()));
        }
        // Re-read this row without restarting the scheduled poll, so cancelling keeps the 1s cadence.
        // A terminal task answers false and is only refreshed; a failed refresh stays silent because the
        // scheduled poll retries and must not turn a settled cancellation into an error.
        return importExportServices
          .getTaskDetails({ taskId })
          .then((task) => {
            if (task) {
              set((state) => applyRefreshedTask(state, task));
            }
            return cancelled;
          })
          .catch(() => cancelled);
      })
      .finally(() => {
        inFlightCancelRequests.delete(taskId);
        set((state) => ({ cancellingTaskIds: state.cancellingTaskIds.filter((id) => id !== taskId) }));
      });
    inFlightCancelRequests.set(taskId, request);
    return request;
  },
  openLogModal: (taskId) => {
    set({ logModalTaskId: taskId });
  },
  setTaskCenterOpen: (open) => {
    const state = get();
    if (!open) {
      taskListRequestCoordinator.invalidateState();
    }
    if (!open && state.activeTaskIds.length === 0) {
      taskListRequestGeneration += 1;
      if (state.getTaskListTimer) {
        clearTimeout(state.getTaskListTimer);
      }
    }
    set({
      taskCenterOpen: open,
      taskListLoadingMore: open ? state.taskListLoadingMore : false,
      unreadCompletedTaskCount: open ? 0 : get().unreadCompletedTaskCount,
      unreadCompletedTaskIds: open ? [] : get().unreadCompletedTaskIds,
      getTaskListTimer: !open && state.activeTaskIds.length === 0 ? null : state.getTaskListTimer,
    });
  },
});

const createStore: StateCreator<ImportExportStore, [['zustand/devtools', never]]> = (...parameters) => ({
  ...initialState,
  ...createImportExportAction(...parameters),
});

export const useImportExportStore = createWithEqualityFn<ImportExportStore>()(
  devtools(createStore, {
    name: 'Chat2DB_ImportExport_Store',
  }),
  shallow,
);
