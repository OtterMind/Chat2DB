import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import ts from 'typescript';
import type { ImportExportTaskDetails } from '@/typings/importExport';
import { ImportExportTaskStatus, ImportExportTaskType, ImportExportType } from '@/constants/importExport';
import type { TaskListParams } from '@/service/importExport';
import { TASK_CENTER_PAGE_SIZE } from './taskCenterUtils';
import type { useImportExportStore } from './index';

type TaskPage = { data: ImportExportTaskDetails[]; hasNextPage: boolean };
interface TaskApi {
  getTaskList: (params: TaskListParams) => Promise<TaskPage>;
  getTaskDetails: (params: { taskId: number }) => Promise<ImportExportTaskDetails>;
  cancelTask: (params: { taskId: number }) => Promise<boolean>;
}

const clientRoot = process.cwd();
const requirePackage = createRequire(path.join(clientRoot, 'package.json'));
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
};
const page = (data: ImportExportTaskDetails[], hasNextPage = false): TaskPage => ({ data, hasNextPage });
const tasks = Array.from({ length: 30 }, (_, index): ImportExportTaskDetails => ({
  // Ids and timestamps both descend with the array; the backend lists newest first by id.
  id: 30 - index,
  name: `task-${30 - index}`,
  type: ImportExportTaskType.QUERY_RESULT_EXPORT,
  status: index === 0 ? ImportExportTaskStatus.RUNNING : ImportExportTaskStatus.SUCCESS,
  progress: index === 0 ? 50 : 100,
  createdAt: 30000 - index * 100,
  updatedAt: 30000,
}));

// Evaluate the real store with an isolated module scope; replace only its network boundary.
function createStore(api: TaskApi): typeof useImportExportStore {
  const modules = new Map<string, { exports: unknown }>();
  const load = (filename: string): unknown => {
    const cached = modules.get(filename);
    if (cached) return cached.exports;
    const module = { exports: {} as unknown };
    modules.set(filename, module);
    const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
      fileName: filename,
    }).outputText;
    const requireSource = (name: string): unknown => {
      if (name === '@/service/importExport') return api;
      if (!name.startsWith('.') && !name.startsWith('@/')) return requirePackage(name);
      const resolved = name.startsWith('@/')
        ? path.join(clientRoot, 'src', name.slice(2))
        : path.resolve(path.dirname(filename), name);
      return load(fs.existsSync(`${resolved}.ts`) ? `${resolved}.ts` : path.join(resolved, 'index.ts'));
    };
    new Function('require', 'module', 'exports', code)(requireSource, module, module.exports);
    return module.exports;
  };
  return (load(path.join(clientRoot, 'src/store/importExport/index.ts')) as {
    useImportExportStore: typeof useImportExportStore;
  }).useImportExportStore;
}

const createApi = (getTaskList: TaskApi['getTaskList'], overrides: Partial<TaskApi> = {}) => {
  const requests: TaskListParams[] = [];
  const api: TaskApi = {
    getTaskList: (params) => {
      requests.push(params);
      return getTaskList(params);
    },
    getTaskDetails: async ({ taskId }) => tasks.find((task) => task.id === taskId)!,
    cancelTask: async () => false,
    ...overrides,
  };
  return { requests, api };
};
const apiWith = (getTaskList: TaskApi['getTaskList']): TaskApi => createApi(getTaskList).api;
const seed = (store: typeof useImportExportStore) => store.setState({
  taskList: tasks.slice(0, 10), taskListHasNextPage: true, taskListPageNo: 1, taskCenterOpen: true,
  activeTaskIds: [30], activeTaskCount: 1,
});

async function slowLoadSurvivesAutomaticPolling() {
  let polls = 0;
  const store = createStore(apiWith(async (params) => {
    if (params.status) return page(params.status === ImportExportTaskStatus.RUNNING ? [tasks[0]] : []);
    if (params.beforeId !== undefined) {
      await new Promise((resolve) => setTimeout(resolve, 1200));
      return page(tasks.slice(10, 20));
    }
    polls += 1;
    return page(tasks.slice(0, 10), true);
  }));
  try {
    store.getState().setTaskCenterOpen(true);
    await store.getState().getTaskList();
    await store.getState().loadMoreTasks();
    assert.ok(polls >= 2, 'the production polling timer must run during the slow load');
    assert.equal(store.getState().taskList.length, 20);
    assert.equal(store.getState().taskListPageNo, 2);
    assert.equal(store.getState().taskListLoadingMore, false);
  } finally { store.getState().stopTaskListPolling(); }
}

