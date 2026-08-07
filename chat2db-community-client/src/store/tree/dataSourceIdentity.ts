import type { IDataSourceIdentityColorResponse, TreeNodeData } from '@/typings';

export type DataSourceIdentityColorPatch = Pick<IDataSourceIdentityColorResponse, 'id'> &
  Partial<Omit<IDataSourceIdentityColorResponse, 'id'>>;

function patchNode(node: TreeNodeData, patch: DataSourceIdentityColorPatch): TreeNodeData {
  const matchesDataSource = node.extraParams?.dataSourceId === patch.id;
  const nextChildren = node.children
    ? patchDataSourceIdentityTree(node.children, patch) ?? undefined
    : node.children;
  const childrenChanged = nextChildren !== node.children;

  if (!matchesDataSource && !childrenChanged) {
    return node;
  }

  const nextExtraParams = matchesDataSource
    ? {
        ...node.extraParams,
        ...('identityColor' in patch ? { identityColor: patch.identityColor ?? null } : {}),
        ...('environmentId' in patch ? { environmentId: patch.environmentId ?? null } : {}),
        ...('environment' in patch ? { environment: patch.environment ?? null } : {}),
      }
    : node.extraParams;

  return {
    ...node,
    extraParams: nextExtraParams,
    children: nextChildren,
  };
}

export function patchDataSourceIdentityTree(
  treeData: TreeNodeData[] | null,
  patch: DataSourceIdentityColorPatch,
): TreeNodeData[] | null {
  if (!treeData) {
    return treeData;
  }

  let changed = false;
  const nextTreeData = treeData.map((node) => {
    const nextNode = patchNode(node, patch);
    changed ||= nextNode !== node;
    return nextNode;
  });

  return changed ? nextTreeData : treeData;
}
