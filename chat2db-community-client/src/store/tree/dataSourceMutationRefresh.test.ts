import assert from 'node:assert/strict';
import { hydrateDataSourceAfterMutation } from './dataSourceMutationRefresh';

async function testUsesCanonicalNodeLoadedAfterMutation() {
  const events: string[] = [];
  const canonicalNode = {
    key: 'dataSource_42',
    extraParams: {
      dataSourceId: 42,
      storageType: 'CLOUD',
      hasPermission: true,
    },
  } as any;
  let dataSourceList: any[] | null = null;

  const result = await hydrateDataSourceAfterMutation(42, {
    refreshTreeData: async () => {
      events.push('refresh');
      dataSourceList = [canonicalNode];
      return true;
    },
    getDataSourceList: () => dataSourceList,
    setSelectedKeys: (keys) => events.push(`select:${String(keys[0])}`),
    setScrollTargetKey: (key) => events.push(`scroll:${String(key)}`),
    loadData: async (node) => {
      events.push(`load:${String(node.key)}`);
    },
  });

  assert.equal(result, canonicalNode);
  assert.deepEqual(events, [
    'refresh',
    'select:dataSource_42',
    'scroll:dataSource_42',
    'load:dataSource_42',
  ]);
}

async function testDoesNotReuseSparseMutationNodeWhenRefreshMisses() {
  const events: string[] = [];

  const result = await hydrateDataSourceAfterMutation(42, {
    refreshTreeData: async () => {
      events.push('refresh');
      return true;
    },
    getDataSourceList: () => [],
    setSelectedKeys: () => events.push('select'),
    setScrollTargetKey: () => events.push('scroll'),
    loadData: async () => {
      events.push('load');
    },
  });

  assert.equal(result, null);
  assert.deepEqual(events, ['refresh']);
}

async function testStopsWhenRefreshIsNotCommitted() {
  const events: string[] = [];
  const staleNode = {
    key: 'dataSource_42',
    extraParams: {
      dataSourceId: 42,
    },
  } as any;

  const result = await hydrateDataSourceAfterMutation(42, {
    refreshTreeData: async () => {
      events.push('refresh');
      return false;
    },
    getDataSourceList: () => {
      events.push('read');
      return [staleNode];
    },
    setSelectedKeys: () => events.push('select'),
    setScrollTargetKey: () => events.push('scroll'),
    loadData: async () => {
      events.push('load');
    },
  });

  assert.equal(result, null);
  assert.deepEqual(events, ['refresh']);
}

async function testPropagatesRefreshFailure() {
  const events: string[] = [];
  const refreshError = new Error('refresh failed');

  await assert.rejects(
    hydrateDataSourceAfterMutation(42, {
      refreshTreeData: async () => {
        events.push('refresh');
        throw refreshError;
      },
      getDataSourceList: () => {
        events.push('read');
        return [];
      },
      setSelectedKeys: () => events.push('select'),
      setScrollTargetKey: () => events.push('scroll'),
      loadData: async () => {
        events.push('load');
      },
    }),
    (error) => error === refreshError,
  );
  assert.deepEqual(events, ['refresh']);
}

Promise.all([
  testUsesCanonicalNodeLoadedAfterMutation(),
  testDoesNotReuseSparseMutationNodeWhenRefreshMisses(),
  testStopsWhenRefreshIsNotCommitted(),
  testPropagatesRefreshFailure(),
])
  .then(() => {
    console.log('Data source mutation refresh tests passed');
  })
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
