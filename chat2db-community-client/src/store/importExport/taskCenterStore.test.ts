import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import ts from 'typescript';
import type { ImportExportTaskDetails } from '@/typings/importExport';
import { ImportExportTaskStatus, ImportExportTaskType } from '@/constants/importExport';
import type { TaskListParams } from '@/service/importExport';
import type { useImportExportStore } from './index';

type TaskPage = { data: ImportExportTaskDetails[]; hasNextPage: boolean };
interface TaskApi {
  getTaskList: (params: TaskListParams) => Promise<TaskPage>;
  getTaskDetails: (params: { taskId: number }) => Promise<ImportExportTaskDetails>;
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
const tasks = Array.from({ length: 20 }, (_, index): ImportExportTaskDetails => ({
  id: index + 1,
  name: `task-${index + 1}`,
  type: ImportExportTaskType.QUERY_RESULT_EXPORT,
  status: index === 0 ? ImportExportTaskStatus.RUNNING : ImportExportTaskStatus.SUCCESS,
  progress: index === 0 ? 50 : 100,
  createdAt: 20000 - index * 100,
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

const apiWith = (getTaskList: TaskApi['getTaskList']): TaskApi => ({
  getTaskList,
  getTaskDetails: async ({ taskId }) => tasks.find((task) => task.id === taskId)!,
});
const seed = (store: typeof useImportExportStore) => store.setState({
  taskList: tasks.slice(0, 10), taskListHasNextPage: true, taskCenterOpen: true,
  activeTaskIds: [1], activeTaskCount: 1,
});

async function slowLoadSurvivesAutomaticPolling() {
  let polls = 0;
  const store = createStore(apiWith(async (params) => {
    if (params.status) return page(params.status === ImportExportTaskStatus.RUNNING ? [tasks[0]] : []);
    if (params.pageSize > 10) {
      await new Promise((resolve) => setTimeout(resolve, 1200));
      return page(tasks);
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
    assert.equal(store.getState().taskListPageSize, 20);
    assert.equal(store.getState().taskListLoadingMore, false);
  } finally { store.getState().stopTaskListPolling(); }
}

async function stalePageKeepsCompletionFromPolling() {
  const load = deferred<TaskPage>();
  const completed = { ...tasks[0], status: ImportExportTaskStatus.SUCCESS, progress: 100, updatedAt: 40000 };
  const store = createStore(apiWith(async (params) => {
    if (params.status) return page([]);
    return params.pageSize > 10 ? load.promise : page([completed, ...tasks.slice(1, 10)], true);
  }));
  try {
    seed(store);
    const loading = store.getState().loadMoreTasks();
    await store.getState().getTaskList();
    load.resolve(page(tasks));
    await loading;
    assert.equal(store.getState().taskList.length, 20);
    assert.equal(store.getState().taskList.find((task) => task.id === 1)?.status, ImportExportTaskStatus.SUCCESS);
    assert.equal(store.getState().activeTaskCount, 0);
  } finally { store.getState().stopTaskListPolling(); }
}

async function slowRecoveryKeepsNewlyLoadedHistoryAndCompletion() {
  const details = deferred<ImportExportTaskDetails>();
  const requested = deferred<void>();
  const completed = { ...tasks[0], status: ImportExportTaskStatus.SUCCESS, progress: 100, updatedAt: 40000 };
  const store = createStore({
    getTaskList: async (params) => {
      if (params.status) return page([]);
      return params.pageSize > 10 ? page([completed, ...tasks.slice(1)]) : page(tasks.slice(1, 11), true);
    },
    getTaskDetails: async () => { requested.resolve(); return details.promise; },
  });
  try {
    seed(store);
    const polling = store.getState().getTaskList();
    await requested.promise;
    await store.getState().loadMoreTasks();
    details.resolve(tasks[0]);
    await polling;
    assert.equal(store.getState().taskList.length, 20);
    assert.equal(store.getState().taskListPageSize, 20);
    assert.equal(store.getState().taskList.find((task) => task.id === 1)?.status, ImportExportTaskStatus.SUCCESS);
    assert.equal(store.getState().activeTaskCount, 0);
  } finally { store.getState().stopTaskListPolling(); }
}

async function deletionRejectsOldPollingAndPagination() {
  const recent = deferred<TaskPage>();
  const more = deferred<TaskPage>();
  const store = createStore(apiWith(async (params) => {
    if (params.status) return page([]);
    return params.pageSize > 10 ? more.promise : recent.promise;
  }));
  try {
    seed(store);
    const loading = store.getState().loadMoreTasks();
    const polling = store.getState().getTaskList();
    store.getState().removeTask(2);
    recent.resolve(page(tasks.slice(0, 10), true));
    more.resolve(page(tasks));
    await Promise.all([loading, polling]);
    assert.equal(store.getState().taskList.some((task) => task.id === 2), false);
    assert.equal(store.getState().taskListLoadingMore, false);
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
    current.resolve(page(tasks));
    await newLoad;
    assert.equal(store.getState().taskList.length, 20);
    assert.equal(store.getState().taskListLoadingMore, false);
  } finally { store.getState().stopTaskListPolling(); }
}

async function loadFailureCanRetryWithoutDuplicateRequests() {
  const first = deferred<TaskPage>();
  let calls = 0;
  const store = createStore(apiWith(() => (++calls === 1 ? first.promise : Promise.resolve(page(tasks)))));
  try {
    seed(store);
    const failed = store.getState().loadMoreTasks();
    await store.getState().loadMoreTasks();
    assert.equal(calls, 1);
    first.reject(new Error('temporary failure'));
    await assert.rejects(failed, /temporary failure/);
    assert.equal(store.getState().taskListPageSize, 10);
    assert.equal(store.getState().taskListLoadingMore, false);
    await store.getState().loadMoreTasks();
    assert.equal(store.getState().taskList.length, 20);
    assert.equal(calls, 2);
  } finally { store.getState().stopTaskListPolling(); }
}

async function run() {
  for (const scenario of [slowLoadSurvivesAutomaticPolling, stalePageKeepsCompletionFromPolling,
    slowRecoveryKeepsNewlyLoadedHistoryAndCompletion, deletionRejectsOldPollingAndPagination,
    oldCloseResponseCannotFinishNewLoad, loadFailureCanRetryWithoutDuplicateRequests]) {
    await scenario();
    console.log(`ok - ${scenario.name}`);
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
