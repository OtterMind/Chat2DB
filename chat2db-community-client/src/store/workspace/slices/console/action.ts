import type { StateCreator } from 'zustand/vanilla';
import { WorkspaceStore } from '../../store';
import { ConsoleState } from './initialState';
import { ICreateConsoleParams, IBoundInfo, IWorkspaceTab } from '@/typings';
import historyService from '@/service/history';
import { ConsoleOpenedStatus, ConsoleStatus, WorkspaceTabType } from '@/constants';

import { EditorSetValueType } from '@/components/SQLEditor';
import { useIndexDBStore } from '@/store/indexDB';
import { useZoerStore } from '@/store/zoer';
import { getPersistableActiveConsoleId } from '../../utils/workspaceTabPersistence';
import { executeSavedConsoleRemoval, resolveSavedConsoleRemoval } from '../../utils/savedConsoleLifecycle';

const RECENTLY_CLOSED_WORKSPACE_TAB_LIMIT = 20;

const getPaneIdForWorkspaceTab = (
  workspaceTabSplitLayout: ConsoleState['workspaceTabSplitLayout'],
  tabId: string | number,
) => {
  const paneTabIds = workspaceTabSplitLayout?.paneTabIds || {};
  return Object.keys(paneTabIds).find((paneId) => paneTabIds[paneId]?.includes(tabId));
};

const isSavedConsoleLikeWorkspaceTab = (item?: IWorkspaceTab | null) => {
  if (!item) {
    return false;
  }
  return (
    item.type === WorkspaceTabType.CONSOLE ||
    item.type === WorkspaceTabType.FUNCTION ||
    item.type === WorkspaceTabType.PROCEDURE ||
    item.type === WorkspaceTabType.TRIGGER ||
    item.type === WorkspaceTabType.VIEW ||
    item.type === ('table' as WorkspaceTabType) ||
    !item.type
  );
};

const stripFunctionValues = (value: any): any => {
  if (typeof value === 'function') {
    return undefined;
  }
  if (!value || typeof value !== 'object') {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(stripFunctionValues).filter((item) => item !== undefined);
  }
  return Object.fromEntries(
    Object.entries(value)
      .map(([key, item]) => [key, stripFunctionValues(item)])
      .filter(([, item]) => item !== undefined),
  );
};

const hasFunctionValue = (value: unknown): boolean => {
  if (typeof value === 'function') {
    return true;
  }
  if (!value || typeof value !== 'object') {
    return false;
  }
  if (Array.isArray(value)) {
    return value.some(hasFunctionValue);
  }
  return Object.values(value as Record<string, unknown>).some(hasFunctionValue);
};

export interface ConsoleAction {
  getOpenConsoleList: () => void;
  getSavedConsoleList: () => void;
  removeSavedConsole: (consoleId: number) => Promise<void>;
  setActiveConsoleId: (data: ConsoleState['activeConsoleId']) => void;
  setWorkspaceTabList: (data: ConsoleState['workspaceTabList']) => void;
  updateWorkspaceTabBoundInfo: (data: IBoundInfo) => void;
  createConsole: (params: ICreateConsoleParams) => Promise<any>;
  addWorkspaceTab: (params: any) => void;
  setEditorToList: (id: number | string, editorIns: any) => void;
  deleteEditor: (id: number | string) => void;
  appendConsole: (params: { id: number | string; content: string; type?: EditorSetValueType; space?: boolean }) => void;
  deleteActiveWorkspaceTab: () => void;
}

