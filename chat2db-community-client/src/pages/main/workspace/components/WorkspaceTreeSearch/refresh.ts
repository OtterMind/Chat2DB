import type { Key } from 'react';
import type { TreeNodeData } from '@/typings';
import { collectExpandedWorkspaceTreeNodeKeys } from './lifecycle';

export interface IWorkspaceTreeRefreshState {
  expandedKeys: Key[];
  searchBarValue: string;
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
  );
  const treeDataRevision = refreshedState.treeDataRevision;

  for (const key of refreshTargetKeys) {
    const currentState = getState();
    if (currentState.treeDataRevision !== treeDataRevision) {
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