async function loadMoreAppendsPagesWithoutRefetchingLoadedPages() {
  const { requests, api } = createApi(async (params) => {
    const remaining = params.beforeId === undefined
      ? tasks
      : tasks.filter((task) => task.id < params.beforeId!);
    return page(remaining.slice(0, params.pageSize), remaining.length > params.pageSize);
  });
  const store = createStore(api);
  try {
    seed(store);
    requests.length = 0;
    await store.getState().loadMoreTasks();
    await store.getState().loadMoreTasks();
    assert.deepEqual(
      requests.map((request) => [request.pageNo, request.pageSize, request.beforeId]),
      [[1, TASK_CENTER_PAGE_SIZE, 21], [1, TASK_CENTER_PAGE_SIZE, 11]],
      'load more must use the oldest loaded id as a cursor instead of an offset',
    );
    assert.equal(store.getState().taskList.length, 30);
    assert.equal(new Set(store.getState().taskList.map((task) => task.id)).size, 30);
    assert.equal(store.getState().taskListPageNo, 3);
    assert.equal(store.getState().taskListHasNextPage, false);
  } finally { store.getState().stopTaskListPolling(); }
}

async function stalePageKeepsCompletionFromPolling() {
  const load = deferred<TaskPage>();
  const completed = { ...tasks[0], status: ImportExportTaskStatus.SUCCESS, progress: 100, updatedAt: 40000 };
  const { requests, api } = createApi(async (params) => {
    if (params.status) return page([]);
    return params.beforeId !== undefined ? load.promise : page([completed, ...tasks.slice(1, 10)], true);
  });
  const store = createStore(api);
  try {
    seed(store);
    const loading = store.getState().loadMoreTasks();
    await store.getState().getTaskList();
    load.resolve(page(tasks.slice(10, 20)));
    await loading;
    assert.equal(store.getState().taskList.length, 20);
    assert.equal(store.getState().taskList.find((task) => task.id === 30)?.status, ImportExportTaskStatus.SUCCESS);
    assert.equal(store.getState().activeTaskCount, 0);
    assert.equal(
      requests.filter((request) => request.status).length,
      0,
      'tracked active tasks must not trigger a status sweep while the recent page already covers them',
    );
  } finally { store.getState().stopTaskListPolling(); }
}

async function slowRecoveryKeepsNewlyLoadedHistoryAndCompletion() {
  const details = deferred<ImportExportTaskDetails>();
  const requested = deferred<void>();
  const completed = { ...tasks[0], status: ImportExportTaskStatus.SUCCESS, progress: 100, updatedAt: 40000 };
  const store = createStore({
    getTaskList: async (params) => {
      if (params.status) return page([]);
      return params.beforeId !== undefined
        ? page([completed, ...tasks.slice(11, 20)])
        : page(tasks.slice(1, 11), true);
    },
    getTaskDetails: async () => { requested.resolve(); return details.promise; },
    cancelTask: async () => false,
  });
  try {
    seed(store);
    const polling = store.getState().getTaskList();
    await requested.promise;
    await store.getState().loadMoreTasks();
    details.resolve(tasks[0]);
    await polling;
    assert.equal(store.getState().taskList.length, 20);
    assert.equal(store.getState().taskListPageNo, 2);
    assert.equal(store.getState().taskList.find((task) => task.id === 30)?.status, ImportExportTaskStatus.SUCCESS);
    assert.equal(store.getState().activeTaskCount, 0);
  } finally { store.getState().stopTaskListPolling(); }
}

async function deletionRejectsOldPollingAndPagination() {
  const recent = deferred<TaskPage>();
  const more = deferred<TaskPage>();
  const store = createStore(apiWith(async (params) => {
    if (params.status) return page([]);
    return params.beforeId !== undefined ? more.promise : recent.promise;
  }));
  try {
    seed(store);
    const loading = store.getState().loadMoreTasks();
    const polling = store.getState().getTaskList();
    store.getState().removeTask(29);
    recent.resolve(page(tasks.slice(0, 10), true));
    more.resolve(page(tasks.slice(10, 20)));
    await Promise.all([loading, polling]);
    assert.equal(store.getState().taskList.some((task) => task.id === 29), false);
    assert.equal(store.getState().taskListLoadingMore, false);
  } finally { store.getState().stopTaskListPolling(); }
}

