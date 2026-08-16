import sendClientSSERequest, { IJcefSseRequest } from '@/service/sse';
import type { SSERequestOptions, SSERequestParams, SSERequestCallbacks, SSERequestHandle, AnyObject } from './index';
import type { SSEStreamProps } from './sseStream';
import { JcefEventBus, JavaPushActionType } from '@/jcef/eventBus';
import { handleSSEErrorPayload } from './errorPayload';
import { createClientRequestHandle } from './clientRequestHandle';

export type SSEFields = 'data' | 'event' | 'id' | 'retry';
export type SSEOutput = Partial<Record<SSEFields, any>>;

class ClientRequestClass {
  readonly baseURL;
  readonly model;

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

  public create<Input = AnyObject, Output = SSEOutput>(
    params: SSERequestParams & Input,
    callbacks: SSERequestCallbacks<Output>,
    _transformStream?: SSEStreamProps<Output>['transformStream'],
  ): SSERequestHandle {
    const currentRequest: IJcefSseRequest = sendClientSSERequest(this.baseURL, params);
    const eventName = `${JavaPushActionType.AI_SSE_MESSAGE}_${currentRequest.requestId}`;
    return createClientRequestHandle({
      callbacks: {
        ...callbacks,
        onUpdate: (output) => {
          if (__PRINT_LOGS__ || window._PRINT_LOGS) {
            console.log('sse-content', output);
          }
          callbacks.onUpdate(output);
        },
      },
      eventBus: JcefEventBus,
      eventName,
      handleErrorPayload: handleSSEErrorPayload,
      requestParams: params,
    });
  }
}

export default ClientRequestClass;
