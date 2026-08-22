import assert from 'node:assert/strict';
import type { OperationTypeEnum } from '@/service/history';
import {
  areOperationLogFiltersEqual,
  buildOperationLogListParams,
  normalizeOperationLogFilters,
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
  assert.equal(
    areOperationLogFiltersEqual(
      { dataSourceId: 12, databaseName: ' application ', searchKey: 'orders' },
      { dataSourceId: 12, databaseName: 'application', searchKey: ' orders ' },
    ),
    true,
  );

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

console.log('Operation log filter tests passed');
