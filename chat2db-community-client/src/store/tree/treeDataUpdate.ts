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

export function mergeLoadedTreeDataForSearchRefresh(
  freshTreeData: TreeNodeData[],
  currentTreeData: TreeNodeData[] | null,
  preserveLoadedChildrenForKeys: ReadonlySet<Key>,
): { treeData: TreeNodeData[]; invalidatedKeys: Key[] } {
  const treeData = mergeLoadedTreeData(freshTreeData, currentTreeData);
  if (!currentTreeData?.length) {
    return { treeData, invalidatedKeys: [] };
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

  const invalidatedKeys: Key[] = [];
  const collectCachedNodes = (nodes: TreeNodeData[]) => {
    nodes.forEach((node) => {
      if (node.children === undefined || node.isLeaf) {
        return;
      }
      if (!preserveLoadedChildrenForKeys.has(node.key)) {
        invalidatedKeys.push(node.key);
        return;
      }
      collectCachedNodes(node.children);
    });
  };
  const visitFreshNodes = (nodes: TreeNodeData[]) => {
    nodes.forEach((node) => {
      const currentNode = currentNodesByKey.get(node.key);
      if (node.children !== undefined) {
        visitFreshNodes(node.children);
        return;
      }
      if (currentNode?.children === undefined) {
        return;
      }
      if (!preserveLoadedChildrenForKeys.has(node.key)) {
        invalidatedKeys.push(node.key);
        return;
      }
      collectCachedNodes(currentNode.children);
    });
  };
  visitFreshNodes(freshTreeData);
  return { treeData, invalidatedKeys };
}

export function updateInvalidatedTreeNodeKeys(
  currentKeys: Key[],
  treeData: TreeNodeData[],
  invalidatedKeys: Key[],
  refreshedKeys: Iterable<Key> = [],
): Key[] {
  const existingKeys = new Set<Key>();
  const collectKeys = (nodes: TreeNodeData[]) => {
    nodes.forEach((node) => {
      existingKeys.add(node.key);
      if (node.children) {
        collectKeys(node.children);
      }
    });
  };
  collectKeys(treeData);
  const refreshedKeySet = new Set(refreshedKeys);
  const retainedKeys = currentKeys.filter((key) => !refreshedKeySet.has(key));
  return Array.from(new Set([...retainedKeys, ...invalidatedKeys])).filter((key) => existingKeys.has(key));
}