async function deletionKeepsTheCursorOnTheRemainingOldestTask() {
  const { api } = createApi(async (params) => {
    const remaining = params.beforeId === undefined
      ? tasks
      : tasks.filter((task) => task.id < params.beforeId!);
    return page(remaining.slice(0, params.pageSize), remaining.length > params.pageSize);
  });
  const store = createStore(api);
  try {
    seed(store);
    await store.getState().loadMoreTasks();
    assert.equal(store.getState().taskListPageNo, 2);
    store.getState().removeTask(29);
    assert.equal(store.getState().taskList.some((task) => task.id === 29), false);
    await store.getState().loadMoreTasks();
    assert.equal(store.getState().taskListPageNo, 3);
    assert.equal(store.getState().taskList.length, 29, 'the cursor continues from the oldest remaining row');
    assert.equal(new Set(store.getState().taskList.map((task) => task.id)).size, 29);
  } finally { store.getState().stopTaskListPolling(); }
}

async function oldCloseResponseCannotFinishNewLoad() {
  const old = deferred<TaskPage>();
  const current = deferred<TaskPage>();
  let calls = 0;
  const store = createStore(apiWith(() => (++calls === 1 ? old.promise : current.promise)));
  try {
    seed(store);
    const oldLoad = store.getState().loadMoreTasks();
    store.getState().setTaskCenterOpen(false);
    assert.equal(store.getState().taskListLoadingMore, false);
    store.getState().setTaskCenterOpen(true);
    const newLoad = store.getState().loadMoreTasks();
    old.reject(new Error('old request failed after closing'));
    await oldLoad;
    assert.equal(store.getState().taskListLoadingMore, true);
    current.resolve(page(tasks.slice(10, 20)));
    await newLoad;
    assert.equal(store.getState().taskList.length, 20);
    assert.equal(store.getState().taskListLoadingMore, false);
  } finally { store.getState().stopTaskListPolling(); }
}

async function loadFailureCanRetryWithoutDuplicateRequests() {
  const first = deferred<TaskPage>();
  let calls = 0;
  const retryPage = page(tasks.slice(10, 20));
  const store = createStore(apiWith(() => (++calls === 1 ? first.promise : Promise.resolve(retryPage))));
  try {
    seed(store);
    const failed = store.getState().loadMoreTasks();
    await store.getState().loadMoreTasks();
    assert.equal(calls, 1);
    first.reject(new Error('temporary failure'));
    await assert.rejects(failed, /temporary failure/);
    assert.equal(store.getState().taskListPageNo, 1);
    assert.equal(store.getState().taskListLoadingMore, false);
    await store.getState().loadMoreTasks();
    assert.equal(store.getState().taskList.length, 20);
    assert.equal(calls, 2);
  } finally { store.getState().stopTaskListPolling(); }
}

async function pollingConvergesToSinglePageRequestPerRound() {
  const running = { ...tasks[0], status: ImportExportTaskStatus.RUNNING, progress: 50 };
  const completed = { ...running, status: ImportExportTaskStatus.SUCCESS, progress: 100, updatedAt: 40000 };
  let round = 0;
  const { requests, api } = createApi(async (params) => {
    if (params.status) return page(params.status === ImportExportTaskStatus.RUNNING ? [running] : []);
    round += 1;
    return page(round === 1 ? [running, ...tasks.slice(1, 10)] : [completed, ...tasks.slice(1, 10)], true);
  });
  const store = createStore(api);
  try {
    store.getState().setTaskCenterOpen(true);
    await store.getState().getTaskList();
    assert.equal(requests.length, 3, 'the first round discovers active tasks with one page request and two sweeps');
    assert.equal(requests.filter((request) => !request.status).length, 1);

    requests.length = 0;
    await store.getState().getTaskList();
    assert.equal(requests.length, 1, 'later rounds only refresh the recent page while active tasks stay visible');
    assert.deepEqual(requests[0], { pageNo: 1, pageSize: TASK_CENTER_PAGE_SIZE });
    assert.equal(store.getState().taskList.find((task) => task.id === 30)?.status, ImportExportTaskStatus.SUCCESS);
    assert.equal(store.getState().activeTaskCount, 0);
    assert.equal(store.getState().getTaskListTimer, null, 'polling stops when no active task remains');
  } finally { store.getState().stopTaskListPolling(); }
}

