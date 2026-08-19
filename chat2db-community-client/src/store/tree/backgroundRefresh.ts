import type { TreeNodeData } from '@/typings';
import type { Key } from 'react';

interface TreeNodeRefreshResult {
  children: TreeNodeData[];
  total?: number;
}

export function createSavedConsoleTreeNodeKey(params: {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  consoleId?: number;
}): string {
  const normalize = (value: string | number | null | undefined) => (value === '' || value === null ? undefined : value);
  return [
    `dataSource_${normalize(params.dataSourceId)}`,
    `database_${normalize(params.databaseName)}`,
    `schema_${normalize(params.schemaName)}`,
    `console_${normalize(params.consoleId)}`,
  ].join('-');
}

export function findTreeNode(key: Key, treeData: TreeNodeData[]): TreeNodeData | undefined {
  for (const node of treeData) {
    if (node.key === key) {
      return node;
    }
    if (node.children) {
      const child = findTreeNode(key, node.children);
      if (child) {
        return child;
      }
    }
  }
  return undefined;
}

export function reconcileTreeInteractionAfterRefresh(
  treeData: TreeNodeData[],
  selectedKeys: Key[],
  currentTreeNode: TreeNodeData | null,
): { selectedKeys: Key[]; currentTreeNode: TreeNodeData | null } {
  const retainedSelectedKeys = selectedKeys.filter((key) => findTreeNode(key, treeData));
  const nextSelectedKeys = retainedSelectedKeys.length === selectedKeys.length ? selectedKeys : retainedSelectedKeys;
  const nextCurrentTreeNode = currentTreeNode ? findTreeNode(currentTreeNode.key, treeData) || null : null;

  return {
    selectedKeys: nextSelectedKeys,
    currentTreeNode: nextCurrentTreeNode,
  };
}

export function reconcileTreeStateAfterRefresh(
  treeData: TreeNodeData[],
  selectedKeys: Key[],
  currentTreeNode: TreeNodeData | null,
  expandedKeys: Key[],
  scrollTargetKey: Key | null,
) {
  return {
    ...reconcileTreeInteractionAfterRefresh(treeData, selectedKeys, currentTreeNode),
    expandedKeys: expandedKeys.filter((key) => findTreeNode(key, treeData)?.children !== undefined),
    scrollTargetKey: scrollTargetKey && findTreeNode(scrollTargetKey, treeData) ? scrollTargetKey : null,
  };
}

export function applyExistingTreeNodeRefresh(
  treeData: TreeNodeData[],
  key: Key,
  result: TreeNodeRefreshResult,
): TreeNodeData[] {
  let changed = false;
  const nextTreeData = treeData.map((node) => {
    if (node.key === key) {
      changed = true;
      return {
        ...node,
        isLeaf: false,
        children: result.children,
        ...(result.total === undefined ? {} : { childCount: result.total }),
      };
    }

    if (node.children) {
      const children = applyExistingTreeNodeRefresh(node.children, key, result);
      if (children !== node.children) {
        changed = true;
        return {
          ...node,
          children,
        };
      }
    }
    return node;
  });

  return changed ? nextTreeData : treeData;
}
