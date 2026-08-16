import { ChatVO, UpdateAnswerPartsParams } from '@/typings/chat';
import createRequest from './base';
import { IPageParams, IPageResponse } from '@/typings/common';

const prefix = '/api/ai/chat';

/** Query session list */
const getChatList = createRequest<IPageParams, IPageResponse<ChatVO>>(`/api/v2/ai/chat/list`);

/** Update session information, including the title and dataset. */
const updateChatInfo = createRequest<ChatVO, boolean>(`${prefix}/update`, {
  method: 'post',
});

/** View conversation details by ID */
const getChatDetailById = createRequest<
  {
    lastQuestionId?: any;
    id: number;
    pageSize: number;
  },
  ChatVO
>(`/api/v2/ai/chat/get`);

/** Delete conversation */
const deleteChat = createRequest<{ id: number }, boolean>(`${prefix}/delete`, {
  method: 'delete',
});

// Update conversation answers every step of the way
const updateAnswerParts = createRequest<UpdateAnswerPartsParams, boolean>(`/api/ai/parts/answer/update`, {
  method: 'post',
  errorLevel: false,
});

/**
 * Get the conversation information concisely through dataSourceId
 * /api/ai/chat/getChatBrief
 */
const getChatBriefByDataSourceId = createRequest<
  {
    dataSourceId: number;
  },
  ChatVO
>('/api/v2/ai/chat/getChatBrief');

export default {
  getChatList,
  getChatDetailById,
  updateChatInfo,
  deleteChat,
  updateAnswerParts,
  getChatBriefByDataSourceId,
};
