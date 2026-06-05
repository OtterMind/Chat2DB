import createRequest from './base';
import {
  IAiConversation,
  IAiConversationCreateRequest,
  IAiConversationDetail,
  IAiConversationQueryRequest,
  IAiConversationRenameRequest,
  IPageResponse,
} from '@/typings/aiConversation';

export const createAiConversation = createRequest<IAiConversationCreateRequest, IAiConversation>(
  '/api/ai/conversation/create',
  { method: 'post' },
);

export const queryAiConversations = createRequest<IAiConversationQueryRequest, IPageResponse<IAiConversation>>(
  '/api/ai/conversation/page',
  { method: 'get' },
);

export const getAiConversation = createRequest<{ conversationId: string }, IAiConversationDetail>(
  '/api/ai/conversation/:conversationId',
  { method: 'get' },
);

export const renameAiConversation = createRequest<
  IAiConversationRenameRequest & { conversationId: string },
  void
>('/api/ai/conversation/:conversationId/rename', { method: 'post' });

export const deleteAiConversation = createRequest<{ conversationId: string }, void>(
  '/api/ai/conversation/:conversationId',
  { method: 'delete' },
);

export default {
  createAiConversation,
  queryAiConversations,
  getAiConversation,
  renameAiConversation,
  deleteAiConversation,
};
