import {
  ABSTRACT_TAB_ID,
  CONSOLE_TAB_ID,
  MESSAGES_TAB_ID,
  getConsoleResultTabLabel,
  getInitialActiveTabId,
  getLatestExecutionSequence,
  getPreferredActiveTabId,
  pickPreferredChangedResult,
  reduceActiveTabSelection,
  resolveAvailableActiveTabId,
  resolveChangedResultsPreference,
  type BatchResultTabPreference,
} from './tabSelection';
import type { IManageResultData } from '@/typings';
import { sortExecutionResults } from '@/service/sqlExecutionStream';

function assertEqual(actual: unknown, expected: unknown, message: string) {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

function result(overrides: Partial<IManageResultData> = {}): IManageResultData {
  return {
    dataList: [],
    headerList: [],
    description: '',
    sql: '',
    originalSql: '',
    success: true,
    sqlType: 'SELECT' as any,
    refreshTargets: [],
    pageNo: 1,
    pageSize: 200,
    fuzzyTotal: '0',
    hasNextPage: false,
    ...overrides,
  };
}

const tableResult = result({
  uuid: 'table-result',
  headerList: [{ name: '#' }, { name: 'id' }] as any,
});

let activeTabId = getPreferredActiveTabId(tableResult, false);
activeTabId = resolveAvailableActiveTabId(activeTabId, [ABSTRACT_TAB_ID, 'table-result']);

assertEqual(
  activeTabId,
  'table-result',
  'view-table result selection survives fallback validation in the same React update',
);
assertEqual(getPreferredActiveTabId(tableResult, true), 'table-result', 'console queries open a new tabular result');
assertEqual(
  getPreferredActiveTabId(tableResult, true, true),
  CONSOLE_TAB_ID,
  'an explicit failed or cancelled execution outcome keeps Output active even when an old table result is restored',
);
assertEqual(
  getPreferredActiveTabId(result({ uuid: 'command-result' }), true),
  CONSOLE_TAB_ID,
  'successful console commands without a table result open Output',
);
assertEqual(
  getPreferredActiveTabId(result({ uuid: 'message-only', extra: { messageOnly: true } }), false),
  MESSAGES_TAB_ID,
  'message-only legacy results open the messages tab',
);
assertEqual(
  getPreferredActiveTabId(result({ uuid: 'command-result' }), false),
  ABSTRACT_TAB_ID,
  'successful non-tabular legacy results open the summary',
);
assertEqual(
  getPreferredActiveTabId(result({ uuid: 'failed-result', success: false }), true),
  CONSOLE_TAB_ID,
  'console failures open the execution console',
);
assertEqual(
  getConsoleResultTabLabel('#3-1 select', false),
  'select',
  'a replacement batch hides both the batch and statement coordinate',
);
assertEqual(
  getConsoleResultTabLabel('#3-2 select', false),
  'select',
  'a replacement batch does not retain a statement sequence in later result labels',
);
assertEqual(
  getConsoleResultTabLabel('#3-1 Result 2 select', false),
  'Result 2 select',
  'a replacement batch preserves a multiple-result suffix after removing the execution coordinate',
);
assertEqual(
  getConsoleResultTabLabel('#3-1 select', true),
  '#3-1 select',
  'a retained batch keeps the complete batch and statement coordinate',
);
let batchedSelection = reduceActiveTabSelection(
  { activeTabId: CONSOLE_TAB_ID, followPreferredTabs: true },
  { type: 'prefer', tabId: 'batched-table-result' },
);
batchedSelection = reduceActiveTabSelection(batchedSelection, {
  type: 'tabsChanged',
  availableTabIds: [CONSOLE_TAB_ID],
});
assertEqual(
  batchedSelection.activeTabId,
  CONSOLE_TAB_ID,
  'a preferred result waits while the rendered tab list still contains only Output',
);
assertEqual(
  batchedSelection.pendingPreferredTabId,
  'batched-table-result',
  'the preferred result remains pending until its tab is rendered',
);
batchedSelection = reduceActiveTabSelection(batchedSelection, {
  type: 'tabsChanged',
  availableTabIds: [CONSOLE_TAB_ID, 'batched-table-result'],
});
assertEqual(
  batchedSelection.activeTabId,
  'batched-table-result',
  'a pending result becomes active when the result tab reaches the rendered tab list',
);
assertEqual(
  batchedSelection.pendingPreferredTabId,
  undefined,
  'the pending preference is consumed after the result tab becomes active',
);
batchedSelection = reduceActiveTabSelection(
  reduceActiveTabSelection(batchedSelection, { type: 'prefer', tabId: 'next-result' }),
  { type: 'activate', tabId: CONSOLE_TAB_ID },
);
assertEqual(
  batchedSelection.activeTabId,
  CONSOLE_TAB_ID,
  'failed and cancelled terminal states activate Output',
);
assertEqual(
  batchedSelection.pendingPreferredTabId,
  undefined,
  'failed and cancelled terminal states discard a pending result preference',
);

let manualSelection = reduceActiveTabSelection(
  { activeTabId: 'first-result', followPreferredTabs: true },
  { type: 'activateByUser', tabId: CONSOLE_TAB_ID },
);
manualSelection = reduceActiveTabSelection(manualSelection, { type: 'prefer', tabId: 'later-result' });
manualSelection = reduceActiveTabSelection(manualSelection, {
  type: 'activateAutomatically',
  tabId: 'later-result',
});
manualSelection = reduceActiveTabSelection(manualSelection, {
  type: 'tabsChanged',
  availableTabIds: [CONSOLE_TAB_ID, 'first-result', 'later-result'],
});
assertEqual(
  manualSelection.activeTabId,
  CONSOLE_TAB_ID,
  'later streamed results do not steal focus after the user selects Output',
);
assertEqual(
  manualSelection.pendingPreferredTabId,
  undefined,
  'manual selection prevents future automatic preferences from becoming pending',
);

manualSelection = reduceActiveTabSelection(manualSelection, { type: 'resetPreference' });
manualSelection = reduceActiveTabSelection(manualSelection, { type: 'prefer', tabId: 'result-after-clear' });
assertEqual(
  manualSelection.activeTabId,
  CONSOLE_TAB_ID,
  'clearing Output does not restore automatic focus within the current batch',
);

manualSelection = reduceActiveTabSelection(manualSelection, {
  type: 'startAutoFollow',
  tabId: CONSOLE_TAB_ID,
});
manualSelection = reduceActiveTabSelection(manualSelection, { type: 'prefer', tabId: 'next-batch-result' });
manualSelection = reduceActiveTabSelection(manualSelection, {
  type: 'tabsChanged',
  availableTabIds: [CONSOLE_TAB_ID, 'next-batch-result'],
});
assertEqual(
  manualSelection.activeTabId,
  'next-batch-result',
  'starting a new execution batch restores automatic result focus',
);
assertEqual(
  resolveAvailableActiveTabId('table-result', [ABSTRACT_TAB_ID, 'table-result']),
  'table-result',
  'fallback validation preserves a result selected earlier in the same React update',
);
assertEqual(
  resolveAvailableActiveTabId('closed-result', [ABSTRACT_TAB_ID, 'next-result']),
  ABSTRACT_TAB_ID,
  'closing the active result selects the first available tab',
);
assertEqual(resolveAvailableActiveTabId('closed-result', []), '', 'removing all tabs clears the active tab');

const sortedWebBatchResults = sortExecutionResults([
  result({
    uuid: 'select-1',
    statementSequence: 1,
    headerList: [{ name: '#' }, { name: 'id' }] as any,
    extra: { executionSequence: 1, resultSequence: 1 },
  }),
  result({
    uuid: 'select-2',
    statementSequence: 2,
    headerList: [{ name: '#' }, { name: 'id' }] as any,
    extra: { executionSequence: 1, resultSequence: 1 },
  }),
  result({
    uuid: 'select-4',
    statementSequence: 4,
    headerList: [{ name: '#' }, { name: 'id' }] as any,
    extra: { executionSequence: 1, resultSequence: 1 },
  }),
  result({
    uuid: 'use-3',
    statementSequence: 3,
    extra: { executionSequence: 1, resultSequence: 3 },
  }),
]);
assertEqual(
  sortedWebBatchResults.map((item) => item.uuid).join(','),
  'select-1,select-2,use-3,select-4',
  'web batch results use the backend statement sequence when stream metadata is absent',
);
assertEqual(
  getPreferredActiveTabId(sortedWebBatchResults[sortedWebBatchResults.length - 1], true),
  'select-4',
  'a web batch ending in SELECT opens its final result tab after a USE statement',
);

const selectResult = result({
  uuid: 'select-result',
  headerList: [{ name: '#' }, { name: 'x' }] as any,
  extra: { executionSequence: 1 },
});
const updateResult = result({ uuid: 'update-result', sqlType: 'UPDATE' as any, extra: { executionSequence: 1 } });
const useResult = result({ uuid: 'use-result', sqlType: 'USE' as any, extra: { executionSequence: 1 } });
const failedResult = result({ uuid: 'failed-result', success: false, extra: { executionSequence: 1 } });
const printResult = result({
  uuid: 'print-result',
  sqlType: 'UNKNOWN' as any,
  extra: { executionSequence: 1, messages: [{ level: 'INFO', message: 'done' }] } as any,
});
const selectTabInBatch: BatchResultTabPreference = { executionKey: '1', tabId: 'select-result' };
const preferConsole = (changedResults: IManageResultData[], batchResultTab?: BatchResultTabPreference) =>
  resolveChangedResultsPreference(changedResults, true, false, batchResultTab, [selectResult, ...changedResults]);

assertEqual(
  preferConsole([selectResult, updateResult]).tabId,
  'select-result',
  'a console batch ending with a statement without a table still opens the earlier result',
);
assertEqual(
  preferConsole([selectResult, updateResult]).batchResultTab?.tabId,
  'select-result',
  'opening a result tab is remembered for the rest of the batch',
);
assertEqual(
  resolveChangedResultsPreference([useResult], true, false, undefined, [useResult]).tabId,
  CONSOLE_TAB_ID,
  'a console batch without any table result opens Output',
);
assertEqual(
  preferConsole([selectResult, failedResult]).tabId,
  CONSOLE_TAB_ID,
  'a failed statement after the last table result opens Output',
);
assertEqual(
  preferConsole([failedResult, selectResult]).tabId,
  'select-result',
  'a batched failure before the last table result still opens that result',
);
assertEqual(preferConsole([failedResult]).tabId, CONSOLE_TAB_ID, 'a streamed failure opens Output');
assertEqual(
  preferConsole([selectResult]).tabId,
  'select-result',
  'a streamed table result after a failure opens that result, as in the batched order',
);
assertEqual(
  preferConsole([updateResult], selectTabInBatch).tabId,
  undefined,
  'a streamed result without a table does not take focus back from the batch result tab',
);
assertEqual(
  preferConsole([failedResult], selectTabInBatch).tabId,
  CONSOLE_TAB_ID,
  'a streamed failure after the table result opens Output, as in the batched order',
);
assertEqual(
  resolveChangedResultsPreference([updateResult], true, true, selectTabInBatch, [selectResult, updateResult]).tabId,
  CONSOLE_TAB_ID,
  'an explicit failed or cancelled outcome keeps Output active',
);
assertEqual(
  preferConsole([selectResult, printResult]).tabId,
  'select-result',
  'a batched statement with messages, such as a NOTICE, after the table result keeps that result',
);
assertEqual(
  preferConsole([printResult], selectTabInBatch).tabId,
  undefined,
  'a streamed statement with messages after the table result keeps that result, as in the batched order',
);
assertEqual(
  preferConsole([printResult], selectTabInBatch).batchResultTab?.tabId,
  'select-result',
  'a streamed statement with messages keeps the batch result tab remembered',
);
assertEqual(
  resolveChangedResultsPreference([printResult], true, false, undefined, [printResult]).tabId,
  CONSOLE_TAB_ID,
  'a batched result with messages and no table opens Output',
);
assertEqual(
  resolveChangedResultsPreference([printResult, useResult], true, false, undefined, [printResult, useResult]).tabId,
  CONSOLE_TAB_ID,
  'a batch of message results without any table opens Output',
);
assertEqual(
  preferConsole([printResult, selectResult]).tabId,
  'select-result',
  'a statement with messages before the table result does not hide that result',
);

const nextExecutionUpdate = result({
  uuid: 'next-update-result',
  sqlType: 'UPDATE' as any,
  extra: { executionSequence: 2 },
});
const nextExecutionPreference = preferConsole([nextExecutionUpdate], selectTabInBatch);
assertEqual(
  nextExecutionPreference.tabId,
  CONSOLE_TAB_ID,
  'a result of a new execution is not held back by the result tab of the previous one',
);
assertEqual(
  nextExecutionPreference.batchResultTab,
  undefined,
  'a new execution forgets the result tab of the previous one',
);
const closedTabPreference = resolveChangedResultsPreference([updateResult], true, false, selectTabInBatch, [
  updateResult,
]);
assertEqual(
  closedTabPreference.tabId,
  CONSOLE_TAB_ID,
  'a later statement without a table opens Output once the batch result tab was closed',
);
assertEqual(closedTabPreference.batchResultTab, undefined, 'a closed batch result tab is forgotten');
assertEqual(
  preferConsole([], selectTabInBatch).batchResultTab,
  selectTabInBatch,
  'no changed result keeps the open batch result tab',
);
assertEqual(preferConsole([]).tabId, undefined, 'no changed result keeps the selection');
assertEqual(
  pickPreferredChangedResult([selectResult, updateResult], false),
  updateResult,
  'outside the console the latest changed result is still used',
);
assertEqual(
  resolveChangedResultsPreference([selectResult, updateResult], false, false, selectTabInBatch, []).batchResultTab,
  undefined,
  'outside the console no batch result tab is tracked',
);
const legacySelectResult = result({ uuid: 'legacy-select-result', headerList: [{ name: '#' }, { name: 'x' }] as any });
const legacyUpdateResult = result({ uuid: 'legacy-update-result', sqlType: 'UPDATE' as any });
const legacyPreference = resolveChangedResultsPreference([legacySelectResult], true, false, undefined, [
  legacySelectResult,
]);
assertEqual(
  legacyPreference.batchResultTab?.tabId,
  'legacy-select-result',
  'a result without an execution key still remembers its tab',
);
assertEqual(
  legacyPreference.batchResultTab?.executionKey,
  undefined,
  'a result without an execution key is remembered without a key',
);
assertEqual(
  resolveChangedResultsPreference([legacyUpdateResult], true, false, legacyPreference.batchResultTab, [
    legacySelectResult,
    legacyUpdateResult,
  ]).tabId,
  undefined,
  'results without an execution key are treated as one execution',
);

const selectResultWithoutTable = result({ uuid: 'select-result', extra: { executionSequence: 1 } });
const noLongerTabularPreference = resolveChangedResultsPreference([updateResult], true, false, selectTabInBatch, [
  selectResultWithoutTable,
  updateResult,
]);
assertEqual(
  noLongerTabularPreference.tabId,
  CONSOLE_TAB_ID,
  'a later statement without a table opens Output once the remembered result no longer has a table',
);
assertEqual(
  noLongerTabularPreference.batchResultTab,
  undefined,
  'a remembered result that no longer has a table is forgotten',
);

const newerSelectResult = result({
  uuid: 'newer-select-result',
  headerList: [{ name: '#' }, { name: 'x' }] as any,
  extra: { executionSequence: 2 },
});
const newerPrintResult = result({
  uuid: 'newer-print-result',
  sqlType: 'UNKNOWN' as any,
  extra: { executionSequence: 2, messages: [{ level: 'INFO', message: 'done' }] } as any,
});
const newerSelectTab: BatchResultTabPreference = { executionKey: '2', tabId: 'newer-select-result' };
const bothExecutions = [selectResult, updateResult, newerSelectResult, newerPrintResult];
assertEqual(getLatestExecutionSequence(bothExecutions), 2, 'the latest execution is the highest execution sequence');
assertEqual(
  getLatestExecutionSequence([legacySelectResult, legacyUpdateResult]),
  undefined,
  'results without an execution sequence have no latest execution',
);
const lateOlderTablePreference = resolveChangedResultsPreference(
  [selectResult],
  true,
  false,
  newerSelectTab,
  bothExecutions,
  2,
);
assertEqual(
  lateOlderTablePreference.batchResultTab,
  newerSelectTab,
  'a late table result of an older execution does not replace the result tab of the latest execution',
);
const lateOlderUpdatePreference = resolveChangedResultsPreference(
  [updateResult],
  true,
  false,
  newerSelectTab,
  bothExecutions,
  2,
);
assertEqual(
  lateOlderUpdatePreference.batchResultTab,
  newerSelectTab,
  'a late result without a table of an older execution does not clear the result tab of the latest execution',
);
assertEqual(
  lateOlderUpdatePreference.tabId,
  undefined,
  'a late result without a table of an older execution does not take focus from the latest result tab',
);
assertEqual(
  resolveChangedResultsPreference(
    [newerPrintResult],
    true,
    false,
    lateOlderTablePreference.batchResultTab,
    bothExecutions,
    2,
  ).tabId,
  undefined,
  'messages of the latest execution keep its result tab after a late result of an older execution',
);
assertEqual(
  resolveChangedResultsPreference([newerSelectResult], true, false, selectTabInBatch, bothExecutions, 2).batchResultTab
    ?.tabId,
  'newer-select-result',
  'a table result of the latest execution replaces the remembered result tab',
);

assertEqual(
  getInitialActiveTabId([selectResult, updateResult], true),
  'select-result',
  'a console mounted with a batch ending in a statement without a table starts on the table result',
);
assertEqual(
  getInitialActiveTabId([useResult], true),
  CONSOLE_TAB_ID,
  'a console mounted with a batch without any table result starts on Output',
);
assertEqual(getInitialActiveTabId([], true), CONSOLE_TAB_ID, 'an empty console starts on Output');
assertEqual(getInitialActiveTabId([], false), '', 'an empty legacy result view starts without a tab');
assertEqual(
  getInitialActiveTabId([selectResult, updateResult], false),
  ABSTRACT_TAB_ID,
  'outside the console the latest result still decides the initial tab',
);

let streamedSelection = reduceActiveTabSelection(
  { activeTabId: CONSOLE_TAB_ID, followPreferredTabs: true },
  { type: 'prefer', tabId: preferConsole([selectResult]).tabId! },
);
streamedSelection = reduceActiveTabSelection(streamedSelection, {
  type: 'tabsChanged',
  availableTabIds: [CONSOLE_TAB_ID, 'select-result'],
});
const streamedUpdatePreference = preferConsole([updateResult], preferConsole([selectResult]).batchResultTab).tabId;
if (streamedUpdatePreference) {
  streamedSelection = reduceActiveTabSelection(streamedSelection, { type: 'prefer', tabId: streamedUpdatePreference });
}
streamedSelection = reduceActiveTabSelection(streamedSelection, {
  type: 'tabsChanged',
  availableTabIds: [CONSOLE_TAB_ID, 'select-result'],
});
assertEqual(
  streamedSelection.activeTabId,
  'select-result',
  'the batch result tab stays active after a later statement without a table',
);

console.log('SearchResult tab selection tests passed');
