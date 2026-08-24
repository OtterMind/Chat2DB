import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveResultPaging } from './pagination';

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
