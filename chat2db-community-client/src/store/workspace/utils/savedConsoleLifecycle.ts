import { ConsoleStatus } from '@/constants/common';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings';
import {
  savedConsoleMutationCoordinator,
  type SavedConsoleMutationCoordinator,
  type SavedConsoleMutationResult,
} from './savedConsoleMutationCoordinator';

export type SavedConsoleRemovalPlan = { action: 'keepDraft'; workspaceTabId: string | number } | { action: 'delete' };

export function resolveSavedConsoleRemoval(
  workspaceTabList: IWorkspaceTab[] | null,
  consoleId: number,
): SavedConsoleRemovalPlan {
  const openTab = workspaceTabList?.find((item) => {
    const itemConsoleId = item.uniqueData?.consoleId ?? item.id;
    return item.type === WorkspaceTabType.CONSOLE && itemConsoleId === consoleId;
  });

  return openTab ? { action: 'keepDraft', workspaceTabId: openTab.id } : { action: 'delete' };
}

interface SavedConsoleRemovalDependencies {
  updateSavedConsole: (params: { id: number; status: ConsoleStatus }) => Promise<unknown>;
  deleteSavedConsole: (params: { id: number }) => Promise<unknown>;
  updateWorkspaceTabBoundInfo: (params: {
    workspaceTabId: string | number;
    consoleId: number;
    status: ConsoleStatus;
  }) => void;
}

export async function executeSavedConsoleRemoval(
  consoleId: number,
  plan: SavedConsoleRemovalPlan,
  dependencies: SavedConsoleRemovalDependencies,
  coordinator: SavedConsoleMutationCoordinator = savedConsoleMutationCoordinator,
): Promise<SavedConsoleMutationResult<unknown>> {
  const result = await coordinator.remove(consoleId, plan.action === 'keepDraft', async () => {
    if (plan.action === 'keepDraft') {
      return dependencies.updateSavedConsole({
        id: consoleId,
        status: ConsoleStatus.DRAFT,
      });
    }
    return dependencies.deleteSavedConsole({ id: consoleId });
  });

  if (result.current && plan.action === 'keepDraft') {
    dependencies.updateWorkspaceTabBoundInfo({
      workspaceTabId: plan.workspaceTabId,
      consoleId,
      status: ConsoleStatus.DRAFT,
    });
  }

  return result;
}