async function cancellationUpdatesTheRowAndKeepsThePollSchedule() {
  const cancelRequests: number[] = [];
  const refreshedTaskIds: number[] = [];
  const cancelledTask = {
    ...tasks[0],
    status: ImportExportTaskStatus.CANCELLED,
    finishedAt: 41000,
    updatedAt: 41000,
  };
  const { api } = createApi(
    async (params) => {
      if (params.status) return page(params.status === ImportExportTaskStatus.RUNNING ? [tasks[0]] : []);
      return page(tasks.slice(0, 10), true);
    },
    {
      cancelTask: async ({ taskId }) => {
        cancelRequests.push(taskId);
        return true;
      },
      getTaskDetails: async ({ taskId }) => {
        refreshedTaskIds.push(taskId);
        return { ...cancelledTask, id: taskId };
      },
    },
  );
  const store = createStore(api);
  try {
    store.getState().setTaskCenterOpen(true);
    await store.getState().getTaskList();
    const scheduledPoll = store.getState().getTaskListTimer;
    assert.ok(scheduledPoll, 'a running task keeps the 1s polling timer scheduled');
    assert.deepEqual(store.getState().activeTaskIds, [30]);

    assert.equal(await store.getState().cancelTask(30), true);

    assert.deepEqual(cancelRequests, [30]);
    assert.deepEqual(refreshedTaskIds, [30], 'a successful cancellation refreshes the authoritative record');
    const cancelledRow = store.getState().taskList.find((task) => task.id === 30);
    assert.equal(cancelledRow?.status, ImportExportTaskStatus.CANCELLED);
    assert.equal(cancelledRow?.finishedAt, 41000, 'the refreshed record replaces the optimistic row');
    assert.deepEqual(store.getState().activeTaskIds, [], 'the cancelled task leaves the active set');
    assert.equal(store.getState().activeTaskCount, 0);
    assert.deepEqual(store.getState().cancellingTaskIds, [], 'the in-flight marker is released');
    assert.equal(
      store.getState().getTaskListTimer,
      scheduledPoll,
      'cancelling must not restart or drop the scheduled 1s polling round',
    );
  } finally {
    store.getState().stopTaskListPolling();
  }
}

async function concurrentCancellationClicksSendOneRequest() {
  const pendingCancel = deferred<boolean>();
  let cancelCalls = 0;
  const { api } = createApi(async (params) => (params.status ? page([]) : page(tasks.slice(0, 10), true)), {
    cancelTask: () => {
      cancelCalls += 1;
      return pendingCancel.promise;
    },
  });
  const store = createStore(api);
  try {
    seed(store);
    const firstClick = store.getState().cancelTask(30);
    const secondClick = store.getState().cancelTask(30);
    assert.equal(cancelCalls, 1, 'a second click while the request is in flight must not send another request');
    assert.deepEqual(store.getState().cancellingTaskIds, [30], 'the row is marked busy so the action can be disabled');

    pendingCancel.resolve(true);
    assert.deepEqual(await Promise.all([firstClick, secondClick]), [true, true]);
    assert.equal(cancelCalls, 1);
    assert.equal(store.getState().taskList.find((task) => task.id === 30)?.status, ImportExportTaskStatus.CANCELLED);
    assert.deepEqual(store.getState().cancellingTaskIds, []);
  } finally {
    store.getState().stopTaskListPolling();
  }
}

