import assert from 'node:assert/strict';
import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';
import { patchDataSourceIdentityTree } from './dataSourceIdentity';

const node = (key: string, dataSourceId?: number, children?: TreeNodeData[]): TreeNodeData =>
  ({
    key,
    originalTitle: key,
    treeNodeType: dataSourceId ? TreeNodeType.DATA_SOURCE : TreeNodeType.GROUP,
    extraParams: dataSourceId ? { dataSourceId } : {},
    children,
  } as TreeNodeData);

const untouched = node('untouched', 2);
const descendant = node('descendant', 1);
const source = node('source', 1, [descendant]);
const tree = [node('group', undefined, [source, untouched])];
const environment = { id: 4, name: 'Production', shortName: 'PROD', color: '#445566' };
const patched = patchDataSourceIdentityTree(tree, {
  id: 1,
  identityColor: '#112233',
  environmentId: 4,
  environment,
});

assert.notEqual(patched, tree);
assert.equal(patched?.[0].children?.[0].extraParams.identityColor, '#112233');
assert.deepEqual(patched?.[0].children?.[0].extraParams.environment, environment);
assert.equal(patched?.[0].children?.[0].children?.[0].extraParams.identityColor, '#112233');
assert.equal(patched?.[0].children?.[1], untouched);
assert.equal(source.extraParams.identityColor, undefined);
assert.equal(patchDataSourceIdentityTree(tree, { id: 99, identityColor: null }), tree);
assert.equal(patchDataSourceIdentityTree(null, { id: 1, identityColor: null }), null);

console.log('Data source identity tree patch tests passed');
