import { clientRuntime } from '@client-runtime';
import { StateCreator } from 'zustand';
import { devtools, persist, PersistOptions } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';
import { AIState, initialState } from './initialState';
import { CascaderAction, createCascaderAction } from './slices/cascader/action';
import { CascaderState, initCascaderState } from './slices/cascader/initialState';
import { createModelAction, ModelAction } from './slices/model/action';
import { initModelState, ModelState, SelectedModelOption } from './slices/model/initialState';
import { createPanelAction, PanelAction } from './slices/panel/action';

export type AIAction = PanelAction & ModelAction & CascaderAction;
export type AIStore = AIState & AIAction & ModelState & CascaderState;

const createStore: StateCreator<AIStore, [['zustand/devtools', never]], [], AIStore> = (...parameters) => ({
  ...initialState,
  ...createPanelAction(...parameters),
  ...initModelState,
  ...createModelAction(...parameters),
  ...initCascaderState,
  ...createCascaderAction(...parameters),
});

type GlobalPersist = Pick<AIStore, 'size'>;

const persistOptions: PersistOptions<AIStore, GlobalPersist> = {
  name: clientRuntime.aiStoreName,
  version: 3,
  partialize: (state) => ({
    size: state.size,
    selectedModel: state.selectedModel,
    cascaderDataMap: state.cascaderDataMap,
    showPanel: state.showPanel,
  }),
  migrate: (persistedState: any) => {
    const nextState = { ...(persistedState || {}) };
    if (typeof nextState.selectedModel === 'string') {
      const selectedValue = nextState.selectedModel.trim();
      nextState.selectedModel = selectedValue
        ? ({
            value: selectedValue,
            label: '',
          } as SelectedModelOption)
        : null;
    }
    if (nextState.cascaderDataMap) {
      nextState.cascaderDataMap = {
        ...nextState.cascaderDataMap,
        stream: null,
      };
    }
    return nextState;
  },
};

export const useAIStore = createWithEqualityFn<AIStore>()(
  persist(
    devtools(createStore, {
      name: clientRuntime.aiStoreName,
    }),
    persistOptions,
  ),
  shallow,
);
