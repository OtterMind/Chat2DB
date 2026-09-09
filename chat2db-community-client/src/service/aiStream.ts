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
  sessionVersion: 1 | 2;
  runtimeType?: 'PI' | 'CODEX' | 'DSH';
  agentStatus?: string;
  modelConfigId?: string;
  gmtCreate: string;
  gmtModified: string;
}

export type KnowledgeSelectionType = 'KNOWLEDGE_TERM' | 'BUSINESS_LOGIC' | 'SQL_TEMPLATE';

export interface ISelectedKnowledge {
  id: number;
  type: KnowledgeSelectionType;
  key: string;
  value?: string;
}

export interface IChatMessage {
  id: string;
  sessionId: string;
  role: 'user' | 'assistant';
  content: string;
  reasoningContent?: string;
  attachments?: IChatAttachment[];
  selectedKnowledge?: ISelectedKnowledge[];
  gmtCreate: string;
}

const getModelCatalog = createRequest<void, IModelCatalogItem[]>('/api/v3/ai/model/list');
const getModelOptions = createRequest<void, IModelOptionItem[]>('/api/v3/ai/model/options');
const getChatSessions = createRequest<void, IChatSession[]>('/api/v3/ai/sessions');
const getChatMessages = createRequest<{ sessionId: string }, IChatMessage[]>('/api/v3/ai/chat/history/messages');
const deleteV1ChatSession = createRequest<{ id: string }, void>('/api/v3/ai/chat/history/session/delete', {
  method: 'post',
});
const renameV1ChatSession = createRequest<{ id: string; title: string }, void>('/api/v3/ai/chat/history/session/rename', {
  method: 'post',
});
const deleteV2ChatSession = createRequest<{ id: string }, void>('/api/v3/ai/sessions/:id/delete', {
  method: 'post',
});
const renameV2ChatSession = createRequest<{ id: string; title: string }, IChatSession>('/api/v3/ai/sessions/:id/rename', {
  method: 'post',
});

const deleteChatSession = ({ id, sessionVersion }: Pick<IChatSession, 'id' | 'sessionVersion'>) =>
  sessionVersion === 2 ? deleteV2ChatSession({ id }) : deleteV1ChatSession({ id });

const renameChatSession = ({
  id,
  title,
  sessionVersion,
}: Pick<IChatSession, 'id' | 'title' | 'sessionVersion'>) =>
  sessionVersion === 2 ? renameV2ChatSession({ id, title }) : renameV1ChatSession({ id, title });

export default {
  getModelCatalog,
  getModelOptions,
  getChatSessions,
  getChatMessages,
  deleteChatSession,
  renameChatSession,
};
