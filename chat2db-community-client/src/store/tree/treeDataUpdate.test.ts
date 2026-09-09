import assert from 'node:assert/strict';
import type { TreeNodeData } from '@/typings';
import {
  appendExpandedTreeKey,
  isDataSourceTreeNodeKey,
  mergeLoadedTreeData,
  mergeLoadedTreeDataForSearchRefresh,
  removeTreeNodeByKey,
  resolveLoadedTreeData,
  updateInvalidatedTreeNodeKeys,
} from './treeDataUpdate';
import { shouldReuseTreeNodeChildren } from './treeNodeLoadState';
import { reconcileTreeStateAfterRefresh } from './backgroundRefresh';
import {
  maskInvalidatedWorkspaceTreeChildren,
  resolveWorkspaceTreeExpandedKeys,
} from '../../pages/main/workspace/components/WorkspaceTreeSearch/lifecycle';

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

const staleTables = node('tables_public', [node('table_old')]);
const collapsedSchema = node('schema_archive', [node('tables_archive', [node('table_old_archive')])]);
const matchingTables = node('tables_search', [node('table_matching_result')]);
const currentSearchTree = [
  node('group_default', [
    node('dataSource_1', [
      node('database_app', [
        node('schemas', [
          node('schema_public', [staleTables]),
          collapsedSchema,
          node('schema_search', [matchingTables]),
        ]),
      ]),
    ]),
  ]),
];
const searchExpandedKeys = new Set([
  'dataSource_1',
  'database_app',
  'schemas',
  'schema_public',
  'schema_search',
  'tables_search',
]);
const searchRefresh = mergeLoadedTreeDataForSearchRefresh(
  [node('group_default', [node('dataSource_1')])],
  currentSearchTree,
  searchExpandedKeys,
);
const refreshedSearchTree = searchRefresh.treeData;
const refreshedDataSource = refreshedSearchTree[0].children?.[0];
const refreshedSchemas = refreshedDataSource?.children?.[0].children?.[0].children;
const refreshedPublicTables = refreshedSchemas?.[0].children?.[0];

assert.deepEqual(
  refreshedPublicTables?.children,
  [node('table_old')],
  'an invalidated Tables cache must remain available for interaction reconciliation until reload',
);
assert.deepEqual(
  searchRefresh.invalidatedKeys,
  ['tables_public', 'schema_archive'],
  'only the nearest collapsed loaded cache roots must be invalidated',
);
assert.deepEqual(
  refreshedSchemas?.[2].children?.[0].children,
  [node('table_matching_result')],
  'the expanded search target must remain hydrated while its authoritative refresh is pending',
);
assert.deepEqual(
  Array.from(searchExpandedKeys),
  ['dataSource_1', 'database_app', 'schemas', 'schema_public', 'schema_search', 'tables_search'],
  'cache invalidation must not change the active search expansion state',
);
assert.equal(
  shouldReuseTreeNodeChildren({
    children: refreshedPublicTables?.children,
    refresh: searchRefresh.invalidatedKeys.includes('tables_public'),
    isDataSourceRoot: false,
  }),
  false,
  're-expanding an invalidated Tables node must reload its children',
);
const selectedDescendant = refreshedSchemas?.[1].children?.[0].children?.[0];
assert.ok(selectedDescendant);
const interactionState = reconcileTreeStateAfterRefresh(
  refreshedSearchTree,
  [selectedDescendant.key],
  selectedDescendant,
  ['tables_archive'],
  selectedDescendant.key,
);
assert.deepEqual(interactionState.selectedKeys, [selectedDescendant.key]);
assert.equal(interactionState.currentTreeNode, selectedDescendant);
assert.deepEqual(interactionState.expandedKeys, ['tables_archive']);
assert.equal(interactionState.scrollTargetKey, selectedDescendant.key);

const reloadedTables = resolveLoadedTreeData([node('table_new')], refreshedPublicTables?.children ?? null, true);
assert.deepEqual(
  reloadedTables,
  [node('table_new')],
  'the reload after search closes must expose the backend mutation',
);
assert.deepEqual(
  updateInvalidatedTreeNodeKeys(
    searchRefresh.invalidatedKeys,
    refreshedSearchTree,
    [],
    ['tables_public'],
  ),
  ['schema_archive'],
  'a successful reload must clear only the cache root it refreshed',
);

const nestedReload = mergeLoadedTreeDataForSearchRefresh(
  [node('tables_archive')],
  collapsedSchema.children || null,
  new Set(['schema_archive']),
);
const nestedReloadTree = [node('schema_archive', nestedReload.treeData)];
const nestedInvalidatedKeys = updateInvalidatedTreeNodeKeys(
  ['schema_archive'],
  nestedReloadTree,
  nestedReload.invalidatedKeys,
  ['schema_archive', 'tables_archive', 'table_old_archive'],
);
assert.deepEqual(
  nestedReload.invalidatedKeys,
  ['tables_archive'],
  'an immediate-child response must not validate a historical expanded descendant cache',
);
assert.deepEqual(nestedInvalidatedKeys, ['tables_archive']);
assert.equal(
  maskInvalidatedWorkspaceTreeChildren(nestedReloadTree, nestedInvalidatedKeys)[0].children?.[0].children,
  undefined,
  'stale descendant rows must stay hidden after their ancestor reloads',
);
assert.deepEqual(
  resolveWorkspaceTreeExpandedKeys(['tables_archive'], [], nestedInvalidatedKeys),
  [],
  'historical expansion intent must not make an invalidated descendant appear loaded',
);
assert.equal(
  shouldReuseTreeNodeChildren({
    children: nestedReload.treeData[0].children,
    refresh: nestedInvalidatedKeys.includes('tables_archive'),
    isDataSourceRoot: false,
  }),
  false,
  'the next descendant expansion must reload instead of reusing resurrected rows',
);

console.log('Tree data update tests passed');
