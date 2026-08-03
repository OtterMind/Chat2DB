import assert from 'node:assert/strict';
import type { IManageResultData } from '@/typings';
import {
  appendCompletedQueryResult,
  clearClosedSqlExecutionResults,
  cancelSqlExecutionWithReconciliation,
  isSqlExecutionResultClosed,
  markSqlExecutionResultsClosed,
  mergeRows,
  type ClosedSqlExecutionResults,
} from './sqlExecutionStream';

function chunk(rows: unknown[][]): IManageResultData {
  return {
    uuid: 'execution-1:1:1',
    dataList: rows as any,
    headerList: [{ name: '#' }, { name: 'value' }] as any,
    description: '',
    sql: 'select 1',
    originalSql: 'select 1',
    success: true,
    duration: 0,
    sqlType: 'SELECT' as any,
    refreshTargets: [],
    extra: {
      executionId: 'execution-1',
      executionSequence: 1,
      statementSequence: 1,
      resultSequence: 1,
      resultKey: 'execution-1:1:1',
    },
    pageNo: 1,
    pageSize: 200,
    fuzzyTotal: '0',
    hasNextPage: false,
  };
}

const restoredAfterClear = mergeRows([], chunk([['first']]));
assert.equal(restoredAfterClear.length, 1, 'a row event recreates a result cleared while execution is running');
assert.deepEqual(restoredAfterClear[0].dataList, [['first']]);

const appendedRows = mergeRows(restoredAfterClear, chunk([['second']]));
assert.equal(appendedRows.length, 1, 'later chunks stay in the same execution result');
assert.deepEqual(appendedRows[0].dataList, [['first'], ['second']]);

const completedQuery = chunk([['finished']]);
const ignoredRowsEvent = appendCompletedQueryResult([], {
  executionId: 'execution-1',
  eventType: 'rows',
  message: chunk([['streamed']]),
});
assert.deepEqual(ignoredRowsEvent, [], 'row chunks do not trigger completed-result callbacks');

const completedResults = appendCompletedQueryResult(ignoredRowsEvent, {
  executionId: 'execution-1',
  eventType: 'resultFinished',
  message: completedQuery,
});
assert.deepEqual(completedResults, [completedQuery], 'a completed query result is retained for callback consumers');

const emptyCompletedQuery = chunk([]);
const completedResultsWithEmptyQuery = appendCompletedQueryResult(completedResults, {
  executionId: 'execution-1',
  eventType: 'resultFinished',
  message: emptyCompletedQuery,
});
assert.deepEqual(
  completedResultsWithEmptyQuery,
  [completedQuery, emptyCompletedQuery],
  'an empty query result still retains its headers for callback consumers',
);

const ignoredUpdate = appendCompletedQueryResult(completedResultsWithEmptyQuery, {
  executionId: 'execution-1',
  eventType: 'updateCount',
  message: { ...chunk([]), dataList: null },
});
assert.equal(ignoredUpdate, completedResultsWithEmptyQuery, 'non-query results do not enter query callbacks');

const closedResults: ClosedSqlExecutionResults = new Map();
markSqlExecutionResultsClosed(closedResults, [
  { executionId: 'execution-1', resultKey: 'execution-1:1:1' },
]);
assert.equal(
  isSqlExecutionResultClosed(closedResults, 'execution-1', 'execution-1:1:1'),
  true,
  'a user-closed streaming result remains closed for later row and finished events',
);
assert.equal(
  isSqlExecutionResultClosed(closedResults, 'execution-1', 'execution-1:1:2'),
  false,
  'closing one result set does not suppress another result set in the same execution',
);
assert.equal(
  isSqlExecutionResultClosed(closedResults, 'execution-2', 'execution-1:1:1'),
  false,
  'a later execution is not suppressed even when its SQL or result coordinate is repeated',
);

clearClosedSqlExecutionResults(closedResults);
assert.equal(
  isSqlExecutionResultClosed(closedResults, 'execution-1', 'execution-1:1:1'),
  false,
  'clearing Output allows an active stream to recreate its result',
);

markSqlExecutionResultsClosed(closedResults, [
  { executionId: 'execution-1', resultKey: 'execution-1:1:1' },
  { executionId: 'execution-2', resultKey: 'execution-2:1:1' },
]);
clearClosedSqlExecutionResults(closedResults, 'execution-1');
assert.equal(
  isSqlExecutionResultClosed(closedResults, 'execution-1', 'execution-1:1:1'),
  false,
  'terminal cleanup releases closed-result state for the completed execution',
);
assert.equal(
  isSqlExecutionResultClosed(closedResults, 'execution-2', 'execution-2:1:1'),
  true,
  'terminal cleanup preserves closed-result state for another running execution',
);

async function testCancellationReconciliation() {
  let missingCount = 0;
  const errors: unknown[] = [];
  const handlers = {
    onExecutionMissing: () => {
      missingCount += 1;
    },
    onError: (error: unknown) => {
      errors.push(error);
    },
  };

  await cancelSqlExecutionWithReconciliation('execution-running', handlers, async () => true);
  assert.equal(missingCount, 0);
  assert.deepEqual(errors, []);

  await cancelSqlExecutionWithReconciliation('execution-finished', handlers, async () => false);
  assert.equal(missingCount, 1, 'a missing server job triggers local terminal reconciliation');

  const bridgeError = new Error('bridge unavailable');
  await cancelSqlExecutionWithReconciliation('execution-unknown', handlers, async () => {
    throw bridgeError;
  });
  assert.deepEqual(errors, [bridgeError], 'bridge failures are handled instead of becoming unhandled rejections');
}

void testCancellationReconciliation().then(() => {
  console.log('SQL execution stream tests passed');
});
