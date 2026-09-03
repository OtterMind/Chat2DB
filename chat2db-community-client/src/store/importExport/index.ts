import { devtools } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';
import { StateCreator } from 'zustand/vanilla';
import { IDatabaseBaseInfo } from '@/typings/database';
import { ImportExportDataBoundInfo, ImportExportTaskDetails } from '@/typings/importExport';
import { ImportExportTaskStatus } from '@/constants/importExport';
import importExportServices from '@/service/importExport';
import {
  createTaskListRequestCoordinator,
  getTaskPollingDelay,
  listAllTasksByStatus,
  loadMissingTrackedTasks,
  mergeTasks,
  reconcileCompletedTaskNotifications,
  shouldKeepTaskPolling,
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
  taskList: ImportExportTaskDetails[];
  taskListPageSize: number;
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
}

const initialState: ImportExportState = {
  runSqlBoundInfo: null,
  importExportDataBoundInfo: null,
  taskList: [],
  taskListPageSize: TASK_CENTER_PAGE_SIZE,
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
};

export interface ImportExportAction {
  setRunSqlBoundInfo: (data: ImportExportState['runSqlBoundInfo']) => void;
  setImportExportDataBoundInfo: (data: ImportExportState['importExportDataBoundInfo']) => void;
  getTaskList: () => Promise<void>;
  loadMoreTasks: () => Promise<void>;
  stopTaskListPolling: () => void;
  removeTask: (taskId: number) => void;
  openLogModal: (taskId: number | null) => void;
  setTaskCenterOpen: (open: boolean) => void;
}

export type ImportExportStore = ImportExportState & ImportExportAction;

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
    });
  },
  getTaskList: () => {
    const requestGeneration = ++taskListRequestGeneration;
    taskListRequestCoordinator.invalidateState();
    const previousActiveTaskIds = get().activeTaskIds;
    // clear timer
    const { getTaskListTimer } = get();
    if (getTaskListTimer) {
      clearTimeout(getTaskListTimer);
      set({ getTaskListTimer: null });
    }
    return Promise.all([
      importExportServices.getTaskList({ pageNo: 1, pageSize: TASK_CENTER_PAGE_SIZE }),
      listAllTasksByStatus(importExportServices.getTaskList, ImportExportTaskStatus.PENDING),
      listAllTasksByStatus(importExportServices.getTaskList, ImportExportTaskStatus.RUNNING),
    ])
      .then(async ([recentPage, pendingTasks, runningTasks]) => {
        if (requestGeneration !== taskListRequestGeneration) return;
        const activeTasks = mergeTasks(pendingTasks, runningTasks);
        const previouslyLoadedTasks = get().taskList.filter((task) => !previousActiveTaskIds.includes(task.id));
        const visibleTasks = mergeTasks(previouslyLoadedTasks, recentPage.data || [], activeTasks);
        const recovered = await loadMissingTrackedTasks(
          previousActiveTaskIds,
          visibleTasks,
          importExportServices.getTaskDetails,
        );
        if (requestGeneration !== taskListRequestGeneration) return;
        taskListRequestCoordinator.invalidateState();
        const taskList = mergeTasks(visibleTasks, recovered.tasks);
        const recoveredActiveTaskIds = recovered.tasks
          .filter((task) => [ImportExportTaskStatus.PENDING, ImportExportTaskStatus.RUNNING].includes(task.status))
          .map((task) => task.id);
        const activeTaskIds = [
          ...new Set([
            ...activeTasks.map((task) => task.id),
            ...recoveredActiveTaskIds,
            ...recovered.unresolvedTaskIds,
          ]),
        ];
        const pollDelay = getTaskPollingDelay(activeTaskIds.length);
        const currentState = get();
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
            currentState.taskListPageSize === TASK_CENTER_PAGE_SIZE
              ? recentPage.hasNextPage === true
              : currentState.taskListHasNextPage,
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
    const { taskListHasNextPage, taskListLoadingMore, taskListPageSize } = get();
    if (!taskListHasNextPage || taskListLoadingMore) {
      return Promise.resolve();
    }
    const nextPageSize = taskListPageSize + TASK_CENTER_PAGE_SIZE;
    const loadMoreRequest = taskListRequestCoordinator.beginLoadMoreRequest();
    set({ taskListLoadingMore: true });
    return importExportServices
      .getTaskList({ pageNo: 1, pageSize: nextPageSize })
      .then((page) => {
        if (!taskListRequestCoordinator.canApplyLoadMoreResponse(loadMoreRequest)) return;
        const activeStatuses = new Set<ImportExportTaskStatus>([
          ImportExportTaskStatus.PENDING,
          ImportExportTaskStatus.RUNNING,
        ]);
        const activeTasks = get().taskList.filter((task) => activeStatuses.has(task.status));
        set({
          taskList: mergeTasks(page.data || [], activeTasks),
          taskListPageSize: nextPageSize,
          taskListHasNextPage: page.hasNextPage === true,
        });
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
      set({ getTaskListTimer: null });
    }
  },
  removeTask: (taskId) => {
    taskListRequestCoordinator.invalidateState();
    const state = get();
    const taskStatusById = { ...state.taskStatusById };
    delete taskStatusById[String(taskId)];
    const unreadCompletedTaskIds = state.unreadCompletedTaskIds.filter((id) => id !== taskId);
    set({
      taskList: state.taskList.filter((task) => task.id !== taskId),
      taskListPageSize: Math.max(TASK_CENTER_PAGE_SIZE, state.taskListPageSize - 1),
      unreadCompletedTaskIds,
      unreadCompletedTaskCount: unreadCompletedTaskIds.length,
      taskStatusById,
      activeTaskIds: state.activeTaskIds.filter((id) => id !== taskId),
    });
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