async function cancellingATerminalTaskOnlyRefreshes() {
  const refreshedTaskIds: number[] = [];
  let cancelCalls = 0;
  const finishedTask = {
    ...tasks[0],
    status: ImportExportTaskStatus.SUCCESS,
    progress: 100,
    finishedAt: 41000,
    updatedAt: 41000,
  };
  const { api } = createApi(async (params) => (params.status ? page([]) : page(tasks.slice(0, 10), true)), {
    cancelTask: async () => {
      cancelCalls += 1;
      return false;
    },
    getTaskDetails: async ({ taskId }) => {
      refreshedTaskIds.push(taskId);
      return { ...finishedTask, id: taskId };
    },
  });
  const store = createStore(api);
  try {
    seed(store);
    assert.equal(await store.getState().cancelTask(30), false, 'a terminal task reports that nothing changed');
    assert.equal(cancelCalls, 1);
    assert.deepEqual(refreshedTaskIds, [30], 'a false answer only refreshes the row instead of reporting an error');
    assert.equal(store.getState().taskList.find((task) => task.id === 30)?.status, ImportExportTaskStatus.SUCCESS);
    assert.deepEqual(store.getState().activeTaskIds, [], 'the refreshed terminal row stops being tracked');
    assert.equal(store.getState().activeTaskCount, 0);
    assert.deepEqual(store.getState().cancellingTaskIds, []);
  } finally {
    store.getState().stopTaskListPolling();
  }
}

async function cancellationFailureCanBeRetried() {
  let cancelCalls = 0;
  const { api } = createApi(async (params) => (params.status ? page([]) : page(tasks.slice(0, 10), true)), {
    cancelTask: async () => {
      cancelCalls += 1;
      if (cancelCalls === 1) throw new Error('cancel endpoint unavailable');
      return true;
    },
  });
  const store = createStore(api);
  try {
    seed(store);
    await assert.rejects(store.getState().cancelTask(30), /cancel endpoint unavailable/);
    assert.equal(store.getState().taskList.find((task) => task.id === 30)?.status, ImportExportTaskStatus.RUNNING);
    assert.deepEqual(store.getState().cancellingTaskIds, [], 'a failed request releases the row so the user can retry');
    assert.equal(store.getState().activeTaskCount, 1);

    assert.equal(await store.getState().cancelTask(30), true);
    assert.equal(cancelCalls, 2);
    assert.equal(store.getState().taskList.find((task) => task.id === 30)?.status, ImportExportTaskStatus.CANCELLED);
  } finally {
    store.getState().stopTaskListPolling();
  }
}

async function cancellationKeepsTheLocalTerminalStateWhenTheRefreshFails() {
  const { api } = createApi(async (params) => (params.status ? page([]) : page(tasks.slice(0, 10), true)), {
    cancelTask: async () => true,
    getTaskDetails: async () => {
      throw new Error('refresh failed');
    },
  });
  const store = createStore(api);
  try {
    seed(store);
    assert.equal(await store.getState().cancelTask(30), true, 'a failed refresh must not fail the cancellation');
    assert.equal(store.getState().taskList.find((task) => task.id === 30)?.status, ImportExportTaskStatus.CANCELLED);
    assert.deepEqual(store.getState().activeTaskIds, []);
    assert.equal(store.getState().activeTaskCount, 0);
    assert.deepEqual(store.getState().cancellingTaskIds, []);
  } finally {
    store.getState().stopTaskListPolling();
  }
}

const exportSpecTask = (): ImportExportTaskDetails => ({
  ...tasks[0],
  id: 21,
  type: ImportExportTaskType.TABLE_DATA_EXPORT,
  specJson: JSON.stringify({
    taskType: 'TABLE_DATA_EXPORT',
    target: { dataSourceId: 5, databaseName: 'shop', schemaName: 'public', tableName: 'orders' },
    tableNames: ['orders'],
    format: 'XLSX',
    scope: 'TABLE',
    containsHeader: false,
    exportPath: '/tmp/export',
  }),
});

async function reuseConfigurationOpensTheWizardWithTheRestoredSpec() {
  const store = createStore(apiWith(async () => page([])));
  try {
    const reusable = exportSpecTask();
    store.setState({ taskList: [reusable], importExportDataBoundInfo: null, importExportPrefill: null });

    const result = store.getState().reuseTaskConfiguration(reusable);

    assert.equal(result.ok, true, 'a task with a saved snapshot can seed the wizard');
    assert.deepEqual(
      store.getState().importExportDataBoundInfo,
      {
        dataSourceId: 5,
        databaseName: 'shop',
        schemaName: 'public',
        tableName: 'orders',
        type: ImportExportType.EXPORT,
      },
      'opening the wizard is part of the same update as the restore',
    );
    assert.equal(store.getState().importExportPrefill?.formValue.exportType, 'XLSX');
    assert.equal(store.getState().importExportPrefill?.formValue.containsHeader, false);
    assert.equal(store.getState().importExportPrefill?.exportPath, '/tmp/export');
    assert.deepEqual(store.getState().taskList.map((task) => task.id), [21], 'the list is untouched by a restore');
  } finally {
    store.getState().stopTaskListPolling();
  }
}

