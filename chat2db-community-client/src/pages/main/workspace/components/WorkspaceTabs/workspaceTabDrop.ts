import {
  IWorkspaceTabPaneNode,
  IWorkspaceTabSplitLayout,
  WorkspaceTabPaneId,
  WorkspaceTabSplitDirection,
} from '@/typings';
import { createWorkspaceTabSplitNode, replaceWorkspaceTabPaneNode } from './workspaceTabLayout';

export type WorkspaceTabDropPosition = 'top' | 'right' | 'bottom' | 'left';

const WORKSPACE_TAB_EDGE_DROP_PREFIX = 'workspace-tab-edge:';
const WORKSPACE_TAB_DROP_POSITIONS: WorkspaceTabDropPosition[] = ['top', 'right', 'bottom', 'left'];

export function getWorkspaceTabEdgeDropId(
  paneId: WorkspaceTabPaneId,
  position: WorkspaceTabDropPosition,
) {
  return `${WORKSPACE_TAB_EDGE_DROP_PREFIX}${encodeURIComponent(paneId)}:${position}`;
}

export function getWorkspaceTabEdgeDropTarget(id: string):
  | {
      paneId: WorkspaceTabPaneId;
      position: WorkspaceTabDropPosition;
    }
  | undefined {
  if (!id.startsWith(WORKSPACE_TAB_EDGE_DROP_PREFIX)) {
    return undefined;
  }
  const target = id.slice(WORKSPACE_TAB_EDGE_DROP_PREFIX.length);
  const separatorIndex = target.lastIndexOf(':');
  if (separatorIndex === -1) {
    return undefined;
  }
  const position = target.slice(separatorIndex + 1) as WorkspaceTabDropPosition;
  if (!WORKSPACE_TAB_DROP_POSITIONS.includes(position)) {
    return undefined;
  }
  try {
    const paneId = decodeURIComponent(target.slice(0, separatorIndex));
    return paneId ? { paneId, position } : undefined;
  } catch {
    return undefined;
  }
}

export function getWorkspaceTabDropPlacement(position: WorkspaceTabDropPosition): {
  direction: WorkspaceTabSplitDirection;
  newPanePlacement: 'first' | 'second';
} {
  return {
    direction: position === 'left' || position === 'right' ? 'vertical' : 'horizontal',
    newPanePlacement: position === 'left' || position === 'top' ? 'first' : 'second',
  };
}

function hasPaneNode(node: IWorkspaceTabPaneNode, paneId: WorkspaceTabPaneId): boolean {
  if (node.type === 'pane') {
    return node.id === paneId;
  }
  return hasPaneNode(node.first, paneId) || hasPaneNode(node.second, paneId);
}

function getActiveTabId(
  tabIds: Array<string | number>,
  currentActiveTabId?: string | number | null,
) {
  if (currentActiveTabId !== undefined && currentActiveTabId !== null && tabIds.includes(currentActiveTabId)) {
    return currentActiveTabId;
  }
  return tabIds[0] ?? null;
}

export function createWorkspaceTabEdgeSplitLayout({
  currentLayout,
  currentRoot,
  sourcePaneId,
  sourceTabId,
  targetPaneId,
  newPaneId,
  position,
}: {
  currentLayout: IWorkspaceTabSplitLayout;
  currentRoot: IWorkspaceTabPaneNode;
  sourcePaneId: WorkspaceTabPaneId;
  sourceTabId: string | number;
  targetPaneId: WorkspaceTabPaneId;
  newPaneId: WorkspaceTabPaneId;
  position: WorkspaceTabDropPosition;
}): IWorkspaceTabSplitLayout | undefined {
  const sourcePaneTabIds = currentLayout.paneTabIds[sourcePaneId] || [];
  const targetPaneTabIds = currentLayout.paneTabIds[targetPaneId] || [];
  if (
    !sourcePaneTabIds.includes(sourceTabId) ||
    !hasPaneNode(currentRoot, targetPaneId) ||
    (sourcePaneId === targetPaneId && sourcePaneTabIds.length <= 1)
  ) {
    return undefined;
  }

  const { direction, newPanePlacement } = getWorkspaceTabDropPlacement(position);
  const newPaneNode: IWorkspaceTabPaneNode = { type: 'pane', id: newPaneId };
  const targetPaneNode: IWorkspaceTabPaneNode = { type: 'pane', id: targetPaneId };
  const replacement = createWorkspaceTabSplitNode(
    direction,
    newPanePlacement === 'first' ? newPaneNode : targetPaneNode,
    newPanePlacement === 'second' ? newPaneNode : targetPaneNode,
  );
  const nextRoot = replaceWorkspaceTabPaneNode(currentRoot, targetPaneId, replacement);
  const nextSourcePaneTabIds = sourcePaneTabIds.filter((id) => id !== sourceTabId);
  const nextTargetPaneTabIds =
    sourcePaneId === targetPaneId
      ? nextSourcePaneTabIds
      : targetPaneTabIds.filter((id) => id !== sourceTabId);

  return {
    ...currentLayout,
    direction: nextRoot.type === 'split' ? nextRoot.direction : direction,
    root: nextRoot,
    activePane: newPaneId,
    paneTabIds: {
      ...currentLayout.paneTabIds,
      [sourcePaneId]: nextSourcePaneTabIds,
      [targetPaneId]: nextTargetPaneTabIds,
      [newPaneId]: [sourceTabId],
    },
    activeTabIds: {
      ...currentLayout.activeTabIds,
      [sourcePaneId]: getActiveTabId(
        nextSourcePaneTabIds,
        currentLayout.activeTabIds[sourcePaneId],
      ),
      [targetPaneId]: getActiveTabId(
        nextTargetPaneTabIds,
        currentLayout.activeTabIds[targetPaneId],
      ),
      [newPaneId]: sourceTabId,
    },
  };
}
