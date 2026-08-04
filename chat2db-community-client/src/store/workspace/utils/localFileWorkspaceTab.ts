import type { IBoundInfo, IWorkspaceTab } from '@/typings/workspace';

export function refreshLocalFileWorkspaceTab(
  workspaceTabList: IWorkspaceTab[] | null | undefined,
  filePath: string,
  nextUniqueData: IBoundInfo,
) {
  const existingTab = workspaceTabList?.find((tab) => tab.uniqueData?.filePath === filePath);
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
