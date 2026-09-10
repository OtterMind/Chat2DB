import assert from 'node:assert/strict';
import test from 'node:test';
import { DatabaseTypeCode } from '@/constants/common';
import type { IManageResultData } from '@/typings';
import { processResultDataList } from '@/utils/resultData';
import { composeResultQuery } from '../ScreeningResult/queryComposer';
import {
  createViewTablePagingState,
  normalizeViewTablePageResults,
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
  assert.equal(replacedResults[0].originalSql, BASE_SQL, 'completion preserves the original table query');
  const nextSortedSql = composeResultQuery({
    databaseType: DatabaseTypeCode.MYSQL,
    orderByValue: 'status ASC',
    originalSql: replacedResults[0].originalSql,
  });
  assert.equal(
    nextSortedSql,
    `${BASE_SQL} ORDER BY status ASC`,
    'later searches compose from the stable base query instead of nesting the previous sort',
  );
  assert.equal(nextSortedSql.match(/\bORDER\s+BY\b/gi)?.length, 1, 'consecutive grid sorts emit one ORDER BY clause');
});

test('table browse SQL normalization follows originalSql, sql, then request SQL', () => {
  const request = { sql: 'SELECT * FROM request_fallback', dataSourceId: 42 };
  assert.equal(processResultDataList([result({ originalSql: 'SELECT 1', sql: 'SELECT 2' })], request)[0].originalSql, 'SELECT 1');
  assert.equal(processResultDataList([result({ originalSql: '', sql: 'SELECT 2' })], request)[0].originalSql, 'SELECT 2');
  assert.equal(processResultDataList([result({ originalSql: '', sql: '' })], request)[0].originalSql, request.sql);
});

test('web table browse retains execution params across consecutive page requests', () => {
  const firstRequest = {
    sql: BASE_SQL,
    dataSourceId: 42,
    databaseName: 'app',
    pageNo: 2,
    pageSize: 5000,
    resultSetId: 1,
  };
  const [firstPage] = normalizeViewTablePageResults(
    [result({ originalSql: '', sql: '', pageNo: 2, pageSize: 5000 })],
    firstRequest,
  );

  assert.deepEqual(firstPage.executeSqlParams, { ...firstRequest, single: undefined });

  const secondRequest = buildResultPageExecuteParams(
    firstPage.executeSqlParams!,
    resolveResultPaging(firstPage.executeSqlParams, { pageNo: 3 }),
  );
  const [secondPage] = normalizeViewTablePageResults(
    [result({ originalSql: '', sql: '', pageNo: 3, pageSize: 5000 })],
    secondRequest,
  );

  assert.equal(secondRequest.pageNo, 3);
  assert.equal(secondPage.executeSqlParams?.sql, BASE_SQL);
  assert.equal(secondPage.executeSqlParams?.dataSourceId, 42);
  assert.equal(secondPage.executeSqlParams?.pageNo, 3);
  assert.equal(secondPage.executeSqlParams?.pageSize, 5000);
});

test('a failed table browse page preserves the confirmed result and reports the backend error', () => {
  const confirmedResult = result({
    uuid: 'confirmed-result',
    dataList: [[{ value: 'confirmed-row' }]] as any,
    pageNo: 1,
  });
  const params = { sql: BASE_SQL, dataSourceId: 42, pageNo: 2, pageSize: 1000 };
  const requestSequence = 8;

  const transition = reduceViewTablePagingEvent(
    createViewTablePagingState(requestSequence, params, confirmedResult),
    event(
      'resultFinished',
      result({
        success: false,
        message: 'Unknown column status',
        dataList: [],
        pageNo: 2,
      }),
    ),
    requestSequence,
  );

  assert.equal(transition.completedResult, undefined, 'a failed page must not replace confirmed rows');
  assert.equal(transition.errorMessage, 'Unknown column status');
  assert.equal(transition.state.errorMessage, 'Unknown column status');
  assert.deepEqual(confirmedResult.dataList, [[{ value: 'confirmed-row' }]]);
});

test('a cancelled table browse republishes the confirmed page without buffered partial rows', () => {
  const params = { sql: BASE_SQL, dataSourceId: 42, pageNo: 2, pageSize: 50_000 };
  const confirmedResult = result({
    uuid: 'confirmed-result',
    dataList: [[{ value: 'confirmed-row' }]] as any,
    pageNo: 1,
    pageSize: 1000,
  });
  const requestSequence = 9;
  let transition = reduceViewTablePagingEvent(
    createViewTablePagingState(requestSequence, params, confirmedResult),
    event('rows', result({ dataList: [[{ value: 'partial' }]] as any })),
    requestSequence,
  );
  assert.equal(transition.completedResult, undefined);

  transition = reduceViewTablePagingEvent(
    transition.state,
    event('cancelled', result()),
    requestSequence,
  );
  assert.notEqual(transition.completedResult, confirmedResult, 'rollback must publish a new object');
  assert.equal(transition.completedResult?.pageNo, 1);
  assert.equal(transition.completedResult?.pageSize, 1000);
  assert.deepEqual(transition.completedResult?.dataList, [[{ value: 'confirmed-row' }]]);
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
