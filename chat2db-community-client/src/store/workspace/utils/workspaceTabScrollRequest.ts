export interface WorkspaceTabScrollRequest {
  tabId: string | number;
  requestId: number;
}

export function createNextWorkspaceTabScrollRequest(
  currentRequest: WorkspaceTabScrollRequest | null | undefined,
  tabId: string | number,
): WorkspaceTabScrollRequest {
  return {
    tabId,
    requestId: (currentRequest?.requestId || 0) + 1,
  };
}
