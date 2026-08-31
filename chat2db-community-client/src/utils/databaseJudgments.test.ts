import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { IdentifierQuoteMode } from '@/constants/databaseCapabilities';
import { EditColumnOperationType } from '@/constants/editTable';
import {
  canCreateDatabase,
  canCreateSchema,
  canSetCreateDatabaseCharset,
  canSetCreateDatabaseCollation,
  canDeleteDatabase,
  canDeleteSchema,
  canExportData,
  canExportSqlFile,
  canGenerateJavaClass,
  canImportData,
  canRunSqlFile,
  canUseAccountManage,
  canUseBackendCompletion,
  canUseRoutineOperation,
  getDatabaseSupport,
  getOpenTableIdentifierQuoteMode,
  getSqlCompletionIdentifierQuoteMode,
  isMongodbTreeDataSource,
  isRedisTreeDataSource,
  isSqliteExistingColumnReadonly,
  quoteOpenTableIdentifier,
  quoteSqlCompletionIdentifier,
  shouldHideOracleIndexColumn,
  shouldShowMysqlIndexMethod,
  shouldShowMysqlTableBaseInfo,
  shouldShowSqliteIncludeCollation,
  shouldShowSqlServerSparse,
} from './databaseJudgments';

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

assert.equal(canUseRoutineOperation(DatabaseTypeCode.MYSQL), true);
assert.equal(canUseRoutineOperation('mysql'), true);
assert.equal(canUseRoutineOperation(DatabaseTypeCode.POSTGRESQL), false);
assert.equal(canUseAccountManage(DatabaseTypeCode.MYSQL), true);
assert.equal(canUseAccountManage(DatabaseTypeCode.ORACLE), false);

assert.equal(canDeleteDatabase(DatabaseTypeCode.MYSQL), true);
assert.equal(canDeleteDatabase(DatabaseTypeCode.POSTGRESQL), true);
assert.equal(canDeleteDatabase(DatabaseTypeCode.ORACLE), false);
assert.equal(canDeleteSchema(DatabaseTypeCode.POSTGRESQL), true);
assert.equal(canDeleteSchema(DatabaseTypeCode.MYSQL), false);

assert.equal(canCreateDatabase(DatabaseTypeCode.H2), false);
assert.equal(canCreateDatabase(DatabaseTypeCode.MYSQL), true);
assert.equal(canSetCreateDatabaseCharset(DatabaseTypeCode.MYSQL), true);
assert.equal(canSetCreateDatabaseCharset(DatabaseTypeCode.POSTGRESQL), false);
assert.equal(canSetCreateDatabaseCharset(DatabaseTypeCode.SQLITE), false);
assert.equal(canSetCreateDatabaseCollation(DatabaseTypeCode.MYSQL), true);
assert.equal(canSetCreateDatabaseCollation(DatabaseTypeCode.POSTGRESQL), false);
assert.equal(canSetCreateDatabaseCollation(DatabaseTypeCode.SQLITE), false);
assert.equal(canCreateSchema(DatabaseTypeCode.ORACLE), false);
assert.equal(canCreateSchema(DatabaseTypeCode.OSCAR), false);
assert.equal(canCreateSchema(DatabaseTypeCode.POSTGRESQL), true);
assert.equal(canCreateSchema('oscar_db'), true);

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
  assert.equal(canRunSqlFile(databaseType), false, `${databaseType} cannot run SQL files`);
  assert.equal(canExportSqlFile(databaseType), false, `${databaseType} cannot export SQL files`);
  assert.equal(canExportData(databaseType), false, `${databaseType} cannot export data`);
  assert.equal(canImportData(databaseType), false, `${databaseType} cannot import data`);
}
assert.equal(canRunSqlFile(DatabaseTypeCode.MYSQL), true);
assert.equal(canExportSqlFile(DatabaseTypeCode.MYSQL), true);
assert.equal(canExportData(DatabaseTypeCode.MYSQL), true);
assert.equal(canImportData(DatabaseTypeCode.MYSQL), true);

assert.equal(canGenerateJavaClass(DatabaseTypeCode.MYSQL), true);
assert.equal(canGenerateJavaClass(DatabaseTypeCode.ORACLE), true);
assert.equal(canGenerateJavaClass(DatabaseTypeCode.REDIS), false);

assert.equal(canUseBackendCompletion(DatabaseTypeCode.MYSQL), true);
assert.equal(canUseBackendCompletion(DatabaseTypeCode.POSTGRESQL), false);

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

assert.equal(isRedisTreeDataSource(DatabaseTypeCode.REDIS), true);
assert.equal(isRedisTreeDataSource(DatabaseTypeCode.MYSQL), false);
assert.equal(isMongodbTreeDataSource(DatabaseTypeCode.MONGODB), true);
assert.equal(isMongodbTreeDataSource(DatabaseTypeCode.MYSQL), false);

assert.equal(shouldShowMysqlTableBaseInfo(DatabaseTypeCode.MYSQL), true);
assert.equal(shouldShowMysqlTableBaseInfo(DatabaseTypeCode.POSTGRESQL), false);
assert.equal(shouldShowMysqlIndexMethod(DatabaseTypeCode.MYSQL), true);
assert.equal(shouldHideOracleIndexColumn(DatabaseTypeCode.ORACLE), true);
assert.equal(shouldShowSqliteIncludeCollation(DatabaseTypeCode.SQLITE), true);
assert.equal(isSqliteExistingColumnReadonly(DatabaseTypeCode.SQLITE, EditColumnOperationType.Modify), true);
assert.equal(isSqliteExistingColumnReadonly(DatabaseTypeCode.SQLITE, EditColumnOperationType.Add), false);
assert.equal(isSqliteExistingColumnReadonly(DatabaseTypeCode.MYSQL, EditColumnOperationType.Modify), false);
assert.equal(shouldShowSqlServerSparse(DatabaseTypeCode.SQLSERVER), true);
assert.equal(shouldShowSqlServerSparse(DatabaseTypeCode.MYSQL), false);

console.log('databaseJudgments.test.ts: all assertions passed');
