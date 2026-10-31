import { TreeNodeType } from '@/constants/tree';
import assert from 'node:assert/strict';
import type { DatabaseTypeCode } from '@/constants/common';
import { createCachedDataSourceSelection, filterBoundInfoChildren } from './dataSourceSelection';

assert.deepEqual(
  createCachedDataSourceSelection({
    dataSourceId: 17,
    dataSourceName: '  deleted-orders  ',
    databaseType: 'MYSQL' as DatabaseTypeCode,
    environmentId: 3,
    environment: { id: 3, name: 'Production', shortName: 'PROD', color: '#FF0000' },
    identityColor: '#112233',
    watermarkEnabled: true,
    watermarkContent: 'Finance',
  }),
  {
    value: '17',
    label: 'deleted-orders',
    title: '  deleted-orders  ',
    dataSourceId: 17,
    environmentId: 3,
    environment: { id: 3, name: 'Production', shortName: 'PROD', color: '#FF0000' },
    identityColor: '#112233',
    watermarkEnabled: true,
    watermarkContent: 'Finance',
    databaseType: 'MYSQL',
  },
);
assert.equal(createCachedDataSourceSelection({ dataSourceId: 18 }).label, '');

console.log('Cached data source selection tests passed');

const database = { treeNodeType: TreeNodeType.DATABASE, name: 'orders' };
const schema = { treeNodeType: TreeNodeType.SCHEMA, name: 'public' };
const utility = { treeNodeType: TreeNodeType.MONITOR, name: 'monitor' };
assert.deepEqual(filterBoundInfoChildren([database, utility], TreeNodeType.DATABASE), [database]);
assert.deepEqual(filterBoundInfoChildren([schema, utility], TreeNodeType.SCHEMA), [schema]);
assert.deepEqual(filterBoundInfoChildren([utility], TreeNodeType.DATABASE), []);
