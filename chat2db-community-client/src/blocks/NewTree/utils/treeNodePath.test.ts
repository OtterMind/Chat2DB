import assert from 'node:assert/strict';
import test from 'node:test';
import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';
import { findTreeNodeWithAncestors, resolveTreeNodeSelection } from './treeNodePath';

const node = (key: string, children?: TreeNodeData[]): TreeNodeData => ({
  key,
  originalTitle: key,
  title: null,
  treeNodeType: TreeNodeType.TABLE,
  extraParams: {},
  children,
});

test('finds a search result in the source tree with its expansion path', () => {
  const result = findTreeNodeWithAncestors(
    [node('data-source', [node('schema', [node('data_source')])])],
    'data_source',
  );

  assert.equal(result?.node.key, 'data_source');
  assert.deepEqual(result?.ancestors, ['data-source', 'schema']);
});

test('returns undefined when a search result is no longer in the source tree', () => {
  assert.equal(findTreeNodeWithAncestors([node('data-source')], 'data_source'), undefined);
});

test('restores the source node only when the click came from a search result', () => {
  const sourceTree = [node('data-source', [node('data_source')])];
  const filteredNode = { ...sourceTree[0].children![0] };

  assert.deepEqual(resolveTreeNodeSelection(sourceTree, filteredNode, true), {
    node: sourceTree[0].children![0],
    ancestors: ['data-source'],
    clearSearch: true,
  });
  assert.deepEqual(resolveTreeNodeSelection(sourceTree, filteredNode, false), {
    node: filteredNode,
    ancestors: [],
    clearSearch: false,
  });
});
