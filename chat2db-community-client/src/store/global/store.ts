import { clientRuntime } from '@client-runtime';
import { registerI18nStateReader } from '@/i18n/runtime';
import { PersistOptions, devtools, persist } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';
import { StateCreator } from 'zustand/vanilla';
import { GlobalState, initialState } from './initialState';
import { CommonAction, createCommonAction } from './slices/common/action';
import { HotUpdateAction, createHotUpdateAction } from './slices/hotUpdate/action';
import { MiscAction, createMiscAction } from './slices/misc/action';
import { RequestAction, createRequestAction } from './slices/request/action';
import { SettingsAction, createSettingsAction } from './slices/settings/action';

export type GlobalStore = GlobalState & CommonAction & SettingsAction & RequestAction & MiscAction & HotUpdateAction;

const createStore: StateCreator<GlobalStore, [['zustand/devtools', never]]> = (...parameters) => ({
  ...initialState,
  ...createCommonAction(...parameters),
  ...createSettingsAction(...parameters),
  ...createRequestAction(...parameters),
  ...createMiscAction(...parameters),
  ...createHotUpdateAction(...parameters),
});

type GlobalPersist = Pick<
  GlobalStore,
  | 'mainPageActiveTab'
  | 'baseSetting'
  | 'hotUpdateConfig'
  | 'editorSettings'
  | 'dataTableSettings'
  | 'shortcutOverrides'
  | 'workspaceAiIntroDismissed'
  | 'terminalSettings'
>;

// local-storage Options
const persistOptions: PersistOptions<GlobalStore, GlobalPersist> = {
  name: clientRuntime.globalStoreName,
  version: 1,
  migrate: (persistedState, version) => {
    const persisted = persistedState as GlobalPersist;
    // Auto-update was previously hidden in Community, so old defaults are not
    // consent to contact the GitHub Release update service.
    if (clientRuntime.runtimeKey === 'community' && version < 1) {
      persisted.hotUpdateConfig = {
        ...persisted.hotUpdateConfig,
        remindMe: false,
        autoDownload: false,
        autoInstall: false,
      };
    }
    return persisted;
  },
  partialize: (state) => ({
    mainPageActiveTab: state.mainPageActiveTab,
    baseSetting: state.baseSetting,
    hotUpdateConfig: state.hotUpdateConfig,
    editorSettings: state.editorSettings,
    dataTableSettings: state.dataTableSettings,
    shortcutOverrides: state.shortcutOverrides,
    workspaceAiIntroDismissed: state.workspaceAiIntroDismissed,
    terminalSettings: state.terminalSettings,
  }),
};

export const useGlobalStore = createWithEqualityFn<GlobalStore>()(
  persist(
    devtools(createStore, {
      name: clientRuntime.globalStoreName,
    }),
    persistOptions,
  ),
  shallow,
);

registerI18nStateReader(() => {
  const state = useGlobalStore.getState();
  return {
    language: state.baseSetting.language,
    isCN: state.appConfig.isCN,
  };
});

export const clearGlobalStore = () => {
  useGlobalStore.setState({
    ...initialState,
    baseSetting: useGlobalStore.getState().baseSetting,
    systemErrorMessageApi: useGlobalStore.getState().systemErrorMessageApi,
    serviceStatus: useGlobalStore.getState().serviceStatus,
    appConfig: useGlobalStore.getState().appConfig,
    appUrlConfig: useGlobalStore.getState().appUrlConfig,
    editorSettings: useGlobalStore.getState().editorSettings,
    shortcutOverrides: useGlobalStore.getState().shortcutOverrides,
    terminalSettings: useGlobalStore.getState().terminalSettings,
  });
};
