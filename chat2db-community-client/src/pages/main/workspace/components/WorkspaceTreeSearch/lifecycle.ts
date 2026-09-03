import type { Key } from 'react';
import type { TreeNodeData } from '@/typings';
import { filterTreeNodesForDisplay } from '@/utils/filterTreeNodes';
import { searchTreeNodes } from '@/utils/searchTreeNodes';

export type WorkspaceTreeSearchEvent =
  | { type: 'query-change'; value: string }
  | { type: 'exit' }
  | {
      type:
        | 'tree-select'
        | 'tree-expand'
        | 'tree-context-menu'
        | 'tree-blank-click'
        | 'focus-change'
        | 'refresh-start'
        | 'refresh-success'
        | 'refresh-failure'
        | 'lazy-load'
        | 'active-tab-change';
    };

export function transitionWorkspaceTreeSearchQuery(currentQuery: string, event: WorkspaceTreeSearchEvent) {
  if (event.type === 'query-change') {
    return event.value;
  }
  if (event.type === 'exit') {
    return '';
  }
  return currentQuery;
}

export function mergeWorkspaceTreeSearchExpandedKeys(currentKeys: Key[], requiredParentKeys: Key[]) {
  return Array.from(new Set([...currentKeys, ...requiredParentKeys]));
}

export function resolveWorkspaceTreeExpandedKeys(
  expandedKeys: Key[],
  searchRequiredExpandedKeys: Key[],
  invalidatedTreeNodeKeys: Key[] = [],
) {
  const invalidatedKeySet = new Set(invalidatedTreeNodeKeys);
  return mergeWorkspaceTreeSearchExpandedKeys(expandedKeys, searchRequiredExpandedKeys).filter(
    (key) => !invalidatedKeySet.has(key),
  );
}

export function maskInvalidatedWorkspaceTreeChildren(
  treeData: TreeNodeData[],
  invalidatedTreeNodeKeys: Key[],
): TreeNodeData[] {
  if (!invalidatedTreeNodeKeys.length) {
    return treeData;
  }
  const invalidatedKeySet = new Set(invalidatedTreeNodeKeys);
  const maskNodes = (nodes: TreeNodeData[]): TreeNodeData[] =>
    nodes.map((node) => ({
      ...node,
      children: invalidatedKeySet.has(node.key)
        ? undefined
        : node.children
          ? maskNodes(node.children)
          : undefined,
    }));
  return maskNodes(treeData);
}

export function createWorkspaceTreeSearchQueryState(searchBarValue: string, searchRevision: number) {
  return {
    searchBarValue,
    regularSearchBarValue: searchBarValue.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
    searchResultKeys: null,
    searchResult: null,
    searchRequiredExpandedKeys: [] as Key[],
    searchRevision: searchRevision + 1,
  };
}

export function resolveWorkspaceTreeSearch(
  treeData: TreeNodeData[],
  regularSearchBarValue: string,
  hiddenTreeNodeIds?: Record<string, string[]> | null,
  invalidatedTreeNodeKeys: Key[] = [],
) {
  if (!regularSearchBarValue) {
    return {
      matchedNodes: null,
      matchedKeys: null,
      requiredExpandedKeys: [] as Key[],
    };
  }
  const visibleTreeData = filterTreeNodesForDisplay(
    maskInvalidatedWorkspaceTreeChildren(treeData, invalidatedTreeNodeKeys),
    {
    hiddenTreeNodeIds,
    },
  );
  const { matchedNodes, matchedKeys, parentIdsWithMatches } = searchTreeNodes(
    visibleTreeData,
    regularSearchBarValue,
  );
  return {
    matchedNodes,
    matchedKeys,
    requiredExpandedKeys: parentIdsWithMatches as Key[],
  };
}

export function collectExpandedWorkspaceTreeNodeKeys(
  treeData: TreeNodeData[],
  expandedKeys: Key[],
  searchRequiredExpandedKeys: Key[] = [],
  invalidatedTreeNodeKeys: Key[] = [],
) {
  const effectiveExpandedKeys = resolveWorkspaceTreeExpandedKeys(
    expandedKeys,
    searchRequiredExpandedKeys,
    invalidatedTreeNodeKeys,
  );
  const expandedKeySet = new Set(effectiveExpandedKeys);
  const refreshTargetKeys: Key[] = [];

  const visit = (nodes: TreeNodeData[]) => {
    nodes.forEach((node) => {
      const expanded = expandedKeySet.has(node.key);
      if (expanded && !node.isLeaf && node.children !== undefined) {
        refreshTargetKeys.push(node.key);
      }
      if (expanded && node.children) {
        visit(node.children);
      }
    });
  };

  visit(treeData);
  return refreshTargetKeys;
}
