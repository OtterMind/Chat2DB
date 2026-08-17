import type { ILoadDataOptions, ILoadDataResult } from '@/blocks/NewTree/treeConfig';
import type { TreeNodeData } from '@/typings';
import type { Key } from 'react';

interface DatabaseTreePathStore {
  treeData: TreeNodeData[] | null;
  expandedKeys: Key[];
  handleLoadData: (node: TreeNodeData, options?: ILoadDataOptions) => Promise<ILoadDataResult>;
  setExpandedKeys: (keys: Key[]) => void;
}

function findTreeNodeByKey(treeData: TreeNodeData[] | null | undefined, key: Key): TreeNodeData | undefined {
  if (!treeData) {
    return undefined;
  }

  for (const node of treeData) {
    if (node.key === key) {
      return node;
    }
    const child = findTreeNodeByKey(node.children, key);
    if (child) {
      return child;
    }
  }
  return undefined;
}

export async function loadDatabaseTreePath(
  loadPath: string[],
  getTreeStore: () => DatabaseTreePathStore,
  isCurrent: () => boolean,
): Promise<boolean> {
  for (const key of loadPath) {
    if (!isCurrent()) {
      return false;
    }

    let treeStore = getTreeStore();
    const node = findTreeNodeByKey(treeStore.treeData, key);
    if (!node) {
      break;
    }

    if (node.children === undefined && !node.isLeaf) {
      try {
        const loadResult = await treeStore.handleLoadData(node, {
          closeExpandTreeNode: true,
          preserveInteraction: true,
        });
        if (!loadResult.committed) {
          return false;
        }
      } catch {
        return false;
      }
      if (!isCurrent()) {
        return false;
      }
      treeStore = getTreeStore();
    }

    if (!isCurrent()) {
      return false;
    }
    if (!findTreeNodeByKey(treeStore.treeData, key)) {
      return false;
    }
    treeStore.setExpandedKeys([...treeStore.expandedKeys, key]);
  }

  return isCurrent();
}
