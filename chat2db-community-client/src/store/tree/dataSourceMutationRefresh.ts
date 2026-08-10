import { TreeNodeData } from '@/typings';
import type { Key } from 'react';

interface DataSourceMutationRefreshDependencies {
  refreshTreeData: () => Promise<void>;
  getDataSourceList: () => TreeNodeData[] | null;
  setSelectedKeys: (keys: Key[]) => void;
  setScrollTargetKey: (key: Key | null) => void;
  loadData: (node: TreeNodeData) => Promise<unknown>;
}

export async function hydrateDataSourceAfterMutation(
  dataSourceId: number,
  dependencies: DataSourceMutationRefreshDependencies,
): Promise<TreeNodeData | null> {
  await dependencies.refreshTreeData();
  const dataSource =
    dependencies
      .getDataSourceList()
      ?.find((node) => node.extraParams?.dataSourceId === dataSourceId) ?? null;
  if (!dataSource) {
    return null;
  }

  dependencies.setSelectedKeys([dataSource.key]);
  dependencies.setScrollTargetKey(dataSource.key);
  await dependencies.loadData(dataSource);
  return dataSource;
}
