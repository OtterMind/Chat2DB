import { IManageResultData } from '@/typings';

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
