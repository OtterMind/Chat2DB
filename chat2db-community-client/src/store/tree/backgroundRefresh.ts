import type { TreeNodeData } from '@/typings';
import type { Key } from 'react';

interface TreeNodeRefreshResult {
  children: TreeNodeData[];
  total?: number;
}

type TreeNodeRefreshLoader = () => Promise<TreeNodeData[] | TreeNodeRefreshResult>;

export class LatestTreeRefreshTracker {
  private readonly sequences = new Map<Key, number>();
  private nextSequence = 0;

  begin(key: Key): number {
    this.nextSequence += 1;
    const sequence = this.nextSequence;
    this.sequences.set(key, sequence);
    return sequence;
  }

  isLatest(key: Key, sequence: number): boolean {
    return this.sequences.get(key) === sequence;
  }

  finish(key: Key, sequence: number) {
    if (this.isLatest(key, sequence)) {
      this.sequences.delete(key);
    }
  }
}

export function createSavedConsoleTreeNodeKey(params: {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  consoleId?: number;
}): string {
  const normalize = (value: string | number | null | undefined) => (value === '' || value === null ? undefined : value);
  return [
    `dataSource_${normalize(params.dataSourceId)}`,
    `database_${normalize(params.databaseName)}`,
    `schema_${normalize(params.schemaName)}`,
    `console_${normalize(params.consoleId)}`,
  ].join('-');
}

export function findTreeNode(key: Key, treeData: TreeNodeData[]): TreeNodeData | undefined {
  for (const node of treeData) {
    if (node.key === key) {
      return node;
    }
    if (node.children) {
      const child = findTreeNode(key, node.children);
      if (child) {
        return child;
      }
    }
  }
  return undefined;
}

export function reconcileTreeInteractionAfterRefresh(
  treeData: TreeNodeData[],
  selectedKeys: Key[],
  currentTreeNode: TreeNodeData | null,
): { selectedKeys: Key[]; currentTreeNode: TreeNodeData | null } {
  const retainedSelectedKeys = selectedKeys.filter((key) => findTreeNode(key, treeData));
  const nextSelectedKeys = retainedSelectedKeys.length === selectedKeys.length ? selectedKeys : retainedSelectedKeys;
  const nextCurrentTreeNode = currentTreeNode ? findTreeNode(currentTreeNode.key, treeData) || null : null;

  return {
    selectedKeys: nextSelectedKeys,
    currentTreeNode: nextCurrentTreeNode,
  };
}

export async function loadExistingTreeNodeRefresh(
  treeData: TreeNodeData[] | null,
  key: Key,
  load: TreeNodeRefreshLoader,
): Promise<TreeNodeRefreshResult | undefined> {
  if (!treeData) {
    return undefined;
  }
  const node = findTreeNode(key, treeData);
  if (!node) {
    return undefined;
  }

  const result = await load();
  return Array.isArray(result) ? { children: result } : result;
}

export function applyExistingTreeNodeRefresh(
  treeData: TreeNodeData[],
  key: Key,
  result: TreeNodeRefreshResult,
): TreeNodeData[] {
  let changed = false;
  const nextTreeData = treeData.map((node) => {
    if (node.key === key) {
      changed = true;
      return {
        ...node,
        isLeaf: false,
        children: result.children,
        ...(result.total === undefined ? {} : { childCount: result.total }),
      };
    }

    if (node.children) {
      const children = applyExistingTreeNodeRefresh(node.children, key, result);
      if (children !== node.children) {
        changed = true;
        return {
          ...node,
          children,
        };
      }
    }
    return node;
  });

  return changed ? nextTreeData : treeData;
}
