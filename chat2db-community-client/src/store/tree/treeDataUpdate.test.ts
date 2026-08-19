import assert from 'node:assert/strict';
import type { TreeNodeData } from '@/typings';
import {
  appendExpandedTreeKey,
  isDataSourceTreeNodeKey,
  mergeLoadedTreeData,
  removeTreeNodeByKey,
  resolveLoadedTreeData,
} from './treeDataUpdate';

const node = (key: string, children?: TreeNodeData[]): TreeNodeData =>
  ({ key, children } as TreeNodeData);

const expandedDuringLoad = ['database_app'];
assert.deepEqual(appendExpandedTreeKey(expandedDuringLoad, 'dataSource_1'), ['database_app', 'dataSource_1']);
assert.equal(appendExpandedTreeKey(expandedDuringLoad, 'database_app'), expandedDuringLoad);

assert.equal(isDataSourceTreeNodeKey('dataSource_1', 1), true);
assert.equal(isDataSourceTreeNodeKey('dataSource_1-database_app-schema_public', 1), true);
assert.equal(isDataSourceTreeNodeKey('dataSource_10-database_app', 1), false);
assert.equal(isDataSourceTreeNodeKey('group_1', 1), false);

const nestedDataSourceTree = [node('group_root', [node('group_nested', [node('dataSource_1')])])];
const nestedDataSourceRemoved = removeTreeNodeByKey(nestedDataSourceTree, 'dataSource_1');
assert.equal(nestedDataSourceRemoved[0].children?.[0].children?.length, 0);
assert.equal(removeTreeNodeByKey(nestedDataSourceTree, 'dataSource_missing'), nestedDataSourceTree);

const loadedDatabase = node('database_app', [node('tables')]);
const currentTree = [node('group_default', [node('dataSource_1', [loadedDatabase]), node('dataSource_deleted')])];
const lateRootResponse = [node('group_default', [node('dataSource_1'), node('dataSource_2')])];
const mergedTree = mergeLoadedTreeData(lateRootResponse, currentTree);

assert.deepEqual(mergedTree[0].children?.[0].children, [loadedDatabase]);
assert.equal(mergedTree[0].children?.some((child) => child.key === 'dataSource_deleted'), false);
assert.equal(mergedTree[0].children?.some((child) => child.key === 'dataSource_2'), true);
assert.equal(lateRootResponse[0].children?.[0].children, undefined);

const authoritativeTree = resolveLoadedTreeData(lateRootResponse, currentTree, true);
assert.equal(authoritativeTree[0].children?.[0].children, undefined);
assert.equal(authoritativeTree, lateRootResponse);

const ordinaryTree = resolveLoadedTreeData(lateRootResponse, currentTree, false);
assert.deepEqual(ordinaryTree[0].children?.[0].children, [loadedDatabase]);

const movedDataSourceTree = resolveLoadedTreeData(
  [node('group_target', [node('dataSource_1')])],
  [node('group_source', [node('dataSource_1', [loadedDatabase])])],
  false,
);
assert.deepEqual(movedDataSourceTree[0].children?.[0].children, [loadedDatabase]);
assert.equal(movedDataSourceTree.some((item) => item.key === 'group_source'), false);

console.log('Tree data update tests passed');
