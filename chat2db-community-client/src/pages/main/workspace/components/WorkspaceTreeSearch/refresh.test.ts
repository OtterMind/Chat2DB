import assert from 'node:assert/strict';
import type { Key } from 'react';
import type { TreeNodeData } from '@/typings';
import { refreshWorkspaceTreeData, type IWorkspaceTreeRefreshState } from './refresh';

const node = (key: string, children: TreeNodeData[] = []): TreeNodeData =>
  ({ key, children, isLeaf: false } as TreeNodeData);

const findNode = (key: Key, treeData: TreeNodeData[]): TreeNodeData | undefined => {
  for (const current of treeData) {
    if (current.key === key) return current;
    const nested = current.children ? findNode(key, current.children) : undefined;
    if (nested) return nested;
  }
  return undefined;
};

async function testQueryChangeDuringRootRefresh() {
  let resolveRoot!: (value: boolean) => void;
  const rootRefresh = new Promise<boolean>((resolve) => {
    resolveRoot = resolve;
  });
  const state: IWorkspaceTreeRefreshState = {
    expandedKeys: ['old-root'],
    searchRequiredExpandedKeys: [],
    invalidatedTreeNodeKeys: [],
    searchBarValue: 'old_query',
    searchRevision: 1,
    treeData: [node('old-root')],
    treeDataRevision: 1,
  };
  const refreshedKeys: Key[] = [];
  const refresh = refreshWorkspaceTreeData({
    findNode,
    getState: () => state,
    refreshNode: async (current) => {
      refreshedKeys.push(current.key);
    },
    refreshRoot: () => rootRefresh,
  });

  state.searchBarValue = 'new_query';
  state.searchRevision = 2;
  state.expandedKeys = [];
  state.searchRequiredExpandedKeys = ['dataSource_1', 'database_app', 'tables'];
  state.treeData = [node('dataSource_1', [node('database_app', [node('tables')])])];
  state.treeDataRevision = 2;
  resolveRoot(true);

  assert.equal(await refresh, true);
  assert.deepEqual(refreshedKeys, ['dataSource_1', 'database_app', 'tables']);
}

async function testNewRootRevisionStopsOlderContinuation() {
  const state: IWorkspaceTreeRefreshState = {
    expandedKeys: ['dataSource_1', 'database_app'],
    searchRequiredExpandedKeys: [],
    invalidatedTreeNodeKeys: [],
    searchBarValue: 'orders',
    searchRevision: 1,
    treeData: [node('dataSource_1', [node('database_app')])],
    treeDataRevision: 7,
  };
  const refreshedKeys: Key[] = [];

  const result = await refreshWorkspaceTreeData({
    findNode,
    getState: () => state,
    refreshNode: async (current) => {
      refreshedKeys.push(current.key);
      state.treeDataRevision += 1;
    },
    refreshRoot: async () => true,
  });

  assert.equal(result, true);
  assert.deepEqual(refreshedKeys, ['dataSource_1']);
}

async function testChildRefreshFailureIsReported() {
  const state: IWorkspaceTreeRefreshState = {
    expandedKeys: ['dataSource_1'],
    searchRequiredExpandedKeys: [],
    invalidatedTreeNodeKeys: [],
    searchBarValue: 'orders',
    searchRevision: 1,
    treeData: [node('dataSource_1')],
    treeDataRevision: 1,
  };

  assert.equal(
    await refreshWorkspaceTreeData({
      findNode,
      getState: () => state,
      refreshNode: async () => {
        throw new Error('metadata unavailable');
      },
      refreshRoot: async () => true,
    }),
    false,
  );
}

async function testQueryChangeStopsTheOlderSearchContinuation() {
  const state: IWorkspaceTreeRefreshState = {
    expandedKeys: [],
    searchRequiredExpandedKeys: ['dataSource_1', 'database_app'],
    invalidatedTreeNodeKeys: [],
    searchBarValue: 'old_query',
    searchRevision: 3,
    treeData: [node('dataSource_1', [node('database_app')])],
    treeDataRevision: 5,
  };
  const refreshedKeys: Key[] = [];

  const result = await refreshWorkspaceTreeData({
    findNode,
    getState: () => state,
    refreshNode: async (current) => {
      refreshedKeys.push(current.key);
      state.searchBarValue = 'new_query';
      state.searchRequiredExpandedKeys = [];
      state.searchRevision += 1;
    },
    refreshRoot: async () => true,
  });

  assert.equal(result, true);
  assert.deepEqual(refreshedKeys, ['dataSource_1']);
}

async function main() {
  await testQueryChangeDuringRootRefresh();
  await testNewRootRevisionStopsOlderContinuation();
  await testChildRefreshFailureIsReported();
  await testQueryChangeStopsTheOlderSearchContinuation();
  console.log('Workspace tree refresh lifecycle tests passed');
}

void main();