export const createConsoleAction: StateCreator<WorkspaceStore, [['zustand/devtools', never]], [], ConsoleAction> = (
  set,
  get,
) => ({
  getOpenConsoleList: () => {
    const zoerBoundInfo = useZoerStore.getState().zoerBoundInfo;
    const params: any = {
      tabOpened: 'y',
      pageNo: 1,
      pageSize: 20,
    };
    if (zoerBoundInfo) {
      params['dataSourceId'] = zoerBoundInfo.dataSourceId;
      params['databaseName'] = zoerBoundInfo.databaseName;
      params['schemaName'] = zoerBoundInfo.schemaName;
    }
    historyService.getConsoleList(params).then((res) => {
      set({
        consoleList: res?.data || [],
      });
    });
  },
  getSavedConsoleList: () => {
    historyService
      .getConsoleList({
        pageNo: 1,
        pageSize: 100,
        status: ConsoleStatus.RELEASE,
        orderByDesc: true,
      })
      .then((res) => {
        set({
          savedConsoleList: res?.data,
        });
      });
  },
  removeSavedConsole: async (consoleId) => {
    const removalPlan = resolveSavedConsoleRemoval(get().workspaceTabList, consoleId);
    await executeSavedConsoleRemoval(consoleId, removalPlan, {
      updateSavedConsole: historyService.updateSavedConsole,
      deleteSavedConsole: historyService.deleteSavedConsole,
      updateWorkspaceTabBoundInfo: get().updateWorkspaceTabBoundInfo,
    });
    get().getSavedConsoleList();
  },
  setActiveConsoleId: (data) => {
    set({
      activeConsoleId: data,
    });
  },
  setWorkspaceTabList: (data) => {
    set({
      workspaceTabList: data,
      activeConsoleId: getPersistableActiveConsoleId({
        activeConsoleId: get().activeConsoleId,
        workspaceTabList: data,
      }),
    });
  },
  createConsole: (params) => {
    const workspaceTabList = get().workspaceTabList;
    const currentConnectionDetails = get().currentConnectionDetails;
    let name = params.name || `${[params.databaseName || params.schemaName].filter(Boolean).join('-')}`;
    if (params.dataSourceName) {
      name = name + `[${params.dataSourceName}]`;
    }
    const newConsole = {
      ...params,
      name,
      ddl: params.ddl || '',
      status: ConsoleStatus.DRAFT,
      operationType: params.operationType || WorkspaceTabType.CONSOLE,
      type: params.databaseType,
      supportDatabase: currentConnectionDetails?.supportDatabase,
      supportSchema: currentConnectionDetails?.supportSchema,
    };

    return new Promise((resolve) => {
      // if ((workspaceTabList?.length || 0) >= 100) {
      //   message.warning(i18n('workspace.tips.maxConsole'));
      //   return;
      // }
      set({ createConsoleLoading: true });
      historyService
        .createConsole(newConsole)
        .then((res) => {
          const newList = [
            ...(workspaceTabList || []),
            {
              id: res,
              title: newConsole.name,
              type: newConsole.operationType,
              uniqueData: {
                ...newConsole,
                consoleId: res,
              },
            },
          ];

          get().setWorkspaceTabList(newList);
          get().setActiveConsoleId(res);
          resolve(res);
        })
        .finally(() => {
          set({ createConsoleLoading: false });
        });
    });
  },
  addWorkspaceTab: (params) => {
    const workspaceTabList = get().workspaceTabList;
    if (workspaceTabList?.length && workspaceTabList.findIndex((item) => item?.id === params?.id) !== -1) {
      get().setActiveConsoleId(params.id);
      return;
    }

    const newList = [...(workspaceTabList || []), params];
    get().setWorkspaceTabList(newList);
    get().setActiveConsoleId(params.id);
  },
  setEditorToList: (id, editorIns) => {
    const editorList = get().editorList;
    set({
      editorList: {
        ...editorList,
        [id]: editorIns,
      },
    });
  },
  deleteEditor: (id) => {
    try {
      const editorList = get().editorList;
      if (id && editorList && editorList[id]) {
        const newEditorList = { ...editorList };
        delete newEditorList[id];
        set({
          editorList: newEditorList,
        });
      }
    } catch (_error) {
      console.error('deleteEditor error');
    }
  },
  appendConsole: ({ id, content, type, space }) => {
    const { editorList } = get();
    const editorRef = editorList[id];
    if (!editorRef) return;
    // If space is true and there is content before it, add a newline character in front of the content
    if (space) {
      const value = editorRef.getValue();
      if (value) {
        editorRef.setValue('\n', type);
      }
    }
    editorRef.setValue(content, type);
  },
  updateWorkspaceTabBoundInfo: (data) => {
    const workspaceTabList = get().workspaceTabList;
    if (!workspaceTabList) {
      return;
    }

    const targetWorkspaceTabId = data.workspaceTabId;
    const targetConsoleId = data.consoleId;
    const newList = workspaceTabList.map((item) => {
      const matchedByWorkspaceTabId = targetWorkspaceTabId !== undefined && item.id === targetWorkspaceTabId;
      const matchedByConsoleId =
        targetConsoleId !== undefined &&
        (item.uniqueData?.consoleId === targetConsoleId || item.id === targetConsoleId);
      if (matchedByWorkspaceTabId || matchedByConsoleId) {
        return {
          ...item,
          uniqueData: {
            ...item.uniqueData,
            ...data,
          },
        };
      }
      return item;
    });

    get().setWorkspaceTabList(newList);
  },
  deleteActiveWorkspaceTab: async () => {
    const { activeConsoleId, workspaceTabList, workspaceTabSplitLayout, editorList } = get();
    if (!activeConsoleId || !workspaceTabList) {
      return;
    }

    const activeWorkspaceTab = workspaceTabList.find((item) => item?.id === activeConsoleId);
    if (activeWorkspaceTab?.pinned) {
      return;
    }

    // Delete editor instance
    if (editorList && editorList[activeConsoleId]) {
      get().deleteEditor(activeConsoleId);
    }

    // Remove from tag list
    const removedWorkspaceTab = workspaceTabList.find((item) => item?.id === activeConsoleId);
    const newList = workspaceTabList.filter((item) => item?.id !== activeConsoleId);

    const savedConsoleId = removedWorkspaceTab?.uniqueData?.consoleId ?? removedWorkspaceTab?.id;
    if (isSavedConsoleLikeWorkspaceTab(removedWorkspaceTab) && typeof savedConsoleId === 'number') {
      await historyService.updateSavedConsole({
        id: savedConsoleId,
        tabOpened: ConsoleOpenedStatus.NOT_OPEN,
      });

      const { deleteValue } = useIndexDBStore.getState();
      deleteValue(String(savedConsoleId));
    }

    // Set new activation tag
    let newActiveId = newList.length > 0 ? newList[newList.length - 1].id : null;
    let nextWorkspaceTabSplitLayout = workspaceTabSplitLayout;
    if (workspaceTabSplitLayout) {
      const activePaneId = getPaneIdForWorkspaceTab(workspaceTabSplitLayout, activeConsoleId);
      if (activePaneId) {
        const paneTabIds = workspaceTabSplitLayout.paneTabIds[activePaneId] || [];
        const currentIndex = paneTabIds.findIndex((id) => id === activeConsoleId);
        const nextPaneTabIds = paneTabIds.filter((id) => id !== activeConsoleId);
        newActiveId =
          nextPaneTabIds[Math.max(0, currentIndex - 1)] ??
          nextPaneTabIds[currentIndex] ??
          newList[newList.length - 1]?.id ??
          null;
        nextWorkspaceTabSplitLayout = {
          ...workspaceTabSplitLayout,
          activePane: activePaneId,
          paneTabIds: {
            ...workspaceTabSplitLayout.paneTabIds,
            [activePaneId]: nextPaneTabIds,
          },
          activeTabIds: {
            ...workspaceTabSplitLayout.activeTabIds,
            [activePaneId]: newActiveId,
          },
        };
      }
    }

    // Add deleted console to history list
    if (removedWorkspaceTab && !hasFunctionValue(removedWorkspaceTab.uniqueData)) {
      const recentlyClosedWorkspaceTabs = get().recentlyClosedWorkspaceTabs || [];
      const currentEditorValue = editorList?.[removedWorkspaceTab.id]?.getValue?.();
      const uniqueData = removedWorkspaceTab.uniqueData
        ? stripFunctionValues(removedWorkspaceTab.uniqueData)
        : undefined;
      if (uniqueData && currentEditorValue !== undefined) {
        uniqueData.ddl = currentEditorValue;
      }
      set({
        recentlyClosedWorkspaceTabs: [
          {
            ...removedWorkspaceTab,
            pinned: false,
            uniqueData,
          },
          ...recentlyClosedWorkspaceTabs,
        ].slice(0, RECENTLY_CLOSED_WORKSPACE_TAB_LIMIT),
      });
    }

    set({ workspaceTabSplitLayout: nextWorkspaceTabSplitLayout });
    get().setWorkspaceTabList(newList);
    get().setActiveConsoleId(newActiveId);
  },
});
