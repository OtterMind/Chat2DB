import type { Key } from 'react';
import type { TreeNodeData } from '@/typings';

export interface TreeNodePath {
  node: TreeNodeData;
  ancestors: Key[];
}

export type TreeNodeSelection = TreeNodePath;

export function findTreeNodeWithAncestors(
  treeData: TreeNodeData[] | null | undefined,
  targetKey: Key,
  ancestors: Key[] = [],
): TreeNodePath | undefined {
  if (!treeData) {
    return undefined;
  }

  for (const node of treeData) {
    if (node.key === targetKey) {
      return { node, ancestors };
    }
    const match = findTreeNodeWithAncestors(node.children, targetKey, [...ancestors, node.key]);
    if (match) {
      return match;
    }
  }

  return undefined;
}

export function resolveTreeNodeSelection(
  treeData: TreeNodeData[] | null | undefined,
  nodeData: TreeNodeData,
  searchActive: boolean,
): TreeNodeSelection {
  const located = searchActive ? findTreeNodeWithAncestors(treeData, nodeData.key) : undefined;
  return {
    node: located?.node || nodeData,
    ancestors: located?.ancestors || [],
  };
}
