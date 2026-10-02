import assert from 'node:assert/strict';
import test from 'node:test';
import {
  isCurrentPaginationCountRequest,
  getPaginationQueryKey,
  isExpectedPaginationResponse,
  isPaginationNavigationDisabled,
  resolvePaginationTotal,
  updatePaginationPage,
  updatePaginationPageSize,
} from './paginationState';

test('exact totals disable next and last on a full final page', () => {
  const paginationConfig = {
    pageNo: 1,
    pageSize: 100,
    total: 100,
    // An exact total is authoritative even if this stale flag says more data is available.
    hasNextPage: true,
  };

  assert.equal(isPaginationNavigationDisabled(paginationConfig, 'next'), true);
  assert.equal(isPaginationNavigationDisabled(paginationConfig, 'last'), true);
});

test('exact totals keep navigation enabled until the final record is visible', () => {
  assert.equal(
    isPaginationNavigationDisabled(
      { pageNo: 1, pageSize: 100, total: 101, hasNextPage: false },
      'next',
    ),
    false,
  );
  assert.equal(
    isPaginationNavigationDisabled(
      { pageNo: 2, pageSize: 100, total: 101, hasNextPage: true },
      'next',
    ),
    true,
  );
});

test('fuzzy totals continue to use the server hasNextPage signal', () => {
  assert.equal(
    isPaginationNavigationDisabled(
      { pageNo: 1, pageSize: 100, total: '100+', hasNextPage: true },
      'next',
    ),
    false,
  );
  assert.equal(
    isPaginationNavigationDisabled(
      { pageNo: 1, pageSize: 100, total: '100+', hasNextPage: false },
      'next',
    ),
    true,
  );
});

test('a numeric final-page total from the server is authoritative even when encoded as a string', () => {
  assert.equal(
    isPaginationNavigationDisabled(
      { pageNo: 1, pageSize: 100, total: '100', hasNextPage: true },
      'next',
    ),
    true,
  );
});

test('a known numeric total survives later fuzzy paging responses', () => {
  assert.equal(resolvePaginationTotal(100, '100+'), 100);
  assert.equal(resolvePaginationTotal('100', '100+'), 100);
  assert.equal(resolvePaginationTotal('100+', '200+'), '200+');
});

test('a changed query replaces the previous exact total with its fuzzy total', () => {
  assert.equal(resolvePaginationTotal(100, '20+', false), '20+');
});

test('the pagination query key ignores page changes but isolates SQL and connection changes', () => {
  const baseResult = {
    originalSql: 'SELECT * FROM users',
    executeSqlParams: {
      dataSourceId: 1,
      databaseName: 'app',
      schemaName: 'public',
      pageNo: 1,
      pageSize: 100,
    },
  };

  assert.equal(
    getPaginationQueryKey(baseResult),
    getPaginationQueryKey({
      ...baseResult,
      executeSqlParams: { ...baseResult.executeSqlParams, pageNo: 2, pageSize: 50 },
    }),
  );
  assert.notEqual(
    getPaginationQueryKey(baseResult),
    getPaginationQueryKey({ ...baseResult, originalSql: 'SELECT * FROM users WHERE active = 1' }),
  );
  assert.notEqual(
    getPaginationQueryKey(baseResult),
    getPaginationQueryKey({
      ...baseResult,
      executeSqlParams: { ...baseResult.executeSqlParams, dataSourceId: 2 },
    }),
  );
});

test('only an expected page response can retain an exact total', () => {
  const request = { queryKey: 'query-a', pageNo: 2, pageSize: 100 };

  assert.equal(isExpectedPaginationResponse(request, 'query-a', { pageNo: 2, pageSize: 100 }), true);
  assert.equal(isExpectedPaginationResponse(undefined, 'query-a', { pageNo: 2, pageSize: 100 }), false);
  assert.equal(isExpectedPaginationResponse(request, 'query-a', { pageNo: 1, pageSize: 100 }), false);
  assert.equal(isExpectedPaginationResponse(request, 'query-b', { pageNo: 2, pageSize: 100 }), false);
});

test('only the latest count response for the current result can update the total', () => {
  const currentRequest = { queryKey: 'query-a', resultGeneration: 4, sequence: 2 };

  assert.equal(isCurrentPaginationCountRequest(currentRequest, currentRequest), true);
  assert.equal(
    isCurrentPaginationCountRequest({ ...currentRequest, sequence: 1 }, currentRequest),
    false,
  );
  assert.equal(
    isCurrentPaginationCountRequest({ ...currentRequest, resultGeneration: 3 }, currentRequest),
    false,
  );
  assert.equal(
    isCurrentPaginationCountRequest({ ...currentRequest, queryKey: 'query-b' }, currentRequest), false);
});

test('page changes retain an exact total resolved immediately before a last-page navigation', () => {
  const countResolvedConfig = { pageNo: 1, pageSize: 100, total: 250, hasNextPage: true };

  assert.deepEqual(updatePaginationPage(countResolvedConfig, 3), {
    ...countResolvedConfig,
    pageNo: 3,
  });
  assert.deepEqual(updatePaginationPageSize(countResolvedConfig, 50), {
    ...countResolvedConfig,
    pageNo: 1,
    pageSize: 50,
  });
});
