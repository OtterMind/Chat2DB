import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';

type LoadDataSourceSearchTree = (node: TreeNodeData, searchValue: string) => Promise<TreeNodeData[]>;

/**
 * Builds a transient tree containing table matches from lazy metadata branches.
 * The workspace tree itself stays lazy and is not mutated by search.
 */
export async function hydrateTreeForSearch(
  treeNodes: TreeNodeData[],
  searchValue: string,
  loadDataSourceSearchTree: LoadDataSourceSearchTree,
): Promise<TreeNodeData[]> {
  const hydrateNode = async (node: TreeNodeData): Promise<TreeNodeData> => {
    if (node.treeNodeType === TreeNodeType.DATA_SOURCE) {
      try {
        return { ...node, children: await loadDataSourceSearchTree(node, searchValue) };
      } catch {
        return node;
      }
    }

    if (!node.children?.length) {
      return node;
    }

    const hydratedChildren = await Promise.all(node.children.map(hydrateNode));
    return { ...node, children: hydratedChildren };
  };

  return Promise.all(treeNodes.map(hydrateNode));
}
