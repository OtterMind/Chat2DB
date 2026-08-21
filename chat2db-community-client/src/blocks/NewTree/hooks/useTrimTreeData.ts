import { useTreeStore } from '@/store/tree';
import { TreeNodeData } from '@/typings';
import { filterTreeNodesForDisplay } from '@/utils/filterTreeNodes';
import { useMemo } from 'react';
import { applyDatabaseObjectTreeSorting } from '../utils/sortTreeNodes';

export default function useTrimTreeData(props?: {
  leafNodes?: string[];
  hiddenNoPermission?: boolean;
  excludeNodes?: string[];
}): TreeNodeData[] | null {
  const { leafNodes, hiddenNoPermission, excludeNodes } = props || {};
  const { treeData, searchResult, hiddenTreeNodeIds, userConfigTree } = useTreeStore((state) => ({
    treeData: state.treeData,
    searchResult: state.searchResult,
    hiddenTreeNodeIds: state.hiddenTreeNodeIds,
    userConfigTree: state.userConfigTree,
  }));

  // filtered tree data
  const filteredTreeData = useMemo(() => {
    // initial data
    const _treeData = searchResult || treeData;

    if (!_treeData) {
      return null;
    }

    const filteredDatabaseAndSchema = filterTreeNodesForDisplay(_treeData, {
      hiddenTreeNodeIds,
      hiddenNoPermission,
      excludeNodes,
      leafNodes,
    });

    return applyDatabaseObjectTreeSorting(filteredDatabaseAndSchema, userConfigTree.sortDatabaseObjects === true);
  }, [
    searchResult,
    treeData,
    hiddenTreeNodeIds,
    hiddenNoPermission,
    excludeNodes,
    leafNodes,
    userConfigTree.sortDatabaseObjects,
  ]);

  return filteredTreeData;
}
