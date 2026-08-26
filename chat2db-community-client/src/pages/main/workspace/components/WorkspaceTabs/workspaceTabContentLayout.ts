export interface WorkspacePaneContentBounds {
  left: number;
  top: number;
  width: number;
  height: number;
}

export type WorkspacePaneContentBoundsMap = Record<string, WorkspacePaneContentBounds>;

export function resolveActiveWorkspaceTabPaneIds(params: {
  activeConsoleId: string | number | null | undefined;
  paneActiveTabIds?: Record<string, string | number | null>;
  mainPaneId: string;
}) {
  const paneIdsByTabId = new Map<string | number, string>();
  if (params.paneActiveTabIds) {
    Object.entries(params.paneActiveTabIds).forEach(([paneId, tabId]) => {
      if (tabId !== null && tabId !== undefined) {
        paneIdsByTabId.set(tabId, paneId);
      }
    });
  } else if (params.activeConsoleId !== null && params.activeConsoleId !== undefined) {
    paneIdsByTabId.set(params.activeConsoleId, params.mainPaneId);
  }
  return paneIdsByTabId;
}

interface RectLike {
  left: number;
  top: number;
  width: number;
  height: number;
}

export function resolveWorkspacePaneContentBounds(
  containerRect: RectLike,
  paneRect: RectLike,
): WorkspacePaneContentBounds {
  return {
    left: paneRect.left - containerRect.left,
    top: paneRect.top - containerRect.top,
    width: paneRect.width,
    height: paneRect.height,
  };
}

export function areWorkspacePaneContentBoundsEqual(
  left: WorkspacePaneContentBoundsMap,
  right: WorkspacePaneContentBoundsMap,
) {
  const leftPaneIds = Object.keys(left);
  const rightPaneIds = Object.keys(right);
  return (
    leftPaneIds.length === rightPaneIds.length &&
    leftPaneIds.every((paneId) => {
      const leftBounds = left[paneId];
      const rightBounds = right[paneId];
      return (
        !!rightBounds &&
        leftBounds.left === rightBounds.left &&
        leftBounds.top === rightBounds.top &&
        leftBounds.width === rightBounds.width &&
        leftBounds.height === rightBounds.height
      );
    })
  );
}
