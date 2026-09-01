import type { Key } from 'react';
import type { TreeNodeData } from '@/typings';

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

export function collectExpandedWorkspaceTreeNodeKeys(treeData: TreeNodeData[], expandedKeys: Key[]) {
  const expandedKeySet = new Set(expandedKeys);
  const refreshTargetKeys: Key[] = [];

  const visit = (nodes: TreeNodeData[]) => {
    nodes.forEach((node) => {
      if (expandedKeySet.has(node.key) && !node.isLeaf && node.children !== undefined) {
        refreshTargetKeys.push(node.key);
      }
      if (node.children) {
        visit(node.children);
      }
    });
  };

  visit(treeData);
  return refreshTargetKeys;
}
