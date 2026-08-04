import type { Key } from 'react';
import type { TreeNodeData } from '@/typings';

export function getParentNode(
  key: Key,
  tree?: TreeNodeData[] | null,
): TreeNodeData | undefined {
  for (const node of tree ?? []) {
    if (node.children?.some((child) => child.key === key)) {
      return node;
    }
    const parent = getParentNode(key, node.children);
    if (parent) {
      return parent;
    }
  }
  return undefined;
}

export function findNode(
  key: Key,
  tree?: TreeNodeData[] | null,
): TreeNodeData | undefined {
  for (const node of tree ?? []) {
    if (node.key === key) {
      return node;
    }
    const match = findNode(key, node.children);
    if (match) {
      return match;
    }
  }
  return undefined;
}
