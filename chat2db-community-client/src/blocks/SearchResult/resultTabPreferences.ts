import type { IManageResultData } from '@/typings';

export type ResultTabOrder = 'oldest-first' | 'newest-first';

interface ResultTabPreferenceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

type ResultTabKeepHistoryListener = (storageKey: string, keepHistory: boolean) => void;
type ResultTabOrderListener = (storageKey: string, order: ResultTabOrder) => void;

export const DEFAULT_RESULT_TAB_KEEP_HISTORY = true;
export const DEFAULT_RESULT_TAB_ORDER: ResultTabOrder = 'oldest-first';

const keepHistoryListeners = new Set<ResultTabKeepHistoryListener>();
const orderListeners = new Set<ResultTabOrderListener>();

export function createResultTabKeepHistoryStorageKey(clientEdition: string, runtimeEnv: string) {
  return `chat2db.${clientEdition}.${runtimeEnv}.result-tabs.keep-history.v1`;
}

export function createResultTabOrderStorageKey(clientEdition: string, runtimeEnv: string) {
  return `chat2db.${clientEdition}.${runtimeEnv}.result-tabs.order.v1`;
}

export function getResultTabPreferenceStorage(): ResultTabPreferenceStorage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.localStorage;
  } catch {
    return undefined;
  }
}

export function readResultTabKeepHistory(
  storage: ResultTabPreferenceStorage | undefined,
  storageKey: string,
) {
  try {
    const storedValue = storage?.getItem(storageKey);
    if (storedValue === 'true') return true;
    if (storedValue === 'false') return false;
    return DEFAULT_RESULT_TAB_KEEP_HISTORY;
  } catch {
    return DEFAULT_RESULT_TAB_KEEP_HISTORY;
  }
}

export function persistResultTabKeepHistory(
  storage: ResultTabPreferenceStorage | undefined,
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

export function subscribeResultTabKeepHistory(listener: ResultTabKeepHistoryListener) {
  keepHistoryListeners.add(listener);
  return () => {
    keepHistoryListeners.delete(listener);
  };
}

export function readResultTabOrder(
  storage: ResultTabPreferenceStorage | undefined,
  storageKey: string,
): ResultTabOrder {
  try {
    const storedValue = storage?.getItem(storageKey);
    return storedValue === 'oldest-first' || storedValue === 'newest-first'
      ? storedValue
      : DEFAULT_RESULT_TAB_ORDER;
  } catch {
    return DEFAULT_RESULT_TAB_ORDER;
  }
}

export function persistResultTabOrder(
  storage: ResultTabPreferenceStorage | undefined,
  storageKey: string,
  order: ResultTabOrder,
) {
  try {
    storage?.setItem(storageKey, order);
  } catch {
    // Storage can be unavailable in restricted browser contexts.
  }
  orderListeners.forEach((listener) => listener(storageKey, order));
}

export function subscribeResultTabOrder(listener: ResultTabOrderListener) {
  orderListeners.add(listener);
  return () => {
    orderListeners.delete(listener);
  };
}

export function orderExecutionResultsByBatch(
  results: readonly IManageResultData[],
  order: ResultTabOrder,
): IManageResultData[] {
  if (order === 'oldest-first') {
    return [...results];
  }

  const executionGroups = new Map<number, IManageResultData[]>();
  const legacyResults: IManageResultData[] = [];
  results.forEach((result) => {
    const executionSequence = positiveInteger(result.extra?.executionSequence);
    if (executionSequence === undefined) {
      legacyResults.push(result);
      return;
    }

    const group = executionGroups.get(executionSequence);
    if (group) {
      group.push(result);
    } else {
      executionGroups.set(executionSequence, [result]);
    }
  });

  const orderedExecutionResults = Array.from(executionGroups.values())
    .reverse()
    .flat();
  return [...orderedExecutionResults, ...legacyResults];
}

function positiveInteger(value: unknown) {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? number : undefined;
}
