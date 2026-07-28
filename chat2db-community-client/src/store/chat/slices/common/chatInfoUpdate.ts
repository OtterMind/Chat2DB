import type { ChatVO } from '@/typings/chat';

export type ChatPage = 'workspace' | 'dashboard' | 'chat';

export interface ChatInfoState {
  currentChat: Record<ChatPage, ChatVO | null>;
  chatList: ChatVO[];
}

export function applyChatInfoUpdate(state: ChatInfoState, page: ChatPage, chatBasicInfo: ChatVO): ChatInfoState {
  if (chatBasicInfo.id === undefined) {
    return state;
  }

  const chatList = state.chatList.map((item) =>
    item.id === chatBasicInfo.id
      ? {
          ...item,
          ...chatBasicInfo,
        }
      : item,
  );
  const activeChat = state.currentChat[page];
  const currentChat =
    activeChat?.id === chatBasicInfo.id
      ? {
          ...state.currentChat,
          [page]: {
            ...activeChat,
            ...chatBasicInfo,
          },
        }
      : state.currentChat;

  return {
    chatList,
    currentChat,
  };
}
