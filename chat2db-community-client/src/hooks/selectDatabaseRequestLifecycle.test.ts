import assert from 'node:assert/strict';
import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings/tree';
import {
  createSelectDatabaseRequestLifecycle,
  disposeSelectDatabaseRequests,
  hasApplicableDatabaseNameChange,
  invalidateDatabaseOptionRequests,
  invalidateDataSourceOptionRequests,
  normalizeDatabaseOptions,
  normalizeSchemaOptions,
  runDatabaseOptionRequest,
  runSchemaOptionRequest,
} from './selectDatabaseRequestLifecycle';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, reject, resolve };
}

function treeNode(
  treeNodeType: TreeNodeType,
  originalTitle: string,
  extraParams: TreeNodeData['extraParams'],
): TreeNodeData {
  return {
    key: `${treeNodeType}-${originalTitle}`,
    originalTitle,
    treeNodeType,
    extraParams,
  };
}

async function testLatestDataSourceDatabaseRequestWins() {
  const lifecycle = createSelectDatabaseRequestLifecycle();
  const firstResponse = deferred<string[]>();
  const secondResponse = deferred<string[]>();
  const committedOptions: string[][] = [];
  const firstRequest = runDatabaseOptionRequest(
    lifecycle,
    () => firstResponse.promise,
    (options) => committedOptions.push(options),
    () => committedOptions.push([]),
  );
  const secondRequest = runDatabaseOptionRequest(
    lifecycle,
    () => secondResponse.promise,
    (options) => committedOptions.push(options),
    () => committedOptions.push([]),
  );

  secondResponse.resolve(['new-source-database']);
  await secondRequest;
  firstResponse.reject(new Error('stale source request failed'));
  await firstRequest;

  assert.deepEqual(committedOptions, [['new-source-database']]);
}

async function testLatestDatabaseSchemaRequestWins() {
  const lifecycle = createSelectDatabaseRequestLifecycle();
  const firstResponse = deferred<string[]>();
  const secondResponse = deferred<string[]>();
  const committedOptions: string[][] = [];
  const firstRequest = runSchemaOptionRequest(
    lifecycle,
    () => firstResponse.promise,
    (options) => committedOptions.push(options),
    () => committedOptions.push([]),
  );

  invalidateDatabaseOptionRequests(lifecycle);
  const secondRequest = runSchemaOptionRequest(
    lifecycle,
    () => secondResponse.promise,
    (options) => committedOptions.push(options),
    () => committedOptions.push([]),
  );
  secondResponse.resolve(['new-database-schema']);
  await secondRequest;
  firstResponse.resolve(['stale-database-schema']);
  await firstRequest;

  assert.deepEqual(committedOptions, [['new-database-schema']]);
}

async function testParentClearInvalidatesBothDependentLevels() {
  const lifecycle = createSelectDatabaseRequestLifecycle();
  const databaseResponse = deferred<string[]>();
  const schemaResponse = deferred<string[]>();
  const committedOptions: string[][] = [];
  const databaseRequest = runDatabaseOptionRequest(
    lifecycle,
    () => databaseResponse.promise,
    (options) => committedOptions.push(options),
    () => committedOptions.push([]),
  );
  const schemaRequest = runSchemaOptionRequest(
    lifecycle,
    () => schemaResponse.promise,
    (options) => committedOptions.push(options),
    () => committedOptions.push([]),
  );

  invalidateDataSourceOptionRequests(lifecycle);
  databaseResponse.resolve(['stale-database']);
  schemaResponse.resolve(['stale-schema']);
  await Promise.all([databaseRequest, schemaRequest]);

  assert.deepEqual(committedOptions, []);
}

async function testUnmountInvalidatesBothDependentLevels() {
  const lifecycle = createSelectDatabaseRequestLifecycle();
  const databaseResponse = deferred<string[]>();
  const schemaResponse = deferred<string[]>();
  const committedOptions: string[][] = [];
  const databaseRequest = runDatabaseOptionRequest(
    lifecycle,
    () => databaseResponse.promise,
    (options) => committedOptions.push(options),
    () => committedOptions.push([]),
  );
  const schemaRequest = runSchemaOptionRequest(
    lifecycle,
    () => schemaResponse.promise,
    (options) => committedOptions.push(options),
    () => committedOptions.push([]),
  );

  disposeSelectDatabaseRequests(lifecycle);
  databaseResponse.resolve(['unmounted-database']);
  schemaResponse.resolve(['unmounted-schema']);
  await Promise.all([databaseRequest, schemaRequest]);

  assert.deepEqual(committedOptions, []);
}

async function testSchemaOnlyFullInitializationKeepsSchemaRequest() {
  const lifecycle = createSelectDatabaseRequestLifecycle();
  const schemaResponse = deferred<string[]>();
  const committedOptions: string[][] = [];
  const initData = {
    dataSourceId: 1,
    databaseName: undefined,
    schemaName: 'PUBLIC',
  };

  const schemaRequest = runSchemaOptionRequest(
    lifecycle,
    () => schemaResponse.promise,
    (options) => committedOptions.push(options),
    () => committedOptions.push([]),
  );

  if (hasApplicableDatabaseNameChange(initData, false)) {
    invalidateDatabaseOptionRequests(lifecycle);
  }

  schemaResponse.resolve(['PUBLIC', 'AUDIT']);
  await schemaRequest;

  assert.deepEqual(committedOptions, [['PUBLIC', 'AUDIT']]);
}

function testOnlyRealNamedDatabaseAndSchemaNodesBecomeOptions() {
  const databaseNodes = [
    treeNode(TreeNodeType.DATABASE, 'orders', { databaseName: 'orders' }),
    treeNode(TreeNodeType.DATABASE_ACCOUNTS, 'Database Accounts', { databaseName: 'inherited-name' }),
    treeNode(TreeNodeType.AI_DATA_COLLECTIONS, 'AI data collections', {}),
    treeNode(TreeNodeType.DATABASE, 'blank database', { databaseName: '  ' }),
    treeNode(TreeNodeType.DATABASE, 'duplicate orders', { databaseName: 'orders' }),
  ];
  assert.deepEqual(normalizeDatabaseOptions(databaseNodes), [{ value: 'orders', label: 'orders' }]);

  const schemaNodes = [
    treeNode(TreeNodeType.SCHEMA, 'public', { schemaName: 'public' }),
    treeNode(TreeNodeType.TABLES, 'Tables', {}),
    treeNode(TreeNodeType.SCHEMA, 'blank schema', { schemaName: '' }),
  ];
  assert.deepEqual(normalizeSchemaOptions(schemaNodes), [{ value: 'public', label: 'public' }]);
}

async function run() {
  await testLatestDataSourceDatabaseRequestWins();
  await testLatestDatabaseSchemaRequestWins();
  await testParentClearInvalidatesBothDependentLevels();
  await testUnmountInvalidatesBothDependentLevels();
  await testSchemaOnlyFullInitializationKeepsSchemaRequest();
  testOnlyRealNamedDatabaseAndSchemaNodesBecomeOptions();
  console.log('Select database request lifecycle tests passed');
}

void run();
