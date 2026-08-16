import { useCallback, useEffect, useState, useMemo, useRef } from 'react';
import sseRequest, {
  AnyObject,
  SSEOutput,
  SSERequestOptions,
  SSERequestParams,
  SSERequestStatus,
  type SSERequestHandle,
} from '@/components/SSERequest';
import { guardSSERequestCallbacks, SSERequestOwner } from '@/components/SSERequest/requestOwner';
import useSyncState from '@/hooks/useSyncState';
import { AnswerPartsType } from '@/constants/chat';
import { parseSSEChunkData } from '@/components/SSERequest/errorPayload';

export { SSERequestStatus };

export interface UseSSERequestOptions extends SSERequestOptions {
  baseURL: string;
  model?: string;
  dangerouslyApiKey?: string;
  onChunk?: (rawChunk: SSEOutput, parsedData: IParsedData) => void;
}

export interface UseSSERequestResult<T = string> {
  content: T;
  status: SSERequestStatus;
  error?: Error;
  request: (params: SSERequestParams & AnyObject) => Promise<void>;
  stop: () => void;
}

export interface IParsedData {
  id: string;
  content: string;
  type: AnswerPartsType;
  title?: string;
  chatId?: number;
  questionId?: number;
  answerId?: number;
}

const useSSERequest = <T = string>(
  options: UseSSERequestOptions,
  transformer?: (parsedData: IParsedData) => T,
): UseSSERequestResult<T> => {
  const [content, setContent] = useState<T>('' as unknown as T);
  const [status, setStatus] = useSyncState<SSERequestStatus>(SSERequestStatus.IDLE);
  const [error, setError] = useState<Error>();
  const mountedRef = useRef(true);
  const requestOwner = useMemo(() => new SSERequestOwner(), []);

  const onChunkRef = useRef(options.onChunk);
  onChunkRef.current = options.onChunk;

  const instance = useMemo(
    () => sseRequest(options),
    [options.baseURL, options.model, options.dangerouslyApiKey, options.lang],
  );

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestOwner.stop();
    };
  }, [requestOwner]);

  const request = useCallback(
    async (params: SSERequestParams & AnyObject) => {
      console.log('[useSSERequest] request called, params:', params);
      const generation = requestOwner.begin();
      setStatus(SSERequestStatus.LOADING);
      setError(undefined);
      const chunks: Record<string, string>[] = [];
      let accumulatedContent = '';
      let requestHandle: SSERequestHandle | undefined;
      let callbackHandledError = false;
      const isActiveRequest = () => mountedRef.current && requestOwner.owns(generation, requestHandle);

      try {
        requestHandle = instance.create(
          params,
          guardSSERequestCallbacks<SSEOutput>(
            {
              onSuccess: () => {
                console.log('[useSSERequest] onSuccess, chunks count:', chunks.length);
                setStatus(SSERequestStatus.FINISH);
              },
              onError: (err) => {
                callbackHandledError = true;
                console.log('[useSSERequest] onError:', err);
                setStatus(SSERequestStatus.ERROR);
                setError(err);
              },
              onUpdate: (chunk) => {
                console.log('[useSSERequest] onUpdate called, chunk:', chunk);
                chunks.push(chunk as Record<string, string>);

                if (transformer) {
                  const parsedData = parseSSEChunkData<IParsedData>(chunk.data);
                  if (!parsedData) {
                    return;
                  }
                  transformer(parsedData);
                } else {
                  const parsedData = parseSSEChunkData<IParsedData>(chunk.data);
                  if (!parsedData) {
                    console.log('json_parse_error');
                    return;
                  }
                  console.log('[useSSERequest] parsedData:', parsedData);
                  onChunkRef.current?.(chunk, parsedData);
                  const newContent = parsedData?.content || '';
                  accumulatedContent += newContent;
                  console.log('[useSSERequest] accumulatedContent length:', accumulatedContent.length);
                  setContent(accumulatedContent as unknown as T);
                }
              },
              onStop: () => {
                console.log('[useSSERequest] onStop');
                setStatus(SSERequestStatus.FINISH);
              },
            },
            isActiveRequest,
          ),
        );
        if (!requestOwner.attach(generation, requestHandle)) {
          return;
        }
        await requestHandle.done;
      } catch (error_) {
        if (!mountedRef.current || !requestOwner.isCurrentGeneration(generation) || callbackHandledError) {
          return;
        }
        console.log('[useSSERequest] catch error:', error_);
        setStatus(SSERequestStatus.ERROR);
        setError(error_ instanceof Error ? error_ : new Error('Unknown error'));
      } finally {
        if (requestHandle) {
          requestOwner.release(generation, requestHandle);
        }
      }
    },
    [instance, requestOwner, setStatus, transformer],
  );

  const stop = useCallback(() => {
    if (requestOwner.stop() && mountedRef.current) {
      setStatus(SSERequestStatus.FINISH);
    }
  }, [requestOwner, setStatus]);

  return {
    content,
    status,
    error,
    request,
    stop,
  };
};
export default useSSERequest;
