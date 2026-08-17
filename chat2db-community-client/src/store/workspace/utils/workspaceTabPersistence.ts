import { WorkspaceTabType } from '@/constants/workspace';
import { IWorkspaceTab } from '@/typings/workspace';
import { initConfigState, type ConfigState } from '../slices/config/initialState';

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

function createPersistenceReplacer() {
  const ancestors: unknown[] = [];

  return function (this: unknown, _key: string, value: unknown) {
    if (typeof value === 'function' || typeof value === 'bigint') {
      return undefined;
    }
    if (typeof Node !== 'undefined' && value instanceof Node) {
      return undefined;
    }
    if (value !== null && typeof value === 'object') {
      while (ancestors.length > 0 && ancestors[ancestors.length - 1] !== this) {
        ancestors.pop();
      }
      if (ancestors.includes(value)) {
        return undefined;
      }
      ancestors.push(value);
    }
    return value;
  };
}

export function getPersistableWorkspaceLayout(layout: ConfigState['layout']): ConfigState['layout'] {
  const normalizedPanelLeftWidth =
    typeof layout.panelLeftWidth === 'number' && Number.isFinite(layout.panelLeftWidth)
      ? Math.max(0, layout.panelLeftWidth)
      : initConfigState.layout.panelLeftWidth;
  const panelLeftWidth = layout.panelLeft === false ? 0 : normalizedPanelLeftWidth;
  const panelRightWidth =
    typeof layout.panelRightWidth === 'number' && Number.isFinite(layout.panelRightWidth)
      ? Math.max(0, layout.panelRightWidth)
      : initConfigState.layout.panelRightWidth;

  return {
    panelLeft: panelLeftWidth > 0,
    panelLeftWidth,
    panelRight: typeof layout.panelRight === 'boolean' ? layout.panelRight : false,
    panelRightWidth,
  };
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
    return JSON.parse(JSON.stringify(cappedTabs, createPersistenceReplacer())) as IWorkspaceTab[];
  } catch {
    return cappedTabs.map((tab) => ({
      id: tab.id,
      type: tab.type,
      title: tab.title,
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
