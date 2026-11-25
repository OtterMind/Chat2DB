import { IManageResultData } from '@/typings';
import { getResultExecutionSequence } from '@/service/sqlExecutionBatch';

export const CONSOLE_TAB_ID = 'execution-console';
export const ABSTRACT_TAB_ID = 'abstract';
export const MESSAGES_TAB_ID = 'messages';

export interface ActiveTabSelectionState {
  activeTabId: string;
  pendingPreferredTabId?: string;
  followPreferredTabs: boolean;
}

export type ActiveTabSelectionAction =
  | { type: 'prefer'; tabId: string }
  | { type: 'activate'; tabId: string }
  | { type: 'activateAutomatically'; tabId: string }
  | { type: 'activateByUser'; tabId: string }
  | { type: 'startAutoFollow'; tabId: string }
  | { type: 'resetPreference' }
  | { type: 'tabsChanged'; availableTabIds: string[] };

export function getResultIdentity(item: IManageResultData) {
  return item.uuid || item.extra?.resultKey || item.extra?.historyKey;
}

export function hasTabularResult(item: IManageResultData) {
  return (item.headerList?.length || 0) > 1;
}

export function hasLegacyResultTab(item: IManageResultData) {
  return hasTabularResult(item) || !item.success;
}

export function getConsoleResultTabLabel(displayName: string, showCoordinates: boolean) {
  if (showCoordinates) {
    return displayName;
  }
  return displayName.replace(/^#\d+-\d+\s*/, '');
}

export function shouldOpenLegacyMessagesTab(item: IManageResultData) {
  return item.extra?.messageOnly || (!!item.extra?.messages?.length && !hasLegacyResultTab(item));
}

export function getPreferredActiveTabId(
  item: IManageResultData | undefined,
  consoleMode: boolean,
  forceOutputTab = false,
) {
  if (consoleMode && forceOutputTab) {
    return CONSOLE_TAB_ID;
  }
  if (!item) {
    return consoleMode ? CONSOLE_TAB_ID : '';
  }
  if (!consoleMode && shouldOpenLegacyMessagesTab(item)) {
    return MESSAGES_TAB_ID;
  }
  if (consoleMode && !item.success) {
    return CONSOLE_TAB_ID;
  }
  if (consoleMode ? hasTabularResult(item) : hasLegacyResultTab(item)) {
    return item.uuid || (consoleMode ? CONSOLE_TAB_ID : ABSTRACT_TAB_ID);
  }
  return consoleMode ? CONSOLE_TAB_ID : ABSTRACT_TAB_ID;
}

export interface BatchResultTabPreference {
  executionKey?: string;
  tabId: string;
}

export interface ChangedResultsPreference {
  tabId?: string;
  batchResultTab?: BatchResultTabPreference;
}

function getResultExecutionKey(item: IManageResultData) {
  const executionKey = item.extra?.executionSequence ?? item.extra?.executionId;
  return executionKey === undefined || executionKey === null ? undefined : String(executionKey);
}

// With the Output tab enabled, a batch such as `SELECT ...; UPDATE ...;` should still open the SELECT result. The last
// result that has a table or failed decides, so the batch picks the same tab whether its results arrive together or
// one by one. Messages such as PRINT, DBMS_OUTPUT or a PostgreSQL NOTICE do not decide the tab: they stay in the Output
// tab, which the user can still open.
export function pickPreferredChangedResult(changedResults: IManageResultData[], consoleMode: boolean) {
  if (!consoleMode) {
    return changedResults[changedResults.length - 1];
  }
  for (let index = changedResults.length - 1; index >= 0; index -= 1) {
    const item = changedResults[index];
    if (!item.success || hasTabularResult(item)) {
      return item;
    }
  }
  return changedResults[changedResults.length - 1];
}

export function getLatestExecutionSequence(results: IManageResultData[]) {
  let latestExecutionSequence: number | undefined;
  results.forEach((item) => {
    const executionSequence = getResultExecutionSequence(item);
    if (executionSequence !== undefined && (latestExecutionSequence ?? 0) < executionSequence) {
      latestExecutionSequence = executionSequence;
    }
  });
  return latestExecutionSequence;
}

// The first render follows the same rule as a batch whose results arrive together.
export function getInitialActiveTabId(results: IManageResultData[], consoleMode: boolean) {
  return getPreferredActiveTabId(pickPreferredChangedResult(results, consoleMode), consoleMode);
}

export function resolveChangedResultsPreference(
  changedResults: IManageResultData[],
  consoleMode: boolean,
  forceOutputTab: boolean,
  batchResultTab: BatchResultTabPreference | undefined,
  availableResults: IManageResultData[],
  latestExecutionSequence?: number,
): ChangedResultsPreference {
  const item = pickPreferredChangedResult(changedResults, consoleMode);
  if (!consoleMode) {
    return { tabId: item ? getPreferredActiveTabId(item, consoleMode, forceOutputTab) : undefined };
  }
  const openBatchResultTab =
    batchResultTab &&
    availableResults.some((result) => result.uuid === batchResultTab.tabId && hasTabularResult(result))
      ? batchResultTab
      : undefined;
  if (!item) {
    return { batchResultTab: openBatchResultTab };
  }
  const executionKey = getResultExecutionKey(item);
  // A late result of an older execution must not replace or clear the result tab of the latest one, otherwise the
  // next message of the latest execution would move focus back to Output.
  const itemExecutionSequence = getResultExecutionSequence(item);
  const fromOlderExecution =
    latestExecutionSequence !== undefined &&
    itemExecutionSequence !== undefined &&
    itemExecutionSequence < latestExecutionSequence;
  const batchExecutionKey = fromOlderExecution ? String(latestExecutionSequence) : executionKey;
  const currentBatchResultTab =
    openBatchResultTab?.executionKey === batchExecutionKey ? openBatchResultTab : undefined;
  const tabId = getPreferredActiveTabId(item, consoleMode, forceOutputTab);
  if (tabId !== CONSOLE_TAB_ID) {
    return { tabId, batchResultTab: fromOlderExecution ? currentBatchResultTab : { executionKey, tabId } };
  }
  // Results can stream in one by one. Once the batch has opened a result tab that is still open, a later successful
  // statement without a table must not move focus back to Output, even when it carries messages. The messages are
  // still written to the Output tab, so the user can open it when needed.
  if (currentBatchResultTab && item.success && !forceOutputTab) {
    return { batchResultTab: currentBatchResultTab };
  }
  return { tabId, batchResultTab: currentBatchResultTab };
}

export function resolveAvailableActiveTabId(
  currentActiveTabId: string,
  availableTabIds: string[],
  preferredActiveTabId?: string,
) {
  if (!availableTabIds.length) {
    return '';
  }
  if (preferredActiveTabId && availableTabIds.includes(preferredActiveTabId)) {
    return preferredActiveTabId;
  }
  if (currentActiveTabId && availableTabIds.includes(currentActiveTabId)) {
    return currentActiveTabId;
  }
  return availableTabIds[0];
}

export function reduceActiveTabSelection(
  state: ActiveTabSelectionState,
  action: ActiveTabSelectionAction,
): ActiveTabSelectionState {
  switch (action.type) {
    case 'prefer':
      if (!state.followPreferredTabs) {
        return state;
      }
      return { ...state, pendingPreferredTabId: action.tabId };
    case 'activate':
      return { ...state, activeTabId: action.tabId, pendingPreferredTabId: undefined };
    case 'activateAutomatically':
      return state.followPreferredTabs
        ? { ...state, activeTabId: action.tabId, pendingPreferredTabId: undefined }
        : state;
    case 'activateByUser':
      return { activeTabId: action.tabId, followPreferredTabs: false };
    case 'startAutoFollow':
      return { activeTabId: action.tabId, followPreferredTabs: true };
    case 'resetPreference':
      return state.pendingPreferredTabId === undefined ? state : { ...state, pendingPreferredTabId: undefined };
    case 'tabsChanged': {
      const activeTabId = resolveAvailableActiveTabId(
        state.activeTabId,
        action.availableTabIds,
        state.pendingPreferredTabId,
      );
      const pendingPreferredTabId =
        activeTabId === state.pendingPreferredTabId ? undefined : state.pendingPreferredTabId;
      if (activeTabId === state.activeTabId && pendingPreferredTabId === state.pendingPreferredTabId) {
        return state;
      }
      return { ...state, activeTabId, pendingPreferredTabId };
    }
    default:
      return state;
  }
}
