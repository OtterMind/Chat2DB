import type { Key } from 'react';
import type { TreeNodeData } from '@/typings';

export function appendExpandedTreeKey(expandedKeys: Key[], key: Key): Key[] {
  return expandedKeys.includes(key) ? expandedKeys : [...expandedKeys, key];
}

export function isDataSourceTreeNodeKey(key: Key, dataSourceId: number): boolean {
  const dataSourceKey = `dataSource_${dataSourceId}`;
  const treeNodeKey = String(key);
  return treeNodeKey === dataSourceKey || treeNodeKey.startsWith(`${dataSourceKey}-`);
}

export function removeTreeNodeByKey(treeData: TreeNodeData[], key: Key): TreeNodeData[] {
  let changed = false;
  const nextTreeData: TreeNodeData[] = [];

  treeData.forEach((node) => {
    if (node.key === key) {
      changed = true;
      return;
    }

    if (node.children) {
      const children = removeTreeNodeByKey(node.children, key);
      if (children !== node.children) {
        changed = true;
        nextTreeData.push({ ...node, children });
        return;
      }
    }
    nextTreeData.push(node);
  });

  return changed ? nextTreeData : treeData;
}

export function mergeLoadedTreeData(
  freshTreeData: TreeNodeData[],
  currentTreeData: TreeNodeData[] | null,
): TreeNodeData[] {
  if (!currentTreeData?.length) {
    return freshTreeData;
  }

  const currentNodesByKey = new Map<Key, TreeNodeData>();
  const indexCurrentNodes = (nodes: TreeNodeData[]) => {
    nodes.forEach((node) => {
      currentNodesByKey.set(node.key, node);
      if (node.children) {
        indexCurrentNodes(node.children);
      }
    });
  };
  indexCurrentNodes(currentTreeData);

  const mergeNodes = (freshNodes: TreeNodeData[]): TreeNodeData[] => freshNodes.map((freshNode) => {
    const currentNode = currentNodesByKey.get(freshNode.key);
    if (!currentNode) {
      return freshNode.children === undefined
        ? freshNode
        : {
            ...freshNode,
            children: mergeNodes(freshNode.children),
          };
    }

    if (freshNode.children !== undefined) {
      return {
        ...freshNode,
        children: mergeNodes(freshNode.children),
      };
    }

    if (currentNode.children !== undefined) {
      return {
        ...freshNode,
        children: currentNode.children,
      };
    }

    return freshNode;
  });

  return mergeNodes(freshTreeData);
}

export function resolveLoadedTreeData(
  freshTreeData: TreeNodeData[],
  currentTreeData: TreeNodeData[] | null,
  authoritative: boolean,
): TreeNodeData[] {
  return authoritative ? freshTreeData : mergeLoadedTreeData(freshTreeData, currentTreeData);
}
