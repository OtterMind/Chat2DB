import { create } from 'zustand';

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
  sessions: Map<string, AiChatSession>;
  currentSessionId: string | null;
  lastRequest: ILastRequest | null;

  createSession: (sessionId: string) => void;
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
}

export const useAiChatStore = create<IAiChatStore>((set, get) => ({
  sessions: new Map(),
  currentSessionId: null,
  lastRequest: null,

  createSession: (sessionId: string) => {
    set((state) => {
      const newSessions = new Map(state.sessions);
      newSessions.set(sessionId, {
        sessionId,
        state: 'IDLE',
        messages: [],
        currentContent: '',
        currentThinking: '',
      });
      return { sessions: newSessions, currentSessionId: sessionId };
    });
  },

  updateState: (sessionId: string, newState: ChatStateType) => {
    set((state) => {
      const sessions = new Map(state.sessions);
      const session = sessions.get(sessionId);
      if (session) {
        sessions.set(sessionId, { ...session, state: newState });
      }
      return { sessions };
    });
  },

  appendContent: (sessionId: string, content: string, thinking?: string) => {
    set((state) => {
      const sessions = new Map(state.sessions);
      const session = sessions.get(sessionId);
      if (session) {
        sessions.set(sessionId, {
          ...session,
          currentContent: session.currentContent + (content || ''),
          currentThinking: session.currentThinking + (thinking || ''),
        });
      }
      return { sessions };
    });
  },

  addMessage: (sessionId: string, message: IChatMessage) => {
    set((state) => {
      const sessions = new Map(state.sessions);
      const session = sessions.get(sessionId);
      if (session) {
        sessions.set(sessionId, {
          ...session,
          messages: [...session.messages, message],
        });
      }
      return { sessions };
    });
  },

  setSelectedTables: (sessionId: string, tables: string[]) => {
    set((state) => {
      const sessions = new Map(state.sessions);
      const session = sessions.get(sessionId);
      if (session) {
        sessions.set(sessionId, { ...session, selectedTables: tables });
      }
      return { sessions };
    });
  },

  setSchemaInfo: (sessionId: string, ddl: string) => {
    set((state) => {
      const sessions = new Map(state.sessions);
      const session = sessions.get(sessionId);
      if (session) {
        sessions.set(sessionId, { ...session, schemaInfo: ddl });
      }
      return { sessions };
    });
  },

  setExplainResult: (sessionId: string, explain: AiChatSession['explainResult']) => {
    set((state) => {
      const sessions = new Map(state.sessions);
      const session = sessions.get(sessionId);
      if (session) {
        sessions.set(sessionId, { ...session, explainResult: explain });
      }
      return { sessions };
    });
  },

  setError: (sessionId: string, error: string) => {
    set((state) => {
      const sessions = new Map(state.sessions);
      const session = sessions.get(sessionId);
      if (session) {
        sessions.set(sessionId, { ...session, state: 'FAILED', error });
      }
      return { sessions };
    });
  },

  setLastRequest: (req: ILastRequest) => {
    set({ lastRequest: req });
  },

  clearSession: (sessionId: string) => {
    set((state) => {
      const sessions = new Map(state.sessions);
      sessions.delete(sessionId);
      const newCurrentId = state.currentSessionId === sessionId ? null : state.currentSessionId;
      return { sessions, currentSessionId: newCurrentId };
    });
  },

  resetCurrentContent: (sessionId: string) => {
    set((state) => {
      const sessions = new Map(state.sessions);
      const session = sessions.get(sessionId);
      if (session) {
        sessions.set(sessionId, { ...session, currentContent: '', currentThinking: '' });
      }
      return { sessions };
    });
  },
}));
