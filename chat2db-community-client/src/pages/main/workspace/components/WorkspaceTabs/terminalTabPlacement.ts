import { WorkspaceTabType } from '@/constants/workspace';
import type {
  IWorkspaceTab,
  IWorkspaceTabPaneNode,
  IWorkspaceTabSplitLayout,
  WorkspaceTabPaneId,
  WorkspaceTabSplitDirection,
} from '@/typings';
import type { TerminalOpenPosition } from '@/typings/settings';
import { collectWorkspaceTabPaneIds, createWorkspaceTabSplitNode } from './workspaceTabLayout';

const MAIN_WORKSPACE_TAB_PANE: WorkspaceTabPaneId = 'main';
const TERMINAL_PANE_IDS: Record<Exclude<TerminalOpenPosition, 'tab'>, WorkspaceTabPaneId> = {
  bottom: 'terminal-panel:bottom',
  right: 'terminal-panel:right',
};

function isDockPosition(value?: TerminalOpenPosition): value is Exclude<TerminalOpenPosition, 'tab'> {
  return value === 'bottom' || value === 'right';
}

function createRootFromLayout(layout: IWorkspaceTabSplitLayout): IWorkspaceTabPaneNode {
  if (layout.root) {
    return layout.root;
  }
  const paneIds = Object.keys(layout.paneTabIds).filter((paneId) => layout.paneTabIds[paneId]?.length);
  const firstPaneId = paneIds[0] || MAIN_WORKSPACE_TAB_PANE;
  return paneIds.slice(1).reduce<IWorkspaceTabPaneNode>(
    (root, paneId) => createWorkspaceTabSplitNode(layout.direction, root, { type: 'pane', id: paneId }),
    { type: 'pane', id: firstPaneId },
  );
}

function getActiveTabId(tabIds: Array<string | number>, activeTabId?: string | number | null) {
  return activeTabId !== undefined && activeTabId !== null && tabIds.includes(activeTabId)
    ? activeTabId
    : tabIds[0] ?? null;
}

export function applyTerminalTabOpenPositions(
  layout: IWorkspaceTabSplitLayout | null | undefined,
  workspaceTabs: IWorkspaceTab[],
  activeTabId?: string | number | null,
): IWorkspaceTabSplitLayout | null {
  const assignedTabIds = new Set(Object.values(layout?.paneTabIds || {}).flat());
  const pendingTerminalTabs = workspaceTabs.filter(
    (tab) =>
      tab.type === WorkspaceTabType.Terminal &&
      isDockPosition(tab.uniqueData?.terminalOpenPosition) &&
      !assignedTabIds.has(tab.id),
  );

  if (!pendingTerminalTabs.length) {
    return layout || null;
  }

  let nextLayout = layout
    ? {
        ...layout,
        root: createRootFromLayout(layout),
        paneTabIds: Object.fromEntries(
          Object.entries(layout.paneTabIds).map(([paneId, tabIds]) => [paneId, [...tabIds]]),
        ),
        activeTabIds: { ...layout.activeTabIds },
      }
    : null;

  if (!nextLayout) {
    const pendingIds = new Set(pendingTerminalTabs.map((tab) => tab.id));
    const mainTabIds = workspaceTabs.filter((tab) => !pendingIds.has(tab.id)).map((tab) => tab.id);
    if (!mainTabIds.length) {
      return null;
    }
    nextLayout = {
      direction: 'vertical',
      activePane: MAIN_WORKSPACE_TAB_PANE,
      root: { type: 'pane', id: MAIN_WORKSPACE_TAB_PANE },
      paneTabIds: { [MAIN_WORKSPACE_TAB_PANE]: mainTabIds },
      activeTabIds: {
        [MAIN_WORKSPACE_TAB_PANE]: getActiveTabId(mainTabIds, activeTabId),
      },
    };
  }

  pendingTerminalTabs.forEach((tab) => {
    const position = tab.uniqueData!.terminalOpenPosition as Exclude<TerminalOpenPosition, 'tab'>;
    const paneId = TERMINAL_PANE_IDS[position];
    const root = nextLayout!.root!;
    const paneExists = collectWorkspaceTabPaneIds(root).includes(paneId);

    if (paneExists) {
      nextLayout!.paneTabIds[paneId] = [...(nextLayout!.paneTabIds[paneId] || []), tab.id];
    } else {
      const direction: WorkspaceTabSplitDirection = position === 'right' ? 'vertical' : 'horizontal';
      const nextRoot = createWorkspaceTabSplitNode(
        direction,
        root,
        { type: 'pane', id: paneId },
        position === 'right' ? '70%' : '65%',
      );
      nextLayout = {
        ...nextLayout!,
        direction,
        root: nextRoot,
        paneTabIds: {
          ...nextLayout!.paneTabIds,
          [paneId]: [tab.id],
        },
      };
    }

    nextLayout!.activePane = paneId;
    nextLayout!.activeTabIds[paneId] = tab.id;
  });

  const activePane = Object.keys(nextLayout.paneTabIds).find((paneId) =>
    nextLayout!.paneTabIds[paneId]?.includes(activeTabId as string | number),
  );
  if (activePane) {
    nextLayout.activePane = activePane;
    nextLayout.activeTabIds[activePane] = activeTabId ?? null;
  }

  return nextLayout;
}
