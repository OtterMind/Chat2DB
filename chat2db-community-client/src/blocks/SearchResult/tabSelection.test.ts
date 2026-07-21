import {
  ABSTRACT_TAB_ID,
  CONSOLE_TAB_ID,
  MESSAGES_TAB_ID,
  getPreferredActiveTabId,
  reduceActiveTabSelection,
  resolveAvailableActiveTabId,
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
    duration: 0,
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
let batchedSelection = reduceActiveTabSelection(
  { activeTabId: CONSOLE_TAB_ID },
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

console.log('SearchResult tab selection tests passed');
