import assert from 'node:assert/strict';
import type { TreeNodeData } from '@/typings';
import { findNode, getParentNode } from './treeNodeLookup';

const node = (key: string, children?: TreeNodeData[]): TreeNodeData =>
  ({ key, children } as TreeNodeData);

const leaf = node('leaf');
const branch = node('branch', [leaf]);
const tree = [node('root', [branch])];

assert.equal(findNode('leaf', tree), leaf);
assert.equal(getParentNode('leaf', tree), branch);
assert.equal(findNode('missing', tree), undefined);
assert.equal(getParentNode('root', tree), undefined);
assert.equal(findNode('leaf', null), undefined);
assert.equal(getParentNode('leaf', null), undefined);

console.log('Tree node lookup tests passed');
