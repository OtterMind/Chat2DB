import type { Key } from 'react';
import type { TreeNodeData } from '@/typings';
import { collectExpandedWorkspaceTreeNodeKeys } from './lifecycle';

export interface IWorkspaceTreeRefreshState {
  expandedKeys: Key[];
  searchRequiredExpandedKeys: Key[];
  invalidatedTreeNodeKeys: Key[];
  searchBarValue: string;
  searchRevision: number;
  treeData: TreeNodeData[] | null;
  treeDataRevision: number;
}

interface IWorkspaceTreeRefreshDependencies {
  findNode: (key: Key, treeData: TreeNodeData[]) => TreeNodeData | undefined;
  getState: () => IWorkspaceTreeRefreshState;
  refreshNode: (node: TreeNodeData) => Promise<unknown>;
  refreshRoot: () => Promise<boolean>;
}

export async function refreshWorkspaceTreeData({
  findNode,
  getState,
  refreshNode,
  refreshRoot,
}: IWorkspaceTreeRefreshDependencies): Promise<boolean> {
  const refreshed = await refreshRoot();
  const refreshedState = getState();
  if (!refreshed || !refreshedState.searchBarValue || !refreshedState.treeData) {
    return refreshed;
  }

  const refreshTargetKeys = collectExpandedWorkspaceTreeNodeKeys(
    refreshedState.treeData,
    refreshedState.expandedKeys,
    refreshedState.searchRequiredExpandedKeys,
    refreshedState.invalidatedTreeNodeKeys,
  );
  const treeDataRevision = refreshedState.treeDataRevision;
  const searchRevision = refreshedState.searchRevision;

  for (const key of refreshTargetKeys) {
    const currentState = getState();
    if (
      currentState.treeDataRevision !== treeDataRevision ||
      currentState.searchRevision !== searchRevision
    ) {
      return true;
    }
    if (!currentState.treeData) {
      continue;
    }
    const node = findNode(key, currentState.treeData);
    if (!node) {
      continue;
    }
    try {
      await refreshNode(node);
    } catch {
      return false;
    }
  }
  return true;
}
