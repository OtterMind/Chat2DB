import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';
import type { DataSourceRuntimeAvailabilityById } from '@/utils/editorDataSourceLifecycle';

export function collectDataSourceNodes(treeData: readonly TreeNodeData[]) {
  const dataSourceList: TreeNodeData[] = [];
  const collect = (node: TreeNodeData) => {
    if (node.treeNodeType === TreeNodeType.DATA_SOURCE) {
      dataSourceList.push(node);
    }
    node.children?.forEach(collect);
  };
  treeData.forEach(collect);
  return dataSourceList;
}

export function pruneDataSourceRuntimeAvailability(
  dataSourceList: readonly TreeNodeData[],
  runtimeAvailabilityByDataSourceId: DataSourceRuntimeAvailabilityById,
) {
  const currentDataSourceIds = new Set(dataSourceList.map((item) => item.extraParams.dataSourceId));
  return Object.fromEntries(
    Object.entries(runtimeAvailabilityByDataSourceId).filter(([dataSourceId]) =>
      currentDataSourceIds.has(Number(dataSourceId)),
    ),
  ) as DataSourceRuntimeAvailabilityById;
}
