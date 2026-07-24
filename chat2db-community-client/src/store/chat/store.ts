import { devtools } from 'zustand/middleware';
import { ChatState, initialState } from './initialState';
import { CommonAction, createCommonAction } from './slices/common/action';
import { SettingAction, createSettingAction } from './slices/setting/action';
import { ShareAction, createShareAction } from './slices/share/action';
import { createWithEqualityFn } from 'zustand/traditional';
import { StateCreator } from 'zustand';
import { AIAction, createAIAction } from './slices/ai/action';
import { ChatDetailAction, createChatDetailAction } from './slices/chatDetails/action';
import { shallow } from 'zustand/shallow';

export type ChatAction = CommonAction & SettingAction & ShareAction & AIAction & ChatDetailAction;
export type ChatStore = ChatState & ChatAction;

const createStore: StateCreator<ChatStore, [['zustand/devtools', never]]> = (...parameters) => ({
  ...initialState,
  ...createCommonAction(...parameters),
  ...createSettingAction(...parameters),
  ...createShareAction(...parameters),
  ...createAIAction(...parameters),
  ...createChatDetailAction(...parameters),
});

export const useChatStore = createWithEqualityFn<ChatStore>()(
  devtools(createStore, {
    name: 'Chat2DB_Chat_Store',
  }),
  shallow,
);

export { shallow };

// Clean store
export const clearChatStore = () => {
  useChatStore.setState(initialState);
};
