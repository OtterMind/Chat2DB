import type { Key } from 'react';
import type { TreeNodeData } from '@/typings';

export function updateTreeData(
  list: TreeNodeData[] | null,
  key: Key,
  children: TreeNodeData[],
  childCount?: number,
  clearChildCount = false,
): TreeNodeData[] | null {
  if (list === null) {
    return null;
  }

  return list.map((node) => {
    if (node.key === key) {
      return {
        isLeaf: false,
        ...node,
        children,
        ...(clearChildCount ? { childCount: undefined } : childCount === undefined ? {} : { childCount }),
      };
    }
    if (node.children) {
      return {
        isLeaf: false,
        ...node,
        children: updateTreeData(node.children, key, children, childCount, clearChildCount) ?? [],
      };
    }
    return node;
  });
}
