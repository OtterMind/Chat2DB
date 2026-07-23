export const WORKSPACE_RESULT_INSPECTOR_PORTAL_ID = 'workspace-result-inspector-portal';

const WORKSPACE_RESULT_INSPECTOR_PREFIX = 'resultInspector:';

export function getWorkspaceResultInspectorCode(ownerId: string) {
  return `${WORKSPACE_RESULT_INSPECTOR_PREFIX}${ownerId}`;
}

export function isWorkspaceResultInspectorCode(code?: string | null) {
  return !!code?.startsWith(WORKSPACE_RESULT_INSPECTOR_PREFIX);
}
