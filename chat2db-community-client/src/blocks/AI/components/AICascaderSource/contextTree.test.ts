import assert from 'node:assert/strict';
import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';
import { buildAIContextTree } from './contextTree';

const node = (treeNodeType: TreeNodeType, key: string, children?: TreeNodeData[]): TreeNodeData => ({
  treeNodeType,
  key,
  originalTitle: key,
  title: key,
  isLeaf: false,
  children,
  extraParams: {},
});

const source = node(TreeNodeType.DATA_SOURCE, 'source', [
  node(TreeNodeType.DATABASE, 'app'),
  node(TreeNodeType.MONITOR, 'monitor'),
  node(TreeNodeType.DATABASE_ACCOUNTS, 'accounts'),
]);
const result = buildAIContextTree([source]);

assert.deepEqual(result[0].children?.map(({ treeNodeType }) => treeNodeType), [TreeNodeType.DATABASE]);
assert.equal(result[0].isLeaf, false);
const flatSource = node(TreeNodeType.DATA_SOURCE, 'flat-source');
flatSource.extraParams = { supportDatabase: false, supportSchema: false };
assert.equal(buildAIContextTree([flatSource])[0].isLeaf, true);
assert.equal(buildAIContextTree([node(TreeNodeType.DATA_SOURCE, 'unloaded-source')])[0].isLeaf, false);
assert.deepEqual(buildAIContextTree([node(TreeNodeType.GROUP, 'empty-group', [node(TreeNodeType.MONITOR, 'monitor')])]), []);
assert.equal(source.children?.length, 3, 'the shared workspace tree must not be mutated');
