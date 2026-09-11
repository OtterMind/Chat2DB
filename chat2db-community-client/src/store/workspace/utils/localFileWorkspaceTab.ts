import type { IBoundInfo, IWorkspaceTab } from '@/typings/workspace';
import { WorkspaceTabType } from '@/constants/workspace';

export function getRestoredLocalFileReadRequest(tab: IWorkspaceTab) {
  const file = tab.uniqueData;
  if (tab.type !== WorkspaceTabType.LocalSQLFile || !file?.filePath || file.ddl !== undefined) {
    return undefined;
  }
  return {
    filePath: file.filePath,
    fileExtension: file.fileExtension,
    context: {
      rootToken: file.fileRootToken,
      relativePath: file.fileRelativePath,
      charset: file.fileCharset,
      workspaceTabId: tab.id,
    },
  };
}

export function refreshLocalFileWorkspaceTab(
  workspaceTabList: IWorkspaceTab[] | null | undefined,
  filePath: string,
  nextUniqueData: IBoundInfo,
  targetWorkspaceTabId?: string | number,
) {
  const existingTab = workspaceTabList?.find(
    (tab) =>
      tab.uniqueData?.filePath === filePath &&
      (targetWorkspaceTabId === undefined || tab.id === targetWorkspaceTabId),
  );
  if (!workspaceTabList || !existingTab) {
    return undefined;
  }

  return {
    activeTabId: existingTab.id,
    workspaceTabList: workspaceTabList.map((tab) =>
      tab.id === existingTab.id
        ? {
            ...tab,
            uniqueData: {
              ...tab.uniqueData,
              ...nextUniqueData,
              fileExtension: nextUniqueData.fileExtension || tab.uniqueData?.fileExtension,
            },
          }
        : tab,
    ),
  };
}
