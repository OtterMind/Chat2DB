import type { ImportExportTaskDetails } from '@/typings/importExport';
import { canResumeTask, isImportTaskType, parseProcessedRows } from './taskArtifacts';

type Task = ImportExportTaskDetails;
type Spec = Record<string, unknown>;
const specOf = (task: Task): Spec => {
  try { return JSON.parse(task.specJson || '{}') || {}; } catch { return {}; }
};
const isId = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const baseName = (name: string) => name.replace(/(?: \(resumed\))+$/, '');
const sameTarget = (left: unknown, right: unknown): boolean => {
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object') return false;
  const fields = ['dataSourceId', 'databaseName', 'schemaName', 'tableName'];
  return fields.every(field => (left as Spec)[field] === (right as Spec)[field]);
};

export interface TaskAttemptGroup {
  id: number;
  name: string;
  latest: Task;
  attempts: Task[];
  resumeCount: number;
}

/** Keep each run's immutable logs; only the task-center presentation groups continuations. */
export const groupTaskAttempts = (tasks: Task[]): TaskAttemptGroup[] => {
  const byId = new Map(tasks.map(task => [task.id, task]));
  const specs = new Map(tasks.map(task => [task.id, specOf(task)]));
  const parent = new Map<number, number>();
  tasks.filter(task => isImportTaskType(task.type)).forEach(task => {
    const spec = specs.get(task.id)!;
    if (isId(spec.resumeRootTaskId) && spec.resumeRootTaskId !== task.id) {
      parent.set(task.id, spec.resumeRootTaskId);
      return;
    }
    // Old runs have no lineage. Link only one unambiguous predecessor with the exact source,
    // target, checkpoint and generated continuation name; never merge independent repeated imports.
    if (!task.name.endsWith(' (resumed)') || typeof spec.resumeFromRow !== 'number' || !spec.sourceFile) return;
    const candidates = tasks.filter(candidate => {
      if (candidate.id >= task.id || candidate.type !== task.type || !canResumeTask(candidate)) return false;
      const previous = specs.get(candidate.id)!;
      return previous.sourceFile === spec.sourceFile &&
        sameTarget(previous.target, spec.target) &&
        task.name === `${candidate.name} (resumed)` &&
        parseProcessedRows(candidate.checkpointJson) === spec.resumeFromRow;
    });
    if (candidates.length === 1) parent.set(task.id, candidates[0].id);
  });
  const rootOf = (id: number): number => {
    const seen = new Set<number>();
    let current = id;
    while (parent.has(current)) {
      if (seen.has(current)) return id;
      seen.add(current);
      current = parent.get(current)!;
    }
    return current;
  };
  const groups = new Map<number, TaskAttemptGroup>();
  tasks.forEach(task => {
    const rootId = rootOf(task.id);
    const spec = specs.get(task.id)!;
    const root = byId.get(rootId);
    const count = typeof spec.resumeAttempt === 'number' && Number.isSafeInteger(spec.resumeAttempt)
      ? Math.max(0, spec.resumeAttempt) : 0;
    let group = groups.get(rootId);
    if (!group) {
      group = { id: rootId, name: root?.name || (typeof spec.resumeRootTaskName === 'string'
        ? spec.resumeRootTaskName : rootId !== task.id ? baseName(task.name) : task.name),
      latest: task, attempts: [], resumeCount: count };
      groups.set(rootId, group);
    }
    group.attempts.push(task);
    if (task.id > group.latest.id) group.latest = task;
    group.resumeCount = Math.max(group.resumeCount, count, group.attempts.length - 1);
  });
  return [...groups.values()].sort((a, b) => b.latest.id - a.latest.id)
    .map(group => ({ ...group, attempts: group.attempts.sort((a, b) => b.id - a.id) }));
};

/** Opening an old log must not offer another continuation after a newer run already exists. */
export const isLatestTaskAttempt = (task: Task, tasks: Task[]): boolean => {
  const all = tasks.some(item => item.id === task.id) ? tasks : [...tasks, task];
  const group = groupTaskAttempts(all).find(item => item.attempts.some(attempt => attempt.id === task.id));
  return group?.latest.id === task.id;
};
