import assert from 'node:assert/strict';
import type { TreeNodeData } from '@/typings';
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
  } as TreeNodeData;
  let dataSourceList: TreeNodeData[] | null = null;

  const result = await hydrateDataSourceAfterMutation(42, {
    refreshTreeData: async () => {
      events.push('refresh');
      dataSourceList = [canonicalNode];
      return true;
    },
    getDataSourceList: () => dataSourceList,
    expandParent: (node) => events.push(`expand:${String(node.key)}`),
    setSelectedKeys: (keys) => events.push(`select:${String(keys[0])}`),
    setScrollTargetKey: (key) => events.push(`scroll:${String(key)}`),
    loadData: async (node) => {
      events.push(`load:${String(node.key)}`);
    },
  });

  assert.equal(result, canonicalNode);
  assert.deepEqual(events, [
    'refresh',
    'expand:dataSource_42',
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
  } as TreeNodeData;

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

async function testRefreshFailureDoesNotRejectSuccessfulSave() {
  const events: string[] = [];
  const originalWarn = console.warn;
  const warnings: unknown[] = [];
  console.warn = (...args: unknown[]) => {
    warnings.push(args[0]);
  };
  try {
    const result = await hydrateDataSourceAfterMutation(42, {
      refreshTreeData: async () => {
        events.push('refresh');
        throw new Error('refresh failed');
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
    });

    assert.equal(result, null, 'a failed post-save refresh must resolve, not reject');
    assert.deepEqual(events, ['refresh']);
    assert.equal(warnings.length, 1, 'the refresh failure is logged once');
  } finally {
    console.warn = originalWarn;
  }
}

async function testChildLoadFailureDoesNotRejectSuccessfulSave() {
  const events: string[] = [];
  const canonicalNode = { key: 'dataSource_42', extraParams: { dataSourceId: 42 } };
  const originalWarn = console.warn;
  const error = new Error('child load failed');
  const warnings: unknown[] = [];
  console.warn = (_message, cause) => warnings.push(cause);
  try {
    const result = await hydrateDataSourceAfterMutation(42, {
      refreshTreeData: async () => true,
      getDataSourceList: () => [canonicalNode as TreeNodeData],
      setSelectedKeys: () => events.push('select'),
      setScrollTargetKey: () => events.push('scroll'),
      loadData: async () => { throw error; },
    });
    assert.equal(result, null);
    assert.deepEqual(events, ['select', 'scroll']);
    assert.deepEqual(warnings, [error]);
  } finally {
    console.warn = originalWarn;
  }
}

async function run() {
  await testUsesCanonicalNodeLoadedAfterMutation();
  await testDoesNotReuseSparseMutationNodeWhenRefreshMisses();
  await testStopsWhenRefreshIsNotCommitted();
  await testRefreshFailureDoesNotRejectSuccessfulSave();
  await testChildLoadFailureDoesNotRejectSuccessfulSave();
  console.log('Data source mutation refresh tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
