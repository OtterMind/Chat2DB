import assert from 'node:assert/strict';
import test from 'node:test';
import { DatabaseTypeCode } from '@/constants/common';
import type { IManageResultData } from '@/typings';
import { processResultDataList } from '@/utils/resultData';
import { composeResultQuery } from '../ScreeningResult/queryComposer';
import {
  createViewTablePagingState,
  reduceViewTablePagingEvent,
  replaceViewTableResult,
  type ViewTablePagingTransition,
} from '@/hooks/viewTablePagingModel';
import { cancelSqlExecutionWithReconciliation, type SqlExecutionEvent } from '@/service/sqlExecutionStream';
import {
  beginSqlExecutionRequest,
  createSqlExecutionRequestTracker,
  finishSqlExecutionRequest,
  requestSqlExecutionCancellation,
  setSqlExecutionRequestId,
} from '@/service/sqlExecutionRequestTracker';
import { buildResultPageExecuteParams, resolveResultPaging } from './pagination';

const BASE_SQL = 'SELECT * FROM app.test_1m_records';

function result(overrides: Partial<IManageResultData> = {}): IManageResultData {
  return {
    uuid: 'backend-result',
    dataList: [],
    headerList: [{ name: '#' }, { name: 'id' }, { name: 'status' }] as any,
    description: '',
    sql: BASE_SQL,
    originalSql: BASE_SQL,
    success: true,
    sqlType: 'SELECT' as any,
    refreshTargets: [],
    resultSetId: 1,
    pageNo: 1,
    pageSize: 1000,
    fuzzyTotal: '1000+',
    hasNextPage: true,
    ...overrides,
  };
}

function event(
  eventType: SqlExecutionEvent['eventType'],
  message: IManageResultData,
): SqlExecutionEvent<IManageResultData> {
  return {
    executionId: 'execution-50000',
    eventType,
    message,
    statementSequence: 1,
    resultSequence: 1,
    resultKey: 'execution-50000:1:1',
  };
}

test('table browse flows from the initial response through filtered 50000-row streaming completion', () => {
  const initialRequest = {
    dataSourceId: 42,
    databaseName: 'app',
    databaseType: DatabaseTypeCode.MYSQL,
    tableName: 'test_1m_records',
    pageNo: 1,
    pageSize: 1000,
  };
  const [initialResult] = processResultDataList(
    [result({ originalSql: '', sql: BASE_SQL })],
    initialRequest,
  );
  const initialUuid = initialResult.uuid;

  assert.equal(initialResult.originalSql, BASE_SQL, 'table browse falls back to the backend sql as its base query');
  assert.equal(initialResult.executeSqlParams?.sql, BASE_SQL, 'subsequent actions retain the normalized base query');

  const filteredSql = composeResultQuery({
    databaseType: DatabaseTypeCode.MYSQL,
    filterValue: "status = 'ACTIVE'",
    orderByValue: 'id DESC',
    originalSql: initialResult.originalSql,
  });
  const paging = resolveResultPaging(initialResult.executeSqlParams, { pageNo: 1, pageSize: 50_000 });
  const executeParams = buildResultPageExecuteParams(initialResult.executeSqlParams!, paging, filteredSql);

  assert.deepEqual(
    executeParams,
    {
      ...initialResult.executeSqlParams,
      pageNo: 1,
      pageSize: 50_000,
      sql: `${BASE_SQL} WHERE status = 'ACTIVE' ORDER BY id DESC`,
    },
    'the paging request carries datasource identity, composed SQL, and the selected page size together',
  );

  const requestSequence = 7;
  let transition: ViewTablePagingTransition = {
    state: createViewTablePagingState(requestSequence, executeParams),
    completedResult: undefined as IManageResultData | undefined,
  };

  const staleTransition = reduceViewTablePagingEvent(
    transition.state,
    event('rows', result({ dataList: [[{ value: 'stale' }]] as any })),
    requestSequence - 1,
  );
  assert.equal(staleTransition.state, transition.state, 'a stale execution cannot mutate the active result');
  assert.equal(staleTransition.completedResult, undefined);

  transition = reduceViewTablePagingEvent(
    transition.state,
    event('resultStarted', result({ dataList: [], pageSize: 50_000, originalSql: filteredSql })),
    requestSequence,
  );
  assert.equal(transition.completedResult, undefined, 'result metadata does not replace the visible table');

  transition = reduceViewTablePagingEvent(
    transition.state,
    event('rows', result({ dataList: [[{ value: 'row-1' }]] as any, originalSql: filteredSql })),
    requestSequence,
  );
  assert.equal(transition.completedResult, undefined, 'the first row chunk remains buffered');

  transition = reduceViewTablePagingEvent(
    transition.state,
    event('rows', result({ dataList: [[{ value: 'row-2' }]] as any, originalSql: filteredSql })),
    requestSequence,
  );
  assert.equal(transition.completedResult, undefined, 'later chunks remain buffered without remounting editors');

  transition = reduceViewTablePagingEvent(
    transition.state,
    event(
      'resultFinished',
      result({
        dataList: [],
        pageSize: 50_000,
        fuzzyTotal: '50000+',
        hasNextPage: true,
        originalSql: filteredSql,
      }),
    ),
    requestSequence,
  );

  assert.deepEqual(
    transition.completedResult?.dataList,
    [[{ value: 'row-1' }], [{ value: 'row-2' }]],
    'completion preserves every buffered row chunk',
  );
  assert.equal(transition.completedResult?.pageSize, 50_000);
  assert.equal(transition.completedResult?.executeSqlParams?.sql, filteredSql);

  const replacedResults = replaceViewTableResult([initialResult], transition.completedResult!);
  assert.equal(replacedResults[0].uuid, initialUuid, 'completion preserves the mounted result identity');
  assert.equal(
    composeResultQuery({
      databaseType: DatabaseTypeCode.MYSQL,
      filterValue: "status = 'ACTIVE'",
      orderByValue: 'id DESC',
      originalSql: initialResult.originalSql,
    }),
    filteredSql,
    'later searches continue composing from the stable base query instead of nesting prior filters',
  );
});

