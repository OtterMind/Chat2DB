import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { buildChartExecutionResult, type DatabaseInfoAndMetaData } from './executionResult';

const previousDatabaseInfo = {
  dataSourceId: 1,
  dataSourceName: 'previous-source',
  databaseType: DatabaseTypeCode.MYSQL,
  databaseName: 'previous_database',
  schemaName: 'previous_schema',
  sql: 'select old_value',
} as const;
const currentDatabaseInfo = {
  dataSourceId: 2,
  dataSourceName: 'current-source',
  databaseType: DatabaseTypeCode.MYSQL,
  databaseName: 'current_database',
  schemaName: 'current_schema',
  sql: 'select current_value',
} as const;
const populatedData = {
  dataList: [[{ value: 'old-value' }]],
  headerList: [{ name: 'old_column' }],
} as any;

let savedResult: DatabaseInfoAndMetaData = buildChartExecutionResult({
  databaseInfo: previousDatabaseInfo,
  data: [populatedData],
});
savedResult = buildChartExecutionResult({ databaseInfo: currentDatabaseInfo, data: [] });

assert.deepEqual(savedResult.metaData?.dataList, []);
assert.deepEqual(savedResult.metaData?.headerList, []);
assert.deepEqual(savedResult.databaseInfo, currentDatabaseInfo);

const firstEmptyResult = buildChartExecutionResult({ databaseInfo: currentDatabaseInfo, data: [] });
assert.deepEqual(firstEmptyResult.metaData?.dataList, []);
assert.deepEqual(firstEmptyResult.metaData?.headerList, []);

const currentData = {
  dataList: [[{ value: 'current-value' }]],
  headerList: [{ name: 'current_column' }],
} as any;
const populatedResult = buildChartExecutionResult({
  databaseInfo: currentDatabaseInfo,
  data: [currentData],
});
assert.equal(populatedResult.metaData?.dataList, currentData.dataList);
assert.equal(populatedResult.metaData?.headerList, currentData.headerList);
assert.deepEqual(populatedResult.databaseInfo, currentDatabaseInfo);

console.log('BI chart SQL execution result tests passed');
