import { clientRuntime } from '@client-runtime';
import { PersistOptions, devtools, persist } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';
import { StateCreator } from 'zustand/vanilla';
import { WorkspaceState, initialState } from './initialState';
import { CommonAction, createCommonAction } from './slices/common/action';
import { ConfigAction, createConfigAction } from './slices/config/action';
import { ConsoleAction, createConsoleAction } from './slices/console/action';
import { ModalAction, createModalAction } from './slices/modal/action';
import {
  getPersistableActiveConsoleId,
  getHydratedWorkspaceLayout,
  getPersistableWorkspaceLayout,
  getPersistableWorkspaceTabList,
} from './utils/workspaceTabPersistence';

type WorkspaceAction = CommonAction & ConfigAction & ConsoleAction & ModalAction;
export type WorkspaceStore = WorkspaceState & WorkspaceAction;

const createStore: StateCreator<WorkspaceStore, [['zustand/devtools', never]]> = (...parameters) => ({
  ...initialState,
  ...createCommonAction(...parameters),
  ...createConfigAction(...parameters),
  ...createConsoleAction(...parameters),
  ...createModalAction(...parameters),
});

type GlobalPersist = Pick<
  WorkspaceStore,
  | 'layout'
  | 'currentConnectionDetails'
  | 'workspaceTabList'
  | 'workspaceTabSplitLayout'
  | 'activeConsoleId'
  | 'recentlyClosedWorkspaceTabs'
>;

// local-storage Options
const persistOptions: PersistOptions<WorkspaceStore, GlobalPersist> = {
  name: clientRuntime.workspaceStoreName,
  partialize: (state) => {
    const workspaceTabList = getPersistableWorkspaceTabList(state.workspaceTabList);
    return {
      layout: getPersistableWorkspaceLayout(state.layout),
      currentConnectionDetails: state.currentConnectionDetails,
      workspaceTabList,
      workspaceTabSplitLayout: state.workspaceTabSplitLayout,
      activeConsoleId: getPersistableActiveConsoleId({
        activeConsoleId: state.activeConsoleId,
        workspaceTabList,
      }),
      recentlyClosedWorkspaceTabs: getPersistableWorkspaceTabList(state.recentlyClosedWorkspaceTabs) || [],
    };
  },
  merge: (persistedState, currentState) => {
    const storedState = (persistedState || {}) as Partial<GlobalPersist>;
    return {
      ...currentState,
      ...storedState,
      layout: getHydratedWorkspaceLayout(currentState.layout, storedState.layout),
    };
  },
};

export const useWorkspaceStore = createWithEqualityFn<WorkspaceStore>()(
  persist(
    devtools(createStore, {
      name: clientRuntime.workspaceStoreName,
    }),
    persistOptions,
  ),
  shallow,
);

export const clearWorkspaceStore = () => {
  useWorkspaceStore.setState(initialState);
};
