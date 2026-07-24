import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings/workspace';

export type WorkspaceLeftPanel = 'explorer' | 'database';

export type ExplorerActiveTabLocateTarget =
  | {
      surface: 'explorerSession';
      sessionId: IWorkspaceTab['id'];
    }
  | {
      surface: 'localFile';
      filePath: string;
    };

export interface DirectActiveTabLocateTargets {
  explorer?: ExplorerActiveTabLocateTarget;
  database?: {
    surface: 'databaseTree';
  };
}

export function resolveWorkspaceLeftPanel(panel?: WorkspaceLeftPanel): WorkspaceLeftPanel {
  return panel || 'database';
}

export function getActiveTabLocateTargetForPanel<TExplorer, TDatabase>(
  targets: { explorer?: TExplorer; database?: TDatabase },
  panel: WorkspaceLeftPanel,
): TExplorer | TDatabase | undefined {
  return targets[panel];
}

export function getDirectActiveTabLocateTargets(
  activeTab?: IWorkspaceTab | null,
): DirectActiveTabLocateTargets | undefined {
  if (!activeTab) {
    return {};
  }

  if (activeTab.type === WorkspaceTabType.CONSOLE) {
    return {
      explorer: { surface: 'explorerSession', sessionId: activeTab.id },
      database: activeTab.uniqueData?.dataSourceId ? { surface: 'databaseTree' } : undefined,
    };
  }

  if (activeTab.type === WorkspaceTabType.LocalSQLFile) {
    const filePath = activeTab.uniqueData?.filePath;
    return {
      explorer: filePath ? { surface: 'localFile', filePath } : undefined,
    };
  }

  return undefined;
}