async function legacyTasksCannotOpenTheWizard() {
  const store = createStore(apiWith(async () => page([])));
  try {
    const legacy = { ...tasks[1], specJson: null };
    store.setState({ taskList: [legacy], importExportDataBoundInfo: null, importExportPrefill: null });

    const result = store.getState().reuseTaskConfiguration(legacy);

    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.equal(result.reason, 'EMPTY_SPEC');
      assert.equal(result.i18nKey, 'workspace.task.reuse.unavailable');
    }
    assert.equal(store.getState().importExportDataBoundInfo, null, 'a disabled action must not open a wizard');
    assert.equal(store.getState().importExportPrefill, null);
    assert.deepEqual(store.getState().taskList.map((task) => task.id), [29]);
  } finally {
    store.getState().stopTaskListPolling();
  }
}

async function damagedSpecsReportAnErrorAndKeepTheListRenderable() {
  const store = createStore(apiWith(async () => page([])));
  try {
    const damaged = { ...tasks[0], id: 31, specJson: '{"taskType":' };
    const neighbour = { ...tasks[1] };
    store.setState({ taskList: [damaged, neighbour], importExportDataBoundInfo: null, importExportPrefill: null });

    const result = store.getState().reuseTaskConfiguration(damaged);

    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.equal(result.reason, 'INVALID_JSON');
      assert.equal(result.i18nKey, 'workspace.task.reuse.invalidSpec');
    }
    assert.equal(store.getState().importExportDataBoundInfo, null, 'a damaged snapshot must not open an empty wizard');
    assert.equal(store.getState().importExportPrefill, null);
    assert.deepEqual(
      store.getState().taskList.map((task) => task.id),
      [31, 29],
      'failing to restore must not disturb the rows the task center renders',
    );
  } finally {
    store.getState().stopTaskListPolling();
  }
}

async function closingTheWizardDropsTheStalePrefill() {
  const store = createStore(apiWith(async () => page([])));
  try {
    const reusable = exportSpecTask();
    store.setState({ taskList: [reusable], importExportDataBoundInfo: null, importExportPrefill: null });

    assert.equal(store.getState().reuseTaskConfiguration(reusable).ok, true);
    assert.ok(store.getState().importExportPrefill, 'the restore is handed to the wizard');

    store.getState().setImportExportDataBoundInfo(null);
    assert.equal(store.getState().importExportPrefill, null, 'closing the wizard drops the restore');

    store.getState().reuseTaskConfiguration(reusable);
    store.getState().setImportExportDataBoundInfo({
      dataSourceId: 1,
      databaseName: 'other',
      tableName: 'other_table',
      type: ImportExportType.IMPORT,
    });
    assert.equal(
      store.getState().importExportPrefill,
      null,
      'a regular import/export must not inherit a stale restore',
    );
    assert.equal(store.getState().importExportDataBoundInfo?.tableName, 'other_table');
  } finally {
    store.getState().stopTaskListPolling();
  }
}

async function run() {
  for (const scenario of [slowLoadSurvivesAutomaticPolling, loadMoreAppendsPagesWithoutRefetchingLoadedPages,
    stalePageKeepsCompletionFromPolling, slowRecoveryKeepsNewlyLoadedHistoryAndCompletion,
    deletionRejectsOldPollingAndPagination, deletionKeepsTheCursorOnTheRemainingOldestTask,
    oldCloseResponseCannotFinishNewLoad,
    loadFailureCanRetryWithoutDuplicateRequests, pollingConvergesToSinglePageRequestPerRound,
    cancellationUpdatesTheRowAndKeepsThePollSchedule, concurrentCancellationClicksSendOneRequest,
    cancellingATerminalTaskOnlyRefreshes, cancellationFailureCanBeRetried,
    cancellationKeepsTheLocalTerminalStateWhenTheRefreshFails, reuseConfigurationOpensTheWizardWithTheRestoredSpec,
    legacyTasksCannotOpenTheWizard, damagedSpecsReportAnErrorAndKeepTheListRenderable,
    closingTheWizardDropsTheStalePrefill]) {
    await scenario();
    console.log(`ok - ${scenario.name}`);
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
