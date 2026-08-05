import type { IWorkspaceTab, IWorkspaceTabSplitLayout } from '@/typings';

export function getNextActiveWorkspaceTabIdAfterClose(params: {
  activeConsoleId?: string | number | null;
  closeTabIds: Set<string | number>;
  layout: IWorkspaceTabSplitLayout | null | undefined;
  orderedNextWorkspaceTabList: IWorkspaceTab[];
}) {
  const { activeConsoleId, closeTabIds, layout, orderedNextWorkspaceTabList } = params;
  if (activeConsoleId === undefined || activeConsoleId === null || !closeTabIds.has(activeConsoleId)) {
    return activeConsoleId ?? null;
  }
  if (!orderedNextWorkspaceTabList.length) {
    return null;
  }

  const availableTabIds = new Set(orderedNextWorkspaceTabList.map((tab) => tab.id));
  const isAvailableTabId = (id: string | number | null | undefined): id is string | number =>
    id !== undefined && id !== null && !closeTabIds.has(id) && availableTabIds.has(id);

  if (layout) {
    const activePaneId = Object.keys(layout.paneTabIds).find((paneId) =>
      layout.paneTabIds[paneId]?.includes(activeConsoleId),
    );
    if (activePaneId) {
      const paneTabIds = layout.paneTabIds[activePaneId] || [];
      const activeIndex = paneTabIds.findIndex((id) => id === activeConsoleId);
      const previousTabId = paneTabIds
        .slice(0, Math.max(activeIndex, 0))
        .reverse()
        .find(isAvailableTabId);
      const nextTabId = paneTabIds.slice(activeIndex + 1).find(isAvailableTabId);
      const fallbackPaneTabId = paneTabIds.find(isAvailableTabId);

      if (previousTabId !== undefined) {
        return previousTabId;
      }
      if (nextTabId !== undefined) {
        return nextTabId;
      }
      if (fallbackPaneTabId !== undefined) {
        return fallbackPaneTabId;
      }

      if (isAvailableTabId(layout.lastNonTerminalActiveTabId)) {
        return layout.lastNonTerminalActiveTabId;
      }

      const otherPaneActiveTabId = Object.entries(layout.activeTabIds)
        .filter(([paneId]) => paneId !== activePaneId)
        .map(([, tabId]) => tabId)
        .find(isAvailableTabId);
      if (otherPaneActiveTabId !== undefined) {
        return otherPaneActiveTabId;
      }

      const otherPaneTabId = Object.entries(layout.paneTabIds)
        .filter(([paneId]) => paneId !== activePaneId)
        .flatMap(([, tabIds]) => tabIds)
        .find(isAvailableTabId);
      if (otherPaneTabId !== undefined) {
        return otherPaneTabId;
      }
    }
  }

  return orderedNextWorkspaceTabList[orderedNextWorkspaceTabList.length - 1]?.id ?? null;
}
