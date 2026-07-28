import { WorkspaceStore } from '../../store';
import { StateCreator } from 'zustand';

export interface AIAction {
  addDefaultDataCollectionList: (param: { type: 'dashboard' | 'console' | 'chat'; id: number; value: number }) => void;
  increaseCreateAiDataCollectionTipsCount: () => void;
}

export const createAIAction: StateCreator<WorkspaceStore, [['zustand/devtools', never]], [], AIAction> = (
  set,
  get,
) => ({
  addDefaultDataCollectionList: ({ type, id, value }) => {
    try {
      const newDefaultDataCollectionList = get().defaultDataCollectionList;
      newDefaultDataCollectionList[type][id] = value;
      set({
        defaultDataCollectionList: newDefaultDataCollectionList,
      });
    } catch {
      const newDefaultDataCollectionList = {
        dashboard: {},
        console: {},
        chat: {},
        [type]: {
          [id]: value,
        },
      };
      set({
        defaultDataCollectionList: newDefaultDataCollectionList,
      });
      console.log('error');
    }
  },
  increaseCreateAiDataCollectionTipsCount: () => {
    set((state) => ({ createAiDataCollectionTipsCount: state.createAiDataCollectionTipsCount + 1 }));
  },
});
