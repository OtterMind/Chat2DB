export const WORKSPACE_RESULT_INSPECTOR_PORTAL_ID = 'workspace-result-inspector-portal';

export const RESULT_INSPECTOR_MAX_PANEL_RATIO = 0.5;

const WORKSPACE_RESULT_INSPECTOR_PREFIX = 'resultInspector:';

export function getWorkspaceResultInspectorCode(ownerId: string) {
  return `${WORKSPACE_RESULT_INSPECTOR_PREFIX}${ownerId}`;
}

export function isWorkspaceResultInspectorCode(code?: string | null) {
  return !!code?.startsWith(WORKSPACE_RESULT_INSPECTOR_PREFIX);
}

export function shouldClearInactiveResultInspector(
  currentWorkspaceExtend: string | null | undefined,
  inspectorExtendCode: string,
  active: boolean,
) {
  return !active && currentWorkspaceExtend === inspectorExtendCode;
}

export function getResultInspectorPanelSize(preferredSize: number, workspaceWidth: number) {
  const maxSize = workspaceWidth * RESULT_INSPECTOR_MAX_PANEL_RATIO;
  return maxSize > 0 ? Math.min(preferredSize, maxSize) : preferredSize;
}
