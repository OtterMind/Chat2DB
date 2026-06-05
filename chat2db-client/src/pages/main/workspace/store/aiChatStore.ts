import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { v4 as uuidv4 } from 'uuid';
import aiConversationService from '@/service/aiConversation';
import { IAiConversation, IAiMessage } from '@/typings/aiConversation';
import { createIndexedDbStorage } from '@/utils/indexedDbStorage';

export type ChatStateType =
  | 'IDLE'
  | 'AUTO_SELECTING_TABLES'
  | 'FETCHING_TABLE_SCHEMA'
  | 'EXECUTING_EXPLAIN'
  | 'BUILDING_PROMPT'
  | 'STREAMING'
  | 'COMPLETED'
  | 'FAILED';

export interface IChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  thinking?: string;
  promptType?: string;
  sqlExtracted?: string | null;
  sequenceNo?: number;
}

export interface AiChatSession {
  sessionId: string;
  state: ChatStateType;
  messages: IChatMessage[];
  currentContent: string;
  currentThinking: string;
  selectedTables?: string[];
  schemaInfo?: string;
  explainResult?: { sql: string; plan: string[][]; formatted: string; success: boolean };
  error?: string;
  dataSourceId?: number | null;
  databaseName?: string | null;
  schemaName?: string | null;
  title?: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface IConversationSummary {
  conversationId: string;
  title?: string | null;
  dataSourceId?: number | null;
  dataSourceName?: string | null;
  databaseName?: string | null;
  schemaName?: string | null;
  messageCount: number;
  lastMessagePreview?: string | null;
  status: 'ACTIVE' | 'ARCHIVED' | 'DELETED';
  gmtCreate?: string;
  gmtModified?: string;
}

interface ILastRequest {
  message: string;
  promptType: string;
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string | null;
  tableNames?: string[] | null;
  ext?: string;
  conversationId?: string;
  history?: string;
  previousSql?: string | null;
  isRevision?: boolean;
}

interface IAiChatStore {
  sessions: Record<string, AiChatSession>;
  currentSessionId: string | null;
  lastRequest: ILastRequest | null;
  conversationList: IConversationSummary[];
  conversationListLoading: boolean;
  conversationListHasMore: boolean;
  conversationListPageNo: number;