test('table browse SQL normalization follows originalSql, sql, then request SQL', () => {
  const request = { sql: 'SELECT * FROM request_fallback', dataSourceId: 42 };
  assert.equal(processResultDataList([result({ originalSql: 'SELECT 1', sql: 'SELECT 2' })], request)[0].originalSql, 'SELECT 1');
  assert.equal(processResultDataList([result({ originalSql: '', sql: 'SELECT 2' })], request)[0].originalSql, 'SELECT 2');
  assert.equal(processResultDataList([result({ originalSql: '', sql: '' })], request)[0].originalSql, request.sql);
});

test('a cancelled table browse never publishes its buffered partial rows', () => {
  const params = { sql: BASE_SQL, dataSourceId: 42, pageNo: 1, pageSize: 50_000 };
  const requestSequence = 8;
  let transition = reduceViewTablePagingEvent(
    createViewTablePagingState(requestSequence, params),
    event('rows', result({ dataList: [[{ value: 'partial' }]] as any })),
    requestSequence,
  );
  assert.equal(transition.completedResult, undefined);

  transition = reduceViewTablePagingEvent(
    transition.state,
    event('cancelled', result()),
    requestSequence,
  );
  assert.equal(transition.completedResult, undefined, 'cancellation leaves the visible completed result untouched');
});

test('table browse cancellation targets the active JCEF execution and releases the request', async () => {
  const tracker = createSqlExecutionRequestTracker();
  const requestSequence = beginSqlExecutionRequest(tracker)!;
  assert.equal(setSqlExecutionRequestId(tracker, requestSequence, 'execution-50000'), true);
  const executionId = requestSqlExecutionCancellation(tracker);
  const cancelledExecutions: string[] = [];

  await cancelSqlExecutionWithReconciliation(
    executionId!,
    {
      onExecutionMissing: () => assert.fail('the active execution should exist'),
      onError: (error) => assert.fail(String(error)),
    },
    async (targetExecutionId) => {
      cancelledExecutions.push(targetExecutionId);
      return true;
    },
  );

  assert.deepEqual(cancelledExecutions, ['execution-50000']);
  assert.equal(finishSqlExecutionRequest(tracker, requestSequence), true);
  assert.equal(requestSqlExecutionCancellation(tracker), undefined, 'terminal cleanup removes the cancellation target');
});
