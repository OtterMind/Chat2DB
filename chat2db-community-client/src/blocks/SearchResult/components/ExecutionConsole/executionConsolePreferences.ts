import type { SqlExecutionLogRecord } from '@/service/sqlExecutionLog';

export type ExecutionConsoleOrder = 'oldest-first' | 'newest-first';

interface ExecutionConsolePreferenceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

type ExecutionConsoleKeepHistoryListener = (storageKey: string, keepHistory: boolean) => void;

export const DEFAULT_EXECUTION_CONSOLE_ORDER: ExecutionConsoleOrder = 'oldest-first';
export const DEFAULT_EXECUTION_CONSOLE_KEEP_HISTORY = true;

const keepHistoryListeners = new Set<ExecutionConsoleKeepHistoryListener>();

export function createExecutionConsoleOrderStorageKey(clientEdition: string, runtimeEnv: string) {
  return `chat2db.${clientEdition}.${runtimeEnv}.execution-console.order.v1`;
}

export function createExecutionConsoleKeepHistoryStorageKey(clientEdition: string, runtimeEnv: string) {
  return `chat2db.${clientEdition}.${runtimeEnv}.execution-console.keep-history.v1`;
}

export function getExecutionConsolePreferenceStorage(): ExecutionConsolePreferenceStorage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.localStorage;
  } catch {
    return undefined;
  }
}

export function readExecutionConsoleOrder(
  storage: ExecutionConsolePreferenceStorage | undefined,
  storageKey: string,
): ExecutionConsoleOrder {
  try {
    const storedValue = storage?.getItem(storageKey);
    return storedValue === 'oldest-first' || storedValue === 'newest-first'
      ? storedValue
      : DEFAULT_EXECUTION_CONSOLE_ORDER;
  } catch {
    return DEFAULT_EXECUTION_CONSOLE_ORDER;
  }
}

export function persistExecutionConsoleOrder(
  storage: ExecutionConsolePreferenceStorage | undefined,
  storageKey: string,
  order: ExecutionConsoleOrder,
) {
  try {
    storage?.setItem(storageKey, order);
  } catch {
    // Storage can be unavailable in restricted browser contexts.
  }
}

export function readExecutionConsoleKeepHistory(
  storage: ExecutionConsolePreferenceStorage | undefined,
  storageKey: string,
) {
  try {
    const storedValue = storage?.getItem(storageKey);
    return storedValue === 'false' ? false : DEFAULT_EXECUTION_CONSOLE_KEEP_HISTORY;
  } catch {
    return DEFAULT_EXECUTION_CONSOLE_KEEP_HISTORY;
  }
}

export function persistExecutionConsoleKeepHistory(
  storage: ExecutionConsolePreferenceStorage | undefined,
  storageKey: string,
  keepHistory: boolean,
) {
  try {
    storage?.setItem(storageKey, String(keepHistory));
  } catch {
    // Storage can be unavailable in restricted browser contexts.
  }
  keepHistoryListeners.forEach((listener) => listener(storageKey, keepHistory));
}

export function subscribeExecutionConsoleKeepHistory(listener: ExecutionConsoleKeepHistoryListener) {
  keepHistoryListeners.add(listener);
  return () => {
    keepHistoryListeners.delete(listener);
  };
}

export function orderExecutionLogRecords(
  records: readonly SqlExecutionLogRecord[],
  order: ExecutionConsoleOrder,
): SqlExecutionLogRecord[] {
  if (order === 'oldest-first') {
    return [...records];
  }

  const executionGroups = new Map<string, SqlExecutionLogRecord[]>();
  records.forEach((record) => {
    const group = executionGroups.get(record.executionId);
    if (group) {
      group.push(record);
    } else {
      executionGroups.set(record.executionId, [record]);
    }
  });

  return Array.from(executionGroups.values())
    .reverse()
    .flat();
}

export function getLatestExecutionEdgeScrollTop(scrollHeight: number, order: ExecutionConsoleOrder) {
  return order === 'newest-first' ? 0 : scrollHeight;
}