  createSession: (sessionId: string, initial?: Partial<AiChatSession>) => void;
  updateState: (sessionId: string, state: ChatStateType) => void;
  appendContent: (sessionId: string, content: string, thinking?: string) => void;
  addMessage: (sessionId: string, message: IChatMessage) => void;
  setSelectedTables: (sessionId: string, tables: string[]) => void;
  setSchemaInfo: (sessionId: string, ddl: string) => void;
  setExplainResult: (sessionId: string, explain: AiChatSession['explainResult']) => void;
  setError: (sessionId: string, error: string) => void;
  setLastRequest: (req: ILastRequest) => void;
  clearSession: (sessionId: string) => void;
  resetCurrentContent: (sessionId: string) => void;
  renameSession: (sessionId: string, title: string) => Promise<void>;
  deleteSession: (sessionId: string) => Promise<void>;
  switchToSession: (sessionId: string) => void;
  startNewConversation: (boundInfo?: {
    dataSourceId?: number | null;
    databaseName?: string | null;
    schemaName?: string | null;
  }) => string;
  loadConversationList: (reset?: boolean) => Promise<void>;
  loadConversationDetail: (conversationId: string) => Promise<void>;
  syncLocalSessionFromDetail: (conversationId: string, conversation: IAiConversation, messages: IAiMessage[]) => void;
}

const generateTitleFromMessage = (message: string): string => {
  if (!message) return '新对话';
  const trimmed = message.trim().replace(/\s+/g, ' ');
  return trimmed.length > 20 ? trimmed.substring(0, 20) + '...' : trimmed;
};

const persistStorage = createJSONStorage(() => createIndexedDbStorage('aiChatStore', 'current'));

export const useAiChatStore = create<IAiChatStore>()(
  persist(
    (set, get) => ({
      sessions: {},
      currentSessionId: null,
      lastRequest: null,
      conversationList: [],
      conversationListLoading: false,
      conversationListHasMore: false,
      conversationListPageNo: 0,

      createSession: (sessionId: string, initial?: Partial<AiChatSession>) => {
        set((state) => {
          const now = Date.now();
          const newSession: AiChatSession = {
            sessionId,
            state: 'IDLE',
            messages: [],
            currentContent: '',
            currentThinking: '',
            createdAt: now,
            updatedAt: now,
            ...initial,
          };
          const existsInList = state.conversationList.some((item) => item.conversationId === sessionId);
          const conversationList = existsInList
            ? state.conversationList
            : [
                {
                  conversationId: sessionId,
                  title: newSession.title ?? '新对话',
                  dataSourceId: newSession.dataSourceId ?? null,
                  dataSourceName: null,
                  databaseName: newSession.databaseName ?? null,
                  schemaName: newSession.schemaName ?? null,
                  messageCount: newSession.messages.length,
                  lastMessagePreview: null,
                  status: 'ACTIVE' as const,
                },
                ...state.conversationList,
              ];
          return {
            sessions: { ...state.sessions, [sessionId]: newSession },
            conversationList,
            currentSessionId: sessionId,
          };
        });
      },

      updateState: (sessionId: string, newState: ChatStateType) => {
        set((state) => {
          const session = state.sessions[sessionId];
          if (!session) {
            return state;
          }
          return {
            sessions: {
              ...state.sessions,
              [sessionId]: { ...session, state: newState, updatedAt: Date.now() },
            },
          };
        });
      },

      appendContent: (sessionId: string, content: string, thinking?: string) => {
        set((state) => {
          const session = state.sessions[sessionId];
          if (!session) {
            return state;
          }
          return {
            sessions: {
              ...state.sessions,
              [sessionId]: {
                ...session,
                currentContent: session.currentContent + (content || ''),
                currentThinking: session.currentThinking + (thinking || ''),
                updatedAt: Date.now(),
              },
            },
          };
        });
      },

      addMessage: (sessionId: string, message: IChatMessage) => {
        set((state) => {
          const session = state.sessions[sessionId];
          if (!session) {
            return state;
          }
          return {
            sessions: {
              ...state.sessions,
              [sessionId]: {
                ...session,
                messages: [...session.messages, message],
                updatedAt: Date.now(),
              },
            },
            conversationList: state.conversationList.map((item) =>
              item.conversationId === sessionId
                ? {
                    ...item,
                    messageCount: item.messageCount + 1,
                    lastMessagePreview: message.content,
                  }
                : item,
            ),
          };
        });
      },

      setSelectedTables: (sessionId: string, tables: string[]) => {
        set((state) => {
          const session = state.sessions[sessionId];
          if (!session) {
            return state;
          }
          return {
            sessions: {
              ...state.sessions,
              [sessionId]: { ...session, selectedTables: tables, updatedAt: Date.now() },
            },
          };
        });
      },

      setSchemaInfo: (sessionId: string, ddl: string) => {
        set((state) => {
          const session = state.sessions[sessionId];
          if (!session) {
            return state;
          }
          return {
            sessions: {
              ...state.sessions,
              [sessionId]: { ...session, schemaInfo: ddl, updatedAt: Date.now() },
            },
          };
        });
      },

      setExplainResult: (sessionId: string, explain: AiChatSession['explainResult']) => {
        set((state) => {
          const session = state.sessions[sessionId];
          if (!session) {
            return state;
          }
          return {
            sessions: {
              ...state.sessions,
              [sessionId]: { ...session, explainResult: explain, updatedAt: Date.now() },
            },
          };
        });
      },

      setError: (sessionId: string, error: string) => {
        set((state) => {
          const session = state.sessions[sessionId];
          if (!session) {
            return state;
          }
          return {
            sessions: {
              ...state.sessions,
              [sessionId]: { ...session, state: 'FAILED', error, updatedAt: Date.now() },
            },
          };
        });
      },

      setLastRequest: (req: ILastRequest) => {
        set({ lastRequest: req });
      },

      clearSession: (sessionId: string) => {
        set((state) => {
          const { [sessionId]: _omit, ...rest } = state.sessions;
          void _omit;
          return {
            sessions: rest,
            currentSessionId: state.currentSessionId === sessionId ? null : state.currentSessionId,
          };
        });
      },

      resetCurrentContent: (sessionId: string) => {
        set((state) => {
          const session = state.sessions[sessionId];
          if (!session) {
            return state;
          }
          return {
            sessions: {
              ...state.sessions,
              [sessionId]: {
                ...session,
                currentContent: '',
                currentThinking: '',
                updatedAt: Date.now(),
              },
            },
          };
        });
      },

      renameSession: async (sessionId: string, title: string) => {
        set((state) => {
          const session = state.sessions[sessionId];
          if (session) {
            return {
              sessions: { ...state.sessions, [sessionId]: { ...session, title } },
            };
          }
          return state;
        });
        try {
          await aiConversationService.renameAiConversation({ conversationId: sessionId, title });
        } catch (e) {
          console.error('[AiChatStore] rename failed:', e);
        }
        set((state) => {
          return {
            conversationList: state.conversationList.map((item) =>
              item.conversationId === sessionId ? { ...item, title } : item,
            ),
          };
        });
      },

      deleteSession: async (sessionId: string) => {
        const { [sessionId]: _omit, ...rest } = get().sessions;
        void _omit;
        set((state) => ({
          sessions: rest,
          currentSessionId: state.currentSessionId === sessionId ? null : state.currentSessionId,
          conversationList: state.conversationList.filter((item) => item.conversationId !== sessionId),
        }));
        try {
          await aiConversationService.deleteAiConversation({ conversationId: sessionId });
        } catch (e) {
          console.error('[AiChatStore] delete failed:', e);
        }
      },

      switchToSession: (sessionId: string) => {
        set({ currentSessionId: sessionId });
        const session = get().sessions[sessionId];
        if (!session) {
          get().loadConversationDetail(sessionId);
        }
      },

      startNewConversation: (boundInfo) => {
        const newSessionId = uuidv4();
        get().createSession(newSessionId, {
          dataSourceId: boundInfo?.dataSourceId ?? null,
          databaseName: boundInfo?.databaseName ?? null,
          schemaName: boundInfo?.schemaName ?? null,
          title: '新对话',
        });
        aiConversationService
          .createAiConversation({
            conversationId: newSessionId,
            dataSourceId: boundInfo?.dataSourceId ?? undefined,
            databaseName: boundInfo?.databaseName ?? undefined,
            schemaName: boundInfo?.schemaName ?? undefined,
            title: '新对话',
          })
          .then((created) => {
            if (created) {
              set((s) => {
                const exists = s.conversationList.some(
                  (item) => item.conversationId === created.conversationId,
                );
                if (exists) {
                  return s;
                }
                const summary: IConversationSummary = {
                  conversationId: created.conversationId,
                  title: created.title ?? '新对话',
                  dataSourceId: created.dataSourceId ?? null,
                  dataSourceName: created.dataSourceName ?? null,
                  databaseName: created.databaseName ?? null,
                  schemaName: created.schemaName ?? null,
                  messageCount: created.messageCount ?? 0,
                  lastMessagePreview: created.lastMessagePreview ?? null,
                  status: created.status,
                  gmtCreate: created.gmtCreate,
                  gmtModified: created.gmtModified,
                };
                return {
                  conversationList: [summary, ...s.conversationList],
                };
              });
            }
          })
          .catch((e) => {
            console.warn('[AiChatStore] startNewConversation create failed (offline?):', e);
          });
        return newSessionId;
      },

      loadConversationList: async (reset = false) => {
        const state = get();
        if (state.conversationListLoading) {
          return;
        }
        const nextPageNo = reset ? 1 : state.conversationListPageNo + 1;
        set({ conversationListLoading: true });
        try {
          const res = await aiConversationService.queryAiConversations({
            pageNo: nextPageNo,
            pageSize: 20,
          });
          const list = res.data || [];
          set((s) => ({
            conversationList: reset ? list : [...s.conversationList, ...list],
            conversationListPageNo: nextPageNo,
            conversationListHasMore: list.length >= res.pageSize,
            conversationListLoading: false,
          }));
        } catch (e) {
          console.error('[AiChatStore] loadConversationList failed:', e);
          set({ conversationListLoading: false });
        }
      },

      loadConversationDetail: async (conversationId: string) => {
        try {
          const detail = await aiConversationService.getAiConversation({ conversationId });
          get().syncLocalSessionFromDetail(conversationId, detail.conversation, detail.messages);
        } catch (e) {
          console.error('[AiChatStore] loadConversationDetail failed:', e);
        }
      },

      syncLocalSessionFromDetail: (
        conversationId: string,
        conversation: IAiConversation,
        messages: IAiMessage[],
      ) => {
        set((state) => {
          const localMessages: IChatMessage[] = messages.map((m) => ({
            id: m.messageId,
            role: m.role,
            content: m.content,
            thinking: m.thinking || undefined,
            sequenceNo: m.sequenceNo,
            sqlExtracted: undefined,
          }));
          const existing = state.sessions[conversationId];
          const now = Date.now();
          const session: AiChatSession = {
            sessionId: conversationId,
            state: 'COMPLETED',
            messages: localMessages,
            currentContent: '',
            currentThinking: '',
            title: conversation.title,
            dataSourceId: conversation.dataSourceId,
            databaseName: conversation.databaseName,
            schemaName: conversation.schemaName,
            createdAt: existing?.createdAt ?? now,
            updatedAt: now,
          };
          return {
            sessions: { ...state.sessions, [conversationId]: session },
            currentSessionId: state.currentSessionId ?? conversationId,
          };
        });
      },
    }),
    {
      name: 'ai-chat-store',
      storage: persistStorage,
      partialize: (state) => ({
        sessions: state.sessions,
        currentSessionId: state.currentSessionId,
        conversationList: state.conversationList,
        conversationListPageNo: state.conversationListPageNo,
        conversationListHasMore: state.conversationListHasMore,
      }),
    },
  ),
);

export const generateTitle = generateTitleFromMessage;
export { uuidv4 };
