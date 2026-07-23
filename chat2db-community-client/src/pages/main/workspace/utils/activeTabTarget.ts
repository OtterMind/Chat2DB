import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings/workspace';

export type WorkspaceLeftPanel = 'explorer' | 'database';
export type WorkspaceTabActivationSource = 'workspaceTab' | 'explorerSession';
export type WorkspaceTabActivationId = string | number | null | undefined;

export type DirectActiveTabLocateTarget =
  | {
      surface: 'localFile';
      filePath: string;
    }
  | {
      surface: 'databaseTree';
    };

export function resolveWorkspaceLeftPanel(panel?: WorkspaceLeftPanel): WorkspaceLeftPanel {
  return panel || 'database';
}

export function getAutoFollowWorkspaceLeftPanel(
  enabled: boolean,
  target?: Pick<DirectActiveTabLocateTarget, 'surface'> | null,
  source: WorkspaceTabActivationSource = 'workspaceTab',
): WorkspaceLeftPanel | undefined {
  if (!enabled || !target || source === 'explorerSession') {
    return undefined;
  }
  return target.surface === 'localFile' ? 'explorer' : 'database';
}

export function shouldLocateActiveTabOnPanelSelection(
  panel: WorkspaceLeftPanel,
  target?: Pick<DirectActiveTabLocateTarget, 'surface'> | null,
): boolean {
  return panel === 'database' && target?.surface === 'databaseTree';
}

export function resolveWorkspaceLeftAutoFollowState({
  activeWorkspaceTabId,
  autoFollowPanel,
  manualOverrideTabId,
  showExplorerPanel,
}: {
  activeWorkspaceTabId: WorkspaceTabActivationId;
  autoFollowPanel?: WorkspaceLeftPanel;
  manualOverrideTabId: WorkspaceTabActivationId;
  showExplorerPanel: boolean;
}) {
  const normalizedActiveTabId = activeWorkspaceTabId ?? null;
  const normalizedManualOverrideTabId = manualOverrideTabId ?? null;
  const nextManualOverrideTabId =
    normalizedActiveTabId === normalizedManualOverrideTabId ? normalizedManualOverrideTabId : null;

  return {
    activeWorkspaceTabId: normalizedActiveTabId,
    manualOverrideTabId: nextManualOverrideTabId,
    shouldApplyAutoFollow: showExplorerPanel && !!autoFollowPanel && nextManualOverrideTabId === null,
  };
}

export function getDirectActiveTabLocateTarget(
  activeTab?: IWorkspaceTab | null,
): DirectActiveTabLocateTarget | null | undefined {
  if (!activeTab) {
    return null;
  }

  if (activeTab.type === WorkspaceTabType.CONSOLE) {
    return activeTab.uniqueData?.dataSourceId ? { surface: 'databaseTree' } : null;
  }

  if (activeTab.type === WorkspaceTabType.LocalSQLFile) {
    const filePath = activeTab.uniqueData?.filePath;
    return filePath ? { surface: 'localFile', filePath } : null;
  }

  return undefined;
}
