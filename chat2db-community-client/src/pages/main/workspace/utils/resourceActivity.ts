export function isWorkspaceTabResourceActive(
  workspaceVisible: boolean,
  tabId: string | number,
  activeTabId: string | number | null | undefined,
) {
  return workspaceVisible && activeTabId !== null && activeTabId !== undefined && tabId === activeTabId;
}
