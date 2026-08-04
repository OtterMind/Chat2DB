import assert from 'node:assert/strict';
import type { TreeNodeData } from '@/typings';
import { updateTreeData } from './treeDataUpdate';

const node = (key: string, children?: TreeNodeData[]): TreeNodeData =>
  ({ key, children } as TreeNodeData);

assert.equal(updateTreeData(null, 'child', []), null);

const replacement = [node('replacement')];
const tree = [node('root', [node('child')])];
const updated = updateTreeData(tree, 'child', replacement, 1);

assert.notEqual(updated, tree);
assert.deepEqual(updated?.[0].children?.[0].children, replacement);
assert.equal(updated?.[0].children?.[0].childCount, 1);
assert.equal(tree[0].children?.[0].children, undefined);

console.log('Tree data update tests passed');
