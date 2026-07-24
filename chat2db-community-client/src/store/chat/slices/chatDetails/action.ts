import { StateCreator } from 'zustand';
import { ChatStore } from '../../store';
import { QuestionVO, ChatDetailVO, AnswerParts, UpdateAnswerPartsParams, AnswerVO } from '@/typings/chat';
import { AnswerPartsStatus, QuestionType } from '@/constants/chat';
import _ from 'lodash';
import { v4 as uuid } from 'uuid';

import chatService from '@/service/chat';
import { TaskStatus } from '@/constants';
import { revisalChatDetail } from '@/utils/chat';
import { updateFirstAnswer } from './answerUpdates';

interface CreateQuestionParams {
  chatId?: number;
  questionId?: number;
  content: string;
  type: QuestionType;
}

export interface ChatDetailAction {
  // Reset chatDetails
  resetChatDetails: (page: string) => void;
  // Initialize chatDetails
  initChatDetails: (chatDetailList?: ChatDetailVO[]) => void;
  // Append data to chatDetails
  appendChatDetails: {
    (content: ChatDetailVO['question'], type: 'question'): string;
    (content: ChatDetailVO['answers'], type: 'answers'): string;
  };
  // create question
  createQuestion: (params: CreateQuestionParams) => (questionId: number, chatId: number) => void;
  // Update Question id
  updateQuestionId: (questionId: number, chatId: number, chatDetailId: string) => void;
  // Get answer by questionId
  getQuestionAnswerByQuestionId: (questionId: number) => {
    answer: AnswerVO | undefined;
    question: QuestionVO | undefined;
  };
  // Stop all answerParts for the current QuestionId
  stopAllAnswerParts: (questionId: number) => void;
  // Append answerPart
  appendAnswerParts: (params: { data: AnswerParts; questionId: number }) => void;
  // Update a certain value of answer
  updateAnswer: (questionId: number, params: any) => void;
  // Change the status of Answer
  updateAnswerStatus: (questionId: number, status: TaskStatus) => void;
  // Update Answer's id
  updateAnswerId: (questionId: number, answerId: number) => void;
  // Adjust the interface to update AnswerPart
  updateAnswerPartsToService: (parts: UpdateAnswerPartsParams) => void;
}

export const createChatDetailAction: StateCreator<ChatStore, [['zustand/devtools', never]], [], ChatDetailAction> = (
  set,
  get,
) => ({
  resetChatDetails: (page: string) => {
    set({
      currentChat: {
        ...get().currentChat,
        [page]: null,
      },
      chatDetails: null,
      chatDetailsIds: null,
    });
  },
  initChatDetails: (chatDetailList) => {
    const chatDetailsIds: string[] = [];
    const chatDetails = {};
    chatDetailList?.map((item) => {
      const id = uuid();
      chatDetails[id] = revisalChatDetail(item);
      chatDetailsIds.push(id);
    });

    set({
      chatDetails,
      chatDetailsIds,
    });
  },
  appendChatDetails: (content, type) => {
    const chatDetails = { ...(get().chatDetails || {}) };
    const chatDetailId = uuid();
    if (type === 'question') {
      chatDetails[chatDetailId] = {
        question: content,
        answers: [],
      };
    }

    if (type === 'answers') {
      const entries = Object.values(chatDetails);
      const lastChatDetail = _.last(entries);
      if (lastChatDetail) {
        const lastKey = Object.keys(chatDetails).find(k => chatDetails[k] === lastChatDetail);
        if (lastKey) {
          chatDetails[lastKey] = { ...lastChatDetail, answers: content };
        }
      }
    }

    set({
      chatDetails,
      chatDetailsIds: Object.keys(chatDetails),
    });

    return chatDetailId;
  },
  createQuestion: (params) => {
    const { chatId, content, questionId, type } = params;
    const questionObj: QuestionVO = {
      id: questionId,
      chatId,
      content,
      type,
    };
    const chatDetailId = get().appendChatDetails(questionObj, 'question');
    // Create the question first, and then update the questionId after you have the questionId.
    return (_questionId, _chatId) => {
      get().updateQuestionId(_questionId, _chatId, chatDetailId);
    };
  },
  updateQuestionId: (questionId, chatId, chatDetailId) => {
    const chatDetails = get().chatDetails;
    const question = chatDetails?.[chatDetailId]?.question;
    if (question) {
      const newChatDetails = { ...chatDetails };
      newChatDetails[chatDetailId] = {
        ...newChatDetails[chatDetailId],
        question: { ...question, id: questionId, chatId },
      };
      set({ chatDetails: newChatDetails });
    }
  },
  getQuestionAnswerByQuestionId: (questionId: number) => {
    const chatDetails = get().chatDetails;
    if (!chatDetails) {
      return {
        answer: undefined,
        question: undefined,
      };
    }

    const chatDetailId = Object.keys(chatDetails).find((key) => chatDetails[key].question?.id === questionId);
    if (!chatDetailId) {
      return {
        answer: undefined,
        question: undefined,
      };
    }

    const question = chatDetails[chatDetailId].question;
    // In fact, there will only be one answer. Later, there may be multiple answers for one question.
    const answer = chatDetails[chatDetailId].answers?.[0];
    return {
      answer,
      question,
    };
  },

  appendAnswerParts: ({ data, questionId }) => {
    const questionVO = get().getQuestionAnswerByQuestionId(questionId);
    const { question } = questionVO;

    if (!question) {
      return;
    }

    let { answer } = questionVO;
    if (!answer?.parts) {
      answer = {
        chatId: question.chatId!,
        questionId: question.id!,
        questionType: question.type,
        parts: [data],
      };
    } else {
      const step = data.step;
      // Create a new parts array
      const newParts = [...answer.parts];
      newParts[step] = {
        ...newParts[step],
        ...data,
      };
      // Create new answer with new parts
      answer = { ...answer, parts: newParts };
    }
    // answer.databaseInfo = databaseInfo;
    get().appendChatDetails([answer], 'answers');
  },

  stopAllAnswerParts: (questionId: number) => {
    const { answer } = get().getQuestionAnswerByQuestionId(questionId);
    if (!answer || !answer.parts) {
      return;
    }

    const newParts = (answer?.parts || []).map((part) => {
      return {
        ...part,
        status: AnswerPartsStatus.FINISH,
      };
    });
    const updatedAnswer = { ...answer, parts: newParts };
    get().appendChatDetails([updatedAnswer], 'answers');
  },
  updateAnswer: (questionId, params) => {
    const chatDetails = get().chatDetails;
    if (!chatDetails) {
      return;
    }
    const chatDetailId = Object.keys(chatDetails).find((key) => chatDetails[key].question?.id === questionId);
    if (!chatDetailId) {
      return;
    }
    const answer = chatDetails[chatDetailId].answers?.[0];
    if (answer) {
      const newChatDetails = { ...chatDetails };
      newChatDetails[chatDetailId] = {
        ...newChatDetails[chatDetailId],
        answers: updateFirstAnswer(newChatDetails[chatDetailId].answers, params),
      };
      set({ chatDetails: newChatDetails });
    }
  },

  updateAnswerStatus: (questionId, status) => {
    get().updateAnswer(questionId, { status });
  },
  updateAnswerId: (questionId, answerId) => {
    get().updateAnswer(questionId, { id: answerId });
  },
  updateAnswerPartsToService: (parts) => {
    chatService.updateAnswerParts(parts);
  },
});
