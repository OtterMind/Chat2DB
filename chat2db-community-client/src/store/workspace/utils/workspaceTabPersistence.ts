import { WorkspaceTabType } from '@/constants/workspace';
import { IWorkspaceTab } from '@/typings/workspace';

/**
 * Maximum number of workspace tabs persisted to localStorage. The in-memory
 * list is unaffected; capping the persisted count limits storage growth and
 * reduces quota risk, but does not bound the size of an individual tab payload.
 * On reload, tabs beyond this cap simply do not restore. The most recent tabs
 * are kept.
 */
const MAX_PERSISTED_TABS = 100;

function capPersistedTabs(tabs: IWorkspaceTab[]): IWorkspaceTab[] {
  return tabs.length > MAX_PERSISTED_TABS ? tabs.slice(-MAX_PERSISTED_TABS) : tabs;
}

export function getPersistableWorkspaceTabList(workspaceTabList?: IWorkspaceTab[] | null) {
  if (!workspaceTabList?.length) {
    return workspaceTabList || null;
  }

  const persistableTabs = workspaceTabList.filter(
    (tab) => tab.type !== WorkspaceTabType.Terminal && !tab.uniqueData?.filePreviewMimeType,
  );
  const cappedTabs = capPersistedTabs(persistableTabs);

  try {
    return JSON.parse(
      JSON.stringify(cappedTabs, (_key, value) => {
        if (typeof value === 'function') {
          return undefined;
        }
        return value;
      }),
    ) as IWorkspaceTab[];
  } catch {
    return cappedTabs.map((tab) => ({
      id: tab.id,
      type: tab.type,
      title: tab.title,
      uniqueData: tab.uniqueData
        ? Object.fromEntries(Object.entries(tab.uniqueData).filter(([, value]) => typeof value !== 'function'))
        : undefined,
    }));
  }
}

export function getPersistableActiveConsoleId(params: {
  activeConsoleId?: string | number | null;
  workspaceTabList?: IWorkspaceTab[] | null;
}) {
  const { activeConsoleId, workspaceTabList } = params;
  if (!workspaceTabList?.length) {
    return null;
  }
  if (workspaceTabList.some((tab) => tab.id === activeConsoleId)) {
    return activeConsoleId || null;
  }
  return workspaceTabList[0].id;
}
