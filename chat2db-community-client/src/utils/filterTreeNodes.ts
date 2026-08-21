import type { TreeNodeData } from '@/typings';
import { TreeNodeType } from '../constants/tree';

export interface FilterTreeNodesOptions {
  hiddenTreeNodeIds?: Record<string, string[]> | null;
  hiddenNoPermission?: boolean;
  excludeNodes?: string[];
  leafNodes?: string[];
}

export function filterTreeNodesForDisplay(
  treeData: TreeNodeData[],
  options: FilterTreeNodesOptions = {},
  inheritedHiddenIds?: string[] | null,
): TreeNodeData[] {
  const {
    hiddenTreeNodeIds,
    hiddenNoPermission,
    excludeNodes,
    leafNodes,
  } = options;

  return treeData
    .map((node) => {
      const newNode = { ...node };
      const dataSourceHiddenIds = newNode.extraParams?.dataSourceId != null
        ? hiddenTreeNodeIds?.[newNode.extraParams.dataSourceId]
        : null;
      const hiddenIds = inheritedHiddenIds || dataSourceHiddenIds;

      if (excludeNodes?.includes(newNode.treeNodeType)) {
        return null;
      }

      if (
        hiddenNoPermission &&
        newNode.treeNodeType === TreeNodeType.DATA_SOURCE &&
        !newNode.extraParams?.hasPermission
      ) {
        return null;
      }

      if (
        (newNode.treeNodeType === TreeNodeType.DATABASE || newNode.treeNodeType === TreeNodeType.SCHEMA) &&
        hiddenIds?.includes(String(newNode.key))
      ) {
        return null;
      }

      if (leafNodes?.includes(newNode.treeNodeType)) {
        newNode.isLeaf = true;
        newNode.children = undefined;
      } else if (newNode.children) {
        newNode.children = filterTreeNodesForDisplay(newNode.children, options, hiddenIds);
      }

      return newNode;
    })
    .filter((node): node is TreeNodeData => Boolean(node));
}
