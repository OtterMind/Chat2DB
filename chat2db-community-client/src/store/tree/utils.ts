import { normalizeDatabaseType, TreeNodeType } from '@/constants';
import { clientRuntime } from '@client-runtime';
import { NamespacesItem, NamespaceTreeListItem } from '@/service/connection';
import { resolveDataSourceAuthorization } from '@/utils/dataSourceAuthorization';
import { getDatabaseSupport } from '@/utils/database';

// Organize dataSources nodes
export const neatenDataSourceTreeNode = (data: any) => {
  if (!data) {
    return null;
  }
  const rawDatabaseType = data.type || data.databaseType || data.dbType || data.dataSourceType;
  const databaseType = normalizeDatabaseType(rawDatabaseType) || rawDatabaseType;
  const { supportDatabase, supportSchema } = getDatabaseSupport(databaseType);
  const { hasPermission, isAdmin } = resolveDataSourceAuthorization(data, clientRuntime.usesFixedIdentity);
  return {
    key: `dataSource_${data.id}`,
    id: data.id,
    originalTitle: data.alias,
    title: null,
    treeNodeType: TreeNodeType.DATA_SOURCE,
    isLeaf: false,
    extraParams: {
      hasPermission,
      isAdmin,
      storageType: data.storageType,
      databaseType,
      dataSourceId: data.id,
      dataSourceName: data.alias,
      environmentId: data.environmentId ?? data.environment?.id ?? null,
      environment: data.environment ?? null,
      identityColor: data.identityColor ?? null,
      watermarkEnabled: data.watermarkEnabled ?? null,
      watermarkContent: data.watermarkContent ?? null,
      supportDatabase,
      supportSchema,
    },
  };
};

// Batch processing and sorting dataSources nodes
export const neatenDataSourcesList = (data: any) => {
  if (!data) return [];
  const dataSourcesList = data.map((t: any) => {
    return neatenDataSourceTreeNode(t);
  });
  // Filter out empty data
  return (dataSourcesList || []).filter((t: any) => t);
};

// Generate group node
export const neatenGroupTreeNode = (data: NamespaceTreeListItem) => {
  const groupData = data.data as NamespacesItem;
  return {
    key: `group_${data.id}`,
    id: data.id,
    originalTitle: groupData?.name,
    title: groupData?.name,
    treeNodeType: TreeNodeType.GROUP,
    children: neatenTreeData(data.children),
    isLeaf: false,
    extraParams: {
      groupId: data.id,
    },
  };
};

// Format the tree data returned by the backend.
export const neatenTreeData = (data: NamespaceTreeListItem[]) => {
  const treeList =
    data?.map((t) => {
      if (t.type === 'NAMESPACE') {
        return neatenGroupTreeNode(t);
      }
      return neatenDataSourceTreeNode(t.data);
    }) || null;
  return (treeList || []).filter((t) => t);
};
