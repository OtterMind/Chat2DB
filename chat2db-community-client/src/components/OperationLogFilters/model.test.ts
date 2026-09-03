import assert from 'node:assert/strict';
import type { OperationTypeEnum } from '@/service/history';
import {
  buildOperationLogListParams,
  normalizeOperationLogFilters,
  shouldApplyOperationLogPageResponse,
  shouldStartOperationLogPageRequest,
  updateOperationLogFilters,
} from './model';

const sqlExecute = 'SQL_EXECUTE' as OperationTypeEnum;

{
  const filters = normalizeOperationLogFilters({
    dataSourceId: 12,
    databaseName: '  application  ',
    schemaName: ' public ',
    searchKey: '  Orders  ',
  });

  assert.deepEqual(filters, {
    dataSourceId: 12,
    databaseName: 'application',
    schemaName: 'public',
    searchKey: 'Orders',
  });
  assert.deepEqual(normalizeOperationLogFilters({ databaseName: ' ', searchKey: '\t' }), {});
}

{
  const filters = {
    dataSourceId: 12,
    databaseName: 'application',
    schemaName: 'public',
    searchKey: 'orders',
  };

  assert.deepEqual(updateOperationLogFilters(filters, { field: 'databaseName', value: 'analytics' }), {
    dataSourceId: 12,
    databaseName: 'analytics',
    schemaName: undefined,
    searchKey: 'orders',
  });
  assert.deepEqual(updateOperationLogFilters(filters, { field: 'dataSourceId', value: undefined }), {
    dataSourceId: undefined,
    databaseName: undefined,
    schemaName: undefined,
    searchKey: 'orders',
  });
}

{
  assert.deepEqual(
    buildOperationLogListParams(
      {
        dataSourceId: 12,
        databaseName: ' application ',
        schemaName: ' public ',
        searchKey: ' orders ',
      },
      1,
      40,
      sqlExecute,
    ),
    {
      pageNo: 1,
      pageSize: 40,
      operationType: sqlExecute,
      dataSourceId: 12,
      databaseName: 'application',
      schemaName: 'public',
      searchKey: 'orders',
    },
  );
}

{
  const current = { currentGeneration: 3, finished: false };

  // Fresh replace request for the current generation starts.
  assert.equal(shouldStartOperationLogPageRequest(3, true, { ...current }), true);
  // Append request starts while pages remain.
  assert.equal(shouldStartOperationLogPageRequest(3, false, { ...current }), true);
  // Stale generation from an older filter change is dropped.
  assert.equal(shouldStartOperationLogPageRequest(2, true, { ...current }), false);
  // Append is dropped once the stream is finished, but a replace (filter change) still runs.
  assert.equal(shouldStartOperationLogPageRequest(3, false, { ...current, finished: true }), false);
  assert.equal(shouldStartOperationLogPageRequest(3, true, { ...current, finished: true }), true);
  // A request already in flight for the same generation is not duplicated.
  assert.equal(shouldStartOperationLogPageRequest(3, true, { ...current, activeRequestGeneration: 3 }), false);
  assert.equal(shouldStartOperationLogPageRequest(3, true, { ...current, activeRequestGeneration: 2 }), true);
}

{
  const response = { mounted: true, currentGeneration: 5 };

  assert.equal(shouldApplyOperationLogPageResponse(5, response), true);
  // Response arriving after a filter change (generation bumped) is dropped.
  assert.equal(shouldApplyOperationLogPageResponse(4, response), false);
  // Response arriving after unmount is dropped.
  assert.equal(shouldApplyOperationLogPageResponse(5, { ...response, mounted: false }), false);
}

console.log('Operation log filter tests passed');
