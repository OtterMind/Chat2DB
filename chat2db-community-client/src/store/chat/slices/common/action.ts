import { StateCreator } from 'zustand';
import { CommonState } from './initialState';
import { ChatStore } from '../../store';
import chatService from '@/service/chat';
import { ChatVO } from '@/typings/chat';
import i18n from '@/i18n';
import { staticMessage } from '@chat2db/ui';
import { useGlobalStore } from '@/store/global';
import { applyChatInfoUpdate, type ChatPage } from './chatInfoUpdate';

export interface CommonAction {
  /** Create fake/empty new chat conversation */
  createFakeNewChat: () => void;
  /** Create new chat */
  createNewChat: (chatInfo: ChatVO) => Promise<any>;
  updateInitChatInfo: (chatInfo: ChatVO) => void;
  /** Update basic information of Chat */
  updateChatInfo: (chatInfo: ChatVO) => void;
  /** Request Chat list */
  queryChatList: () => void;
  /** Set the current session */
  setCurrentChat: (chat: CommonState['currentChat'], lastQuestionId?: number) => Promise<any>;
  // Append list
  nextChatList: (lastQuestionId: number) => Promise<boolean>;
  /** Set session list */
  setChatList: (chatList: CommonState['chatList']) => void;
  /** Chat to request sharing */
  queryShareChat: (id: string, type: 'view' | 'edit') => void;
  /** Delete Chat */
  deleteChat: (id: number) => Promise<void>;
  /** setHandleSend */
  setHandleSend: (handleSend: CommonState['handleSend']) => void;
  /** Set the parameters that need to be automatically filled in the chat input box */
  setChatInputAutoFillParams: (params: CommonState['chatInputAutoFillParams']) => void;
}

export const createCommonAction: StateCreator<ChatStore, [['zustand/devtools', never]], [], CommonAction> = (
  set,
  get,
) => ({
  createFakeNewChat: () => {
    const page = useGlobalStore.getState().mainPageActiveTab;

    get().setCurrentChat({
      ...get().currentChat,
      [page]: null,
    });
  },
  createNewChat: (chatInfo) => {
    const page = useGlobalStore.getState().mainPageActiveTab;
    const currentChat = get().currentChat;

    return new Promise((resolve, reject) => {
      chatService
        .createNewChat(chatInfo)
        .then((res) => {
          if (res) {
            const chatList = get().chatList;
            get().setChatList([res, ...chatList]);
            get()
              .setCurrentChat({
                ...currentChat,
                [page]: res,
              })
              .then((chatWithDetails) => {
                get().setOpenSettingModal(false);
                resolve(chatWithDetails || res);
              })
              .catch(reject);
          }
        })
        .catch(reject);
    });
  },
  updateInitChatInfo: async (chatBasicInfo) => {
    const page = useGlobalStore.getState().mainPageActiveTab;

    set({
      currentChat: {
        ...get().currentChat,
        [page]: chatBasicInfo,
      },
      chatList: [chatBasicInfo, ...get().chatList],
    });
  },
  updateChatInfo: async (chatBasicInfo) => {
    const page = useGlobalStore.getState().mainPageActiveTab as ChatPage;
    chatService.updateChatInfo(chatBasicInfo).then(() => {
      set((state) => applyChatInfoUpdate(state, page, chatBasicInfo));
    });
  },
  queryChatList: async () => {
    const pageParams = get().chatListParams;
    const page = useGlobalStore.getState().mainPageActiveTab;
    const currentChat = get().currentChat;

    chatService
      .getChatList(pageParams)
      .then((res) => {
        if (res.data) {
          const newChatList = [...get().chatList, ...res.data];
          set({
            chatList: newChatList,
            chatListParams: { ...pageParams, pageNo: pageParams.pageNo + 1, hasNextPage: !!res.hasNextPage },
          });

          if (!currentChat?.[page]) {
            get().setCurrentChat({
              ...currentChat,
              [page]: newChatList[0],
            });
          }
        }
      })
      .catch(() => {
        set({
          chatList: [],
        });

        get().setCurrentChat({
          ...currentChat,
          [page]: null,
        });
      });
  },
  queryShareChat: async (id, type) => {
    let res;
    if (type === 'view') {
      res = await chatService.getChatShareViewDetail({ viewShareId: id });
    } else {
      res = await chatService.getChatShareEditDetail({ editShareId: id });
    }
    get().setCurrentChat(res);
  },

  setChatList: (chatList) => {
    set({ chatList: chatList });
  },
  setCurrentChat: (chat) => {
    const page = useGlobalStore.getState().mainPageActiveTab;
    get().resetChatDetails(page);
    const { currentChat } = get();

    if (chat[page]?.id) {
      const { id } = chat[page];
      set({ currentChat: { ...currentChat, [page]: chat } });
      return chatService
        .getChatDetailById({
          pageSize: 5,
          id: id!,
        })
        .then((res) => {
          if (res) {
            set({
              currentChat: {
                ...currentChat,
                [page]: res,
              },
            });
            get().initChatDetails(res.chatDetails);
            return res;
          }
        });
    } else {
      set({ currentChat: { ...currentChat, [page]: chat[page] } });
      get().initChatDetails([]);
      return Promise.resolve(undefined);
    }
  },
  nextChatList: (lastQuestionId) => {
    const page = useGlobalStore.getState().mainPageActiveTab;
    const id = get().currentChat?.[page]?.id;
    return new Promise((resolve) => {
      if (id) {
        chatService
          .getChatDetailById({
            lastQuestionId,
            pageSize: 5,
            id,
          })
          .then((res) => {
            if (!res.chatDetails?.length) {
              resolve(false);
              return;
            }
            resolve(true);
            if (res) {
              const { currentChat } = get();
              const _chatDetails = [...(res.chatDetails || []), ...(currentChat[page]?.chatDetails || [])];
              set({
                currentChat: {
                  ...currentChat,
                  [page]: {
                    ...currentChat[page],
                    chatDetails: _chatDetails,
                  },
                },
              });
              get().initChatDetails(_chatDetails);
            }
          });
      } else {
        resolve(false);
      }
    });
  },
  deleteChat: async (id) => {
    chatService.deleteChat({ id }).then((res) => {
      if (res) {
        // const chatList = get().chatList;
        const chatList = get().chatList.filter((i) => i.id !== id);
        const { currentChat } = get();
        const page = useGlobalStore.getState().mainPageActiveTab;
        if (currentChat?.[page]?.id === id) {
          get().setCurrentChat({
            ...get().currentChat,
            [page]: chatList[0],
          });
        }
        set({ chatList });
        staticMessage.success(i18n('common.text.successfullyDelete'));
      }
    });
  },
  setHandleSend: (handleSend) => {
    set({ handleSend });
  },
  setChatInputAutoFillParams: (params) => {
    set({ chatInputAutoFillParams: params });
  },
});
