import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { DatabaseCapability, IdentifierQuoteMode } from '@/constants/databaseCapabilities';
import {
  getDatabaseSupport,
  getOpenTableIdentifierQuoteMode,
  getSqlCompletionIdentifierQuoteMode,
  isDatabaseCapabilitySupported,
  quoteOpenTableIdentifier,
  quoteSqlCompletionIdentifier,
} from './databaseJudgments';

const assertCapability = (
  databaseType: DatabaseTypeCode | string | null | undefined,
  capability: DatabaseCapability,
  expected: boolean,
) => {
  assert.equal(
    isDatabaseCapabilitySupported(databaseType, capability),
    expected,
    `${String(databaseType)} ${capability}`,
  );
};

assert.deepEqual(getDatabaseSupport(DatabaseTypeCode.MYSQL), {
  supportDatabase: true,
  supportSchema: false,
});
assert.deepEqual(getDatabaseSupport(DatabaseTypeCode.HIVE), {
  supportDatabase: true,
  supportSchema: false,
});
assert.deepEqual(getDatabaseSupport(DatabaseTypeCode.ORACLE), {
  supportDatabase: false,
  supportSchema: true,
});
assert.deepEqual(getDatabaseSupport(undefined), {
  supportDatabase: false,
  supportSchema: false,
});
assert.deepEqual(getDatabaseSupport('oscar_db'), {
  supportDatabase: false,
  supportSchema: true,
});

assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.ROUTINE_OPERATION, true);
assertCapability('mysql', DatabaseCapability.ROUTINE_OPERATION, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.ROUTINE_OPERATION, false);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.ACCOUNT_MANAGEMENT, true);
assertCapability(DatabaseTypeCode.ORACLE, DatabaseCapability.ACCOUNT_MANAGEMENT, false);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.ACTIVE_TRANSACTION_INSPECTION, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.ACTIVE_TRANSACTION_INSPECTION, false);

assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.DATABASE_DELETE, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.DATABASE_DELETE, true);
assertCapability(DatabaseTypeCode.ORACLE, DatabaseCapability.DATABASE_DELETE, false);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.SCHEMA_DELETE, true);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.SCHEMA_DELETE, false);

assertCapability(DatabaseTypeCode.H2, DatabaseCapability.DATABASE_CREATE, false);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.DATABASE_CREATE, true);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.DATABASE_CREATE_CHARSET, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.DATABASE_CREATE_CHARSET, false);
assertCapability(DatabaseTypeCode.SQLITE, DatabaseCapability.DATABASE_CREATE_CHARSET, false);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.DATABASE_CREATE_COLLATION, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.DATABASE_CREATE_COLLATION, false);
assertCapability(DatabaseTypeCode.SQLITE, DatabaseCapability.DATABASE_CREATE_COLLATION, false);
assertCapability(DatabaseTypeCode.ORACLE, DatabaseCapability.SCHEMA_CREATE, false);
assertCapability(DatabaseTypeCode.OSCAR, DatabaseCapability.SCHEMA_CREATE, false);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.SCHEMA_CREATE, true);
assertCapability('oscar_db', DatabaseCapability.SCHEMA_CREATE, true);

for (const databaseType of [
  DatabaseTypeCode.REDIS,
  DatabaseTypeCode.H2,
  DatabaseTypeCode.PRESTO,
  DatabaseTypeCode.MONGODB,
  DatabaseTypeCode.SNOWFLAKE,
  DatabaseTypeCode.KYLIN,
  DatabaseTypeCode.KINGBASE,
  DatabaseTypeCode.HIVE,
]) {
  assertCapability(databaseType, DatabaseCapability.IMPORT_EXPORT, false);
}
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.IMPORT_EXPORT, true);

assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.JAVA_CLASS_GENERATION, true);
assertCapability(DatabaseTypeCode.ORACLE, DatabaseCapability.JAVA_CLASS_GENERATION, true);
assertCapability(DatabaseTypeCode.REDIS, DatabaseCapability.JAVA_CLASS_GENERATION, false);

assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.BACKEND_COMPLETION, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.BACKEND_COMPLETION, false);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.BACKEND_EDITOR_HINTS, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.BACKEND_EDITOR_HINTS, true);
assertCapability(DatabaseTypeCode.GAUSSDB, DatabaseCapability.BACKEND_EDITOR_HINTS, true);
assertCapability(DatabaseTypeCode.SQLSERVER, DatabaseCapability.BACKEND_EDITOR_HINTS, false);

assertCapability(DatabaseTypeCode.REDIS, DatabaseCapability.REDIS_TREE, true);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.REDIS_TREE, false);
assertCapability(DatabaseTypeCode.MONGODB, DatabaseCapability.MONGODB_TREE, true);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.MONGODB_TREE, false);

assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.TABLE_EDITOR_BASE_INFO, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.TABLE_EDITOR_BASE_INFO, false);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.TABLE_EDITOR_INDEX_METHOD, true);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.TABLE_EDITOR_COLUMN_VISIBILITY, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.TABLE_EDITOR_COLUMN_VISIBILITY, false);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.TABLE_EDITOR_INDEX_VISIBILITY, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.TABLE_EDITOR_INDEX_VISIBILITY, false);
assertCapability(DatabaseTypeCode.ORACLE, DatabaseCapability.TABLE_EDITOR_INDEX_COLUMN, false);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.TABLE_EDITOR_INDEX_COLUMN, true);
assertCapability(DatabaseTypeCode.SQLITE, DatabaseCapability.TABLE_EDITOR_INCLUDE_COLLATION, true);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.TABLE_EDITOR_INCLUDE_COLLATION, false);
assertCapability(DatabaseTypeCode.SQLITE, DatabaseCapability.TABLE_EDITOR_EXISTING_COLUMN_EDIT, false);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.TABLE_EDITOR_EXISTING_COLUMN_EDIT, true);
assertCapability(DatabaseTypeCode.SQLSERVER, DatabaseCapability.TABLE_EDITOR_SPARSE_COLUMN, true);
assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.TABLE_EDITOR_SPARSE_COLUMN, false);

assert.equal(getOpenTableIdentifierQuoteMode(DatabaseTypeCode.POSTGRESQL), IdentifierQuoteMode.DOUBLE_QUOTE);
assert.equal(getOpenTableIdentifierQuoteMode(DatabaseTypeCode.SQLSERVER), IdentifierQuoteMode.SQUARE_BRACKET);
assert.equal(getOpenTableIdentifierQuoteMode(DatabaseTypeCode.MYSQL), IdentifierQuoteMode.BACKTICK);
assert.equal(getOpenTableIdentifierQuoteMode(DatabaseTypeCode.REDIS), IdentifierQuoteMode.NONE);
assert.equal(getOpenTableIdentifierQuoteMode('mysql'), IdentifierQuoteMode.NONE);
assert.equal(quoteOpenTableIdentifier('User Table', DatabaseTypeCode.POSTGRESQL), '"User Table"');
assert.equal(quoteOpenTableIdentifier('User Table', DatabaseTypeCode.SQLSERVER), '[User Table]');
assert.equal(quoteOpenTableIdentifier('User Table', DatabaseTypeCode.MYSQL), '`User Table`');

assert.equal(getSqlCompletionIdentifierQuoteMode(DatabaseTypeCode.OCEANBASE_ORACLE), IdentifierQuoteMode.DOUBLE_QUOTE);
assert.equal(getSqlCompletionIdentifierQuoteMode(DatabaseTypeCode.OCEANBASE), IdentifierQuoteMode.BACKTICK);
assert.equal(quoteSqlCompletionIdentifier('User Table', DatabaseTypeCode.OCEANBASE_ORACLE), '"User Table"');
assert.equal(quoteSqlCompletionIdentifier('User Table', DatabaseTypeCode.OCEANBASE), '`User Table`');

assertCapability(DatabaseTypeCode.MYSQL, DatabaseCapability.TABLE_EDITOR_GENERATED_COLUMN, true);
assertCapability(DatabaseTypeCode.POSTGRESQL, DatabaseCapability.TABLE_EDITOR_GENERATED_COLUMN, false);

console.log('databaseJudgments.test.ts: all assertions passed');
