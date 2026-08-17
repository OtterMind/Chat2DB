import assert from 'node:assert/strict';
import type { ILoadDataOptions } from '@/blocks/NewTree/treeConfig';
import type { TreeNodeData } from '@/typings';
import type { Key } from 'react';
import { loadDatabaseTreePath } from './loadDatabaseTreePath';

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

const node = (key: string, children?: TreeNodeData[]): TreeNodeData =>
  ({ key, children, isLeaf: false } as TreeNodeData);

async function testSuccessfulPathLoadsSilentlyAndExpandsEachCurrentStep() {
  const database = node('database_app', []);
  const dataSource = node('dataSource_1');
  let treeData = [dataSource];
  let expandedKeys: Key[] = [];
  const loadOptions: ILoadDataOptions[] = [];
  const store = {
    get treeData() {
      return treeData;
    },
    get expandedKeys() {
      return expandedKeys;
    },
    async handleLoadData(_node: TreeNodeData, options?: ILoadDataOptions) {
      loadOptions.push(options || {});
      dataSource.children = [database];
      treeData = [dataSource];
      return { children: [database], committed: true };
    },
    setExpandedKeys(keys: Key[]) {
      expandedKeys = Array.from(new Set(keys));
    },
  };

  const loaded = await loadDatabaseTreePath(['dataSource_1', 'database_app'], () => store, () => true);

  assert.equal(loaded, true);
  assert.deepEqual(expandedKeys, ['dataSource_1', 'database_app']);
  assert.deepEqual(loadOptions, [{ closeExpandTreeNode: true, preserveInteraction: true }]);
}

async function testCancelledPathDoesNotExpandAfterPendingLoadSettles() {
  const request = deferred();
  const dataSource = node('dataSource_1');
  let current = true;
  let expandedKeys: Key[] = [];
  const store = {
    treeData: [dataSource],
    get expandedKeys() {
      return expandedKeys;
    },
    async handleLoadData() {
      await request.promise;
      return { children: [], committed: true };
    },
    setExpandedKeys(keys: Key[]) {
      expandedKeys = keys;
    },
  };

  const loadedPromise = loadDatabaseTreePath(['dataSource_1'], () => store, () => current);
  current = false;
  request.resolve();

  assert.equal(await loadedPromise, false);
  assert.deepEqual(expandedKeys, []);
}

async function testClearedTreeDoesNotReceiveExpansionFromPendingLoad() {
  const request = deferred();
  const dataSource = node('dataSource_1');
  let treeData: TreeNodeData[] | null = [dataSource];
  let expandedKeys: Key[] = [];
  const store = {
    get treeData() {
      return treeData;
    },
    get expandedKeys() {
      return expandedKeys;
    },
    async handleLoadData() {
      await request.promise;
      return { children: [], committed: true };
    },
    setExpandedKeys(keys: Key[]) {
      expandedKeys = keys;
    },
  };

  const loadedPromise = loadDatabaseTreePath(['dataSource_1'], () => store, () => true);
  treeData = null;
  request.resolve();

  assert.equal(await loadedPromise, false);
  assert.deepEqual(expandedKeys, []);
}

async function testSupersededLoadDoesNotExpandOrContinuePath() {
  const dataSource = node('dataSource_1');
  let expandedKeys: Key[] = [];
  const store = {
    treeData: [dataSource],
    get expandedKeys() {
      return expandedKeys;
    },
    async handleLoadData() {
      return { children: [node('database_app')], committed: false };
    },
    setExpandedKeys(keys: Key[]) {
      expandedKeys = keys;
    },
  };

  const loaded = await loadDatabaseTreePath(['dataSource_1', 'database_app'], () => store, () => true);

  assert.equal(loaded, false);
  assert.deepEqual(expandedKeys, []);
}

async function testMissingDescendantLeavesLoadedAncestorAvailableForFallback() {
  const dataSource = node('dataSource_1', []);
  let expandedKeys: Key[] = [];
  const store = {
    treeData: [dataSource],
    get expandedKeys() {
      return expandedKeys;
    },
    async handleLoadData() {
      throw new Error('cached children must not be loaded again');
    },
    setExpandedKeys(keys: Key[]) {
      expandedKeys = keys;
    },
  };

  const loaded = await loadDatabaseTreePath(['dataSource_1', 'database_removed'], () => store, () => true);

  assert.equal(loaded, true);
  assert.deepEqual(expandedKeys, ['dataSource_1']);
}

Promise.all([
  testSuccessfulPathLoadsSilentlyAndExpandsEachCurrentStep(),
  testCancelledPathDoesNotExpandAfterPendingLoadSettles(),
  testClearedTreeDoesNotReceiveExpansionFromPendingLoad(),
  testSupersededLoadDoesNotExpandOrContinuePath(),
  testMissingDescendantLeavesLoadedAncestorAvailableForFallback(),
])
  .then(() => {
    console.log('Database tree path loading tests passed');
  })
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
