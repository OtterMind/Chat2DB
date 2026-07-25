import { useState, useRef, useEffect } from 'react';
import { QuestionType } from '@/constants/chat';
import MagicStickService from '@/service/magicStick';
import { formatParams } from '@/utils/url';
import connectToEventSource from '@/utils/eventSource';
import { useGlobalStore } from '@/store/global';
import miscServices from '@/service/misc';
import useAbortRequest from '@/hooks/useAbortRequest';

import {
  GetChatTokenParams,
  TextToCreateTableParams,
  TextToAlterTableParams,
  TextToSQLParams,
  GetChatTokenResponse,
} from '@/typings/ai';

interface GetPromptToken {
  [key: string]: {
    getPromptToken?: any;
    isStep?: boolean;
    isStream?: boolean;
  };
}

const aiRequestSet: GetPromptToken = {
  [QuestionType.NL_2_SQL]: {
    getPromptToken: MagicStickService.text2SQL,
  },
  [QuestionType.DASHBOARD_GENERATION_STREAM]: {
    getPromptToken: MagicStickService.sqlGenerateNLP,
    isStep: true,
  },
  [QuestionType.EXCEL_CHAT]: {
    getPromptToken: MagicStickService.text2SQL,
    isStep: true,
  },
  [QuestionType.SQL_EXPLAIN]: {
    getPromptToken: MagicStickService.explainSQL,
  },
  [QuestionType.SQL_OPTIMIZER]: {
    getPromptToken: MagicStickService.optimizeSQL,
  },
  [QuestionType.SQL_2_SQL]: {
    getPromptToken: MagicStickService.convertSQL,
  },
  [QuestionType.CRUD_GENERATION]: {
    getPromptToken: MagicStickService.generateCRUD,
  },
  [QuestionType.DATA_INSERT]: {
    getPromptToken: MagicStickService.generateTestData,
  },
  [QuestionType.NL_TO_CREATE_TABLE]: {
    getPromptToken: MagicStickService.textToCreateTable,
  },
  [QuestionType.NL_TO_ALTER_TABLE]: {
    getPromptToken: MagicStickService.textToAlterTable,
  },
  [QuestionType.DASHBOARD_GENERATION]: {
    getPromptToken: MagicStickService.text2Chart,
    isStream: false,
  },
};

interface UseChatAIParams {
  messageChangeCallback?: (props: { newMessage: string; allMessage: string; questionType: QuestionType }) => void;
  filterSqlCallBack?: (sql: string) => void;
  // Callback after output is completed
  onDone?: (message: string) => void;
}

interface ISendMessageResponse extends GetChatTokenResponse {
  isStep?: boolean;
}

interface UseChatAIResult {
  isLoading: boolean;
  messageContent: string;
  sendMessage: {
    (questionType: QuestionType.NL_TO_CREATE_TABLE, message: TextToCreateTableParams): Promise<ISendMessageResponse>;
    (questionType: QuestionType.NL_TO_ALTER_TABLE, message: TextToAlterTableParams): Promise<ISendMessageResponse>;
    (questionType: QuestionType, message: TextToSQLParams): Promise<ISendMessageResponse>;
  };
  closeEventSource: () => void;
  questionType: QuestionType | null;
}

export const useStreamChatAI = (params: UseChatAIParams = {}): UseChatAIResult => {
  const { messageChangeCallback, filterSqlCallBack, onDone } = params;
  const gatewayUrl = useGlobalStore.getState().appConfig.gatewayUrl;
  const [isLoading, setIsLoading] = useState(false);
  const [messageContent, setMessageContent] = useState<string>('');
  const messageContentRef = useRef<string>('');
  const closeEventSourceRef = useRef<any | null>(null);
  const [questionType, setQuestionType] = useState<QuestionType | null>(null);
  const [initSignal, abortRequest] = useAbortRequest();

  useEffect(() => {
    return () => {
      closeEventSource();
    };
  }, []);

  const closeEventSource = () => {
    abortRequest();
    closeEventSourceRef.current?.();
    closeEventSourceRef.current = null;
    setIsLoading(false);
  };

  const sendMessage = (_questionType, message: GetChatTokenParams) => {
    closeEventSource();
    setIsLoading(true);
    setMessageContent('');
    messageContentRef.current = '';
    setQuestionType(_questionType);
    const { getPromptToken, isStep } = aiRequestSet[_questionType];
    // AI requests for streaming output
    return new Promise<GetChatTokenResponse>((resolve, reject) => {
      getPromptToken(
        {
          ...message,
          questionType: _questionType,
        },
        { signal: initSignal() },
      )
        .then((res) => {
          resolve({
            ...res,
            isStep,
          });
          const _params = formatParams(res);
          closeEventSourceRef.current = connectToEventSource({
            url: `${gatewayUrl}/api/ai/chat?${_params}`,
            onOpen: () => {
              // console.log('SSE onOpen');
            },
            onMessage: (_message: string) => {
              try {
                const isEOF = _message === '[DONE]';
                if (isEOF) {
                  closeEventSourceRef.current?.();
                  setIsLoading(false);
                  onDone?.(messageContentRef.current);
                  if (filterSqlCallBack && messageContentRef.current) {
                    miscServices.characterHandler({ text: messageContentRef.current }).then((_res) => {
                      setMessageContent(_res);
                      messageContentRef.current = _res;
                      filterSqlCallBack(_res);
                    });
                  }
                } else {
                  const newMessage = JSON.parse(_message)?.content ?? '';
                  setMessageContent((prev) => prev + newMessage);
                  messageContentRef.current += newMessage;
                  messageChangeCallback?.({
                    newMessage,
                    allMessage: messageContentRef.current,
                    questionType: _questionType,
                  });
                }
              } catch (error) {
                console.error('handleAIChat error', error);
              }
            },
            onError: () => {
              setIsLoading(false);
              closeEventSourceRef.current?.();
            },
          });
        })
        .catch((error) => {
          reject(error);
          setIsLoading(false);
        });
    });
  };

  return {
    messageContent,
    isLoading,
    sendMessage,
    closeEventSource,
    questionType,
  };
};
