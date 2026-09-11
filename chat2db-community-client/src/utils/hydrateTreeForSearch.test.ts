import assert from 'node:assert/strict';
import type { TreeNodeData } from '@/typings';
import { TreeNodeType } from '../constants/tree';
import { hydrateTreeForSearch } from './hydrateTreeForSearch';

const node = (key: string, treeNodeType: TreeNodeType, children?: TreeNodeData[]): TreeNodeData => ({
  key,
  originalTitle: key,
  title: null,
  treeNodeType,
  extraParams: {},
  children,
});

async function run() {
  const source = [node('datasource', TreeNodeType.DATA_SOURCE)];
  let dataSourceSearches = 0;
  const hydrated = await hydrateTreeForSearch(source, 'sales_order_history', async (current) => {
    dataSourceSearches += 1;
    assert.equal(current.treeNodeType, TreeNodeType.DATA_SOURCE);
    return [
      node('database', TreeNodeType.DATABASE, [
        node('tables', TreeNodeType.TABLES, [node('sales_order_history', TreeNodeType.TABLE)]),
      ]),
    ];
  });

  assert.equal(hydrated[0].children?.[0].children?.[0].children?.[0].originalTitle, 'sales_order_history');
  assert.equal(source[0].children, undefined);
  assert.equal(dataSourceSearches, 1);

  const loadedTable = node('customer', TreeNodeType.TABLE);
  const partiallyLoaded = [
    node('loaded', TreeNodeType.TABLES, [loadedTable]),
    node('failed', TreeNodeType.DATA_SOURCE),
  ];
  let failedBranchLoads = 0;
  const isolated = await hydrateTreeForSearch(partiallyLoaded, 'customer', async () => {
    failedBranchLoads += 1;
    throw new Error('unavailable');
  });

  assert.deepEqual(isolated[0].children, [loadedTable]);
  assert.equal(isolated[1].children, undefined);
  assert.equal(failedBranchLoads, 1);
}

void run().then(() => console.log('hydrateTreeForSearch.test.ts: all assertions passed'));
