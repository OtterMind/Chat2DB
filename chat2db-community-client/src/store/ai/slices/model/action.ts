import { StateCreator } from 'zustand';
import { AIStore } from '../../store';
import aiStreamService from '@/service/aiStream';
import { SelectedModelOption } from './initialState';

export interface ModelAction {
  getModelList: () => Promise<void>;
  setModelList: (modelList: Array<{ label: string; value: string; isDefault: boolean }>) => void;
  setSelectedModel: (model: SelectedModelOption | null) => void;
}

export const createModelAction: StateCreator<AIStore, [['zustand/devtools', never]], [], ModelAction> = (set, get) => ({
  getModelList: async () => {
    const options = await aiStreamService.getModelOptions();
    const modelList = (options || []).map((option) => ({
      label: option.label,
      value: option.value,
      isDefault: !!option.defaultOption,
    }));

    set({ modelList });

    // Set default model if exists
    if (!get().selectedModel) {
      const defaultModel = modelList.find((i) => i.isDefault);
      if (defaultModel) {
        set({
          selectedModel: {
            value: defaultModel.value,
            label: defaultModel.label,
          },
        });
      }
    }
  },

  setModelList: (modelList) => {
    set({ modelList });
  },

  setSelectedModel: (model) => {
    set({ selectedModel: model });
  },
});
