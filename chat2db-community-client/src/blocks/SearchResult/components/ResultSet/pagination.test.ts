import assert from 'node:assert/strict';
import test from 'node:test';
import { MAX_RESULT_PAGE_SIZE } from '@/constants/pagination';
import { buildResultPageExecuteParams, resolveResultPaging } from './pagination';
import './viewTablePagingFlow.test';

test('matches the Java Integer transport boundary without restoring the old product cap', () => {
  assert.equal(MAX_RESULT_PAGE_SIZE, 2_147_483_647);
});

test('uses an explicit page-size selection immediately', () => {
  assert.deepEqual(resolveResultPaging({ pageNo: 3, pageSize: 1000 }, { pageNo: 1, pageSize: 5000 }), {
    pageNo: 1,
    pageSize: 5000,
  });
});

test('preserves the current page size when only the page changes', () => {
  assert.deepEqual(resolveResultPaging({ pageNo: 3, pageSize: 5000 }, { pageNo: 2 }), {
    pageNo: 2,
    pageSize: 5000,
  });
});

test('falls back to stable defaults when result metadata is incomplete', () => {
  assert.deepEqual(resolveResultPaging(undefined), { pageNo: 1, pageSize: 1000 });
});

test('ordinary SQL paging preserves its existing SQL when no table-browser override is supplied', () => {
  assert.deepEqual(
    buildResultPageExecuteParams(
      { sql: 'SELECT 1', dataSourceId: 42, pageNo: 1, pageSize: 1000 },
      { pageNo: 2, pageSize: 5000 },
    ),
    { sql: 'SELECT 1', dataSourceId: 42, pageNo: 2, pageSize: 5000 },
  );
});
