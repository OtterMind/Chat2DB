import createRequest from './base';
import { IChatAttachment } from './aiAttachment';

export interface IModelCatalogItem {
  provider: 'OPENAI' | 'CLAUDE' | 'GEMINI' | 'MINIMAX';
  models: string[];
}

export interface IModelOptionItem {
  value: string;
  label: string;
  provider: 'OPENAI' | 'CLAUDE' | 'GEMINI' | 'MINIMAX';
  model: string;
  modelConfigId?: string;
  customOption?: boolean;
  defaultOption?: boolean;
}

export interface IChatSession {
  id: string;
  title: string;
  gmtCreate: string;
  gmtModified: string;
}

export interface IChatMessage {
  id: string;
  sessionId: string;
  role: 'user' | 'assistant';
  content: string;
  reasoningContent?: string;
  attachments?: IChatAttachment[];
  gmtCreate: string;
}

const getModelCatalog = createRequest<void, IModelCatalogItem[]>('/api/v3/ai/model/list');
const getModelOptions = createRequest<void, IModelOptionItem[]>('/api/v3/ai/model/options');
const getChatSessions = createRequest<void, IChatSession[]>('/api/v3/ai/chat/history/sessions');
const getChatMessages = createRequest<{ sessionId: string }, IChatMessage[]>('/api/v3/ai/chat/history/messages');
const deleteChatSession = createRequest<{ id: string }, void>('/api/v3/ai/chat/history/session/delete', {
  method: 'post',
});

export default {
  getModelCatalog,
  getModelOptions,
  getChatSessions,
  getChatMessages,
  deleteChatSession,
};
