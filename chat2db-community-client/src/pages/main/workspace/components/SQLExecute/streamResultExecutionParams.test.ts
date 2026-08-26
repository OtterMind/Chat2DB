import assert from 'node:assert/strict';
import { buildStreamResultExecuteSqlParams } from './streamResultExecutionParams';

const params = {
  sql: 'select * from records',
  dataSourceId: 42,
  databaseName: 'app',
  pageNo: 1,
  pageSize: 1_000_000,
};

assert.deepEqual(
  buildStreamResultExecuteSqlParams(params, { originalSql: 'select * from records', resultSetId: 2 }, 9),
  {
    ...params,
    resultSetId: 2,
  },
  'streamed results retain the selected page size and result set identity',
);
assert.equal(
  buildStreamResultExecuteSqlParams(undefined, { originalSql: 'select 1', resultSetId: 1 }, 1),
  undefined,
  'missing execution parameters remain distinguishable from an empty parameter object',
);

console.log('Stream result execution parameter tests passed');
