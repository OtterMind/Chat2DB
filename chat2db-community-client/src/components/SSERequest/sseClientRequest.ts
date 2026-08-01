import sendClientSSERequest, { IJcefSseRequest } from '@/service/sse';
import { SSERequestOptions, SSERequestParams, SSERequestCallbacks, AnyObject } from './index';
import { JcefEventBus, JavaPushActionType } from '@/jcef/eventBus';
import { handleSSEErrorPayload, isApplicationStreamErrorPayload } from './errorPayload';

export type SSEFields = 'data' | 'event' | 'id' | 'retry';
export type SSEOutput = Partial<Record<SSEFields, any>>;

class ClientRequestClass {
  readonly baseURL;
  readonly model;
  private currentRequest?: IJcefSseRequest;
  private onStop?: () => void;

  private constructor(options: SSERequestOptions) {
    const { baseURL, model } = options;

    this.baseURL = baseURL;
    this.model = model;
  }

  private static instanceBuffer: Map<string | typeof fetch, ClientRequestClass> = new Map();

  public static init(options: SSERequestOptions): ClientRequestClass {
    if (!options.baseURL || typeof options.baseURL !== 'string') {
      throw new Error('The baseURL is not valid!');
    }

    const id = options.baseURL;

    if (!ClientRequestClass.instanceBuffer.has(id)) {
      ClientRequestClass.instanceBuffer.set(id, new ClientRequestClass(options));
    }

    return ClientRequestClass.instanceBuffer.get(id)!;
  }

  public async create<Input = AnyObject, Output = SSEOutput>(
    params: SSERequestParams & Input,
    callbacks: SSERequestCallbacks<Output>,
  ) {
    try {
    // Remove stale listeners.
      if (this.currentRequest) {
        JcefEventBus.off(`${JavaPushActionType.AI_SSE_MESSAGE}_${this.currentRequest.requestId}`);
      }

      this.onStop = callbacks.onStop;
      this.currentRequest = sendClientSSERequest(this.baseURL, params);

      const listener = (sseOutput: any) => {
        if (__PRINT_LOGS__ || window._PRINT_LOGS) {
          console.log('sse-content', sseOutput);
        }
        if (!sseOutput) {
          return;
        }
        try {
          const parsedDonePayload =
            typeof sseOutput?.data === 'string' ? safeParseDonePayload(sseOutput.data) : undefined;

          if (sseOutput?.data === '[DONE]') {
            callbacks.onSuccess([sseOutput] as Output[]);
            this.stop();
          } else if (parsedDonePayload) {
            const doneChunk = {
              ...sseOutput,
              data: parsedDonePayload,
            };
            callbacks.onUpdate(doneChunk as Output);
            callbacks.onSuccess([doneChunk] as Output[]);
            this.stop();
          } else if (isApplicationStreamErrorPayload(sseOutput?.data)) {
            // Application-level stream errors (subscription idle timeout, model reject, …)
            // are in-band events. Keep the listener open for the following done payload
            // so partial finalAnswer can still be recovered before the stream ends.
            callbacks.onUpdate(sseOutput as Output);
          } else if (handleSSEErrorPayload(sseOutput, params)) {
            callbacks.onSuccess([sseOutput] as Output[]);
            this.stop();
          } else {
            callbacks.onUpdate(sseOutput);
          }
        } catch (error) {
          console.error('push-sse-message', error);
        }
      };

      JcefEventBus.on(`${JavaPushActionType.AI_SSE_MESSAGE}_${this.currentRequest?.requestId}`, listener);
    } catch (error) {
      const err = error instanceof Error ? error : new Error('Unknown error!');
      callbacks.onError(err);
      throw err;
    }
  }

  public stop() {
    JcefEventBus.off(`${JavaPushActionType.AI_SSE_MESSAGE}_${this.currentRequest?.requestId}`);
    this.currentRequest = undefined;
    this.onStop?.();
  }
}

function safeParseDonePayload(data: string) {
  try {
    const parsed = JSON.parse(data);
    if (parsed?.type === 'done') {
      return parsed;
    }
  } catch {
    return undefined;
  }
  return undefined;
}

export default ClientRequestClass;
