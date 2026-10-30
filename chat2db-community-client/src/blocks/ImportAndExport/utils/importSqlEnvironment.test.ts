import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { isSqlImportEnvironmentReady, sqlImportEnvironmentTarget } from './importSqlEnvironment';

const initial = { dataSourceId: 1, databaseType: DatabaseTypeCode.MYSQL, databaseName: 'old_db', tableName: 'old_table' };
assert.equal(isSqlImportEnvironmentReady(initial), true);
assert.equal(isSqlImportEnvironmentReady({ ...initial, databaseName: undefined }), false);
assert.equal(isSqlImportEnvironmentReady({}), false);
assert.equal(isSqlImportEnvironmentReady({ dataSourceId: 2, databaseType: DatabaseTypeCode.POSTGRESQL,
  databaseName: 'new_db' }), false);
assert.equal(isSqlImportEnvironmentReady({ dataSourceId: 2, databaseType: DatabaseTypeCode.POSTGRESQL,
  databaseName: 'new_db', schemaName: 'public' }), true);
assert.equal(isSqlImportEnvironmentReady({ dataSourceId: 3, databaseType: DatabaseTypeCode.REDIS }), false);
assert.deepEqual(sqlImportEnvironmentTarget(initial), {
  dataSourceId: 1, databaseName: 'old_db', schemaName: undefined, tableName: undefined,
});
// A connection change must not inherit the previous connection's database, schema or table.
assert.deepEqual(sqlImportEnvironmentTarget({ dataSourceId: 2, databaseType: DatabaseTypeCode.MYSQL }), {
  dataSourceId: 2, databaseName: undefined, schemaName: undefined, tableName: undefined,
});
assert.deepEqual(sqlImportEnvironmentTarget({ dataSourceId: 2, databaseName: 'new_db', schemaName: 'new_schema' }), {
  dataSourceId: 2, databaseName: 'new_db', schemaName: 'new_schema', tableName: undefined,
});
console.log('SQL import environment readiness and namespace isolation passed');
