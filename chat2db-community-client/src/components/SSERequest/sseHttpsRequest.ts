import sseStream, { SSEOutput, SSEStreamProps } from './sseStream';
import sseFetch from './sseFetch';
import type { SSERequestOptions, SSERequestParams, SSERequestCallbacks, SSERequestHandle, AnyObject } from './index';
import { handleSSEErrorPayload } from './errorPayload';
import { createAbortableRequestHandle, createSSEStreamError } from './requestHandle';

class HTTPSRequestClass {
  readonly baseURL;
  readonly model;
  private defaultHeaders;

  private static instanceBuffer: Map<string | typeof fetch, HTTPSRequestClass> = new Map();

  private constructor(options: SSERequestOptions) {
    const { baseURL, model } = options;

    this.baseURL = baseURL;
    this.model = model;
    this.defaultHeaders = {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream, application/json',
      'Cache-Control': 'no-cache',
      ...(options.dangerouslyApiKey && {
        Authorization: options.dangerouslyApiKey,
      }),
      'Accept-Language': options.lang || 'en-US',
    };
  }

  public static init(options: SSERequestOptions): HTTPSRequestClass {
    if (!options.baseURL || typeof options.baseURL !== 'string') {
      throw new Error('The baseURL is not valid!');
    }

    const id = JSON.stringify([
      options.baseURL,
      options.model || '',
      options.dangerouslyApiKey || '',
      options.lang || '',
    ]);

    if (!HTTPSRequestClass.instanceBuffer.has(id)) {
      HTTPSRequestClass.instanceBuffer.set(id, new HTTPSRequestClass(options));
    }

    return HTTPSRequestClass.instanceBuffer.get(id)!;
  }

  public create = <Input = AnyObject, Output = SSEOutput>(
    params: SSERequestParams & Input,
    callbacks: SSERequestCallbacks<Output>,
    transformStream?: SSEStreamProps<Output>['transformStream'],
  ): SSERequestHandle => {
    return createAbortableRequestHandle(async (signal) => {
      const requestInit = {
        method: 'POST',
        headers: this.defaultHeaders,
        body: JSON.stringify({
          model: this.model,
          ...params,
        }),
        signal,
      };
      const response = await sseFetch(this.baseURL, requestInit);

      if (transformStream) {
        await this.customResponseHandler<Output>(response, callbacks, transformStream);
        return;
      }

      const contentType = response.headers.get('content-type') || '';

      const mimeType = contentType.split(';')[0].trim();
      switch (mimeType) {
        /** SSE */
        case 'text/event-stream': {
          await this.sseResponseHandler<Output>(response, callbacks, params);
          break;
        }

        /** JSON */
        case 'application/json': {
          await this.jsonResponseHandler<Output>(response, callbacks, params);
          break;
        }

        default: {
          throw new Error(`The response content-type: ${contentType} is not support!`);
        }
      }
    }, callbacks);
  };

  private customResponseHandler = async <Output = SSEOutput>(
    response: Response,
    callbacks?: SSERequestCallbacks<Output>,
    transformStream?: SSEStreamProps<Output>['transformStream'],
  ) => {
    const chunks: Output[] = [];

    for await (const chunk of sseStream({
      readableStream: response.body!,
      transformStream,
    })) {
      chunks.push(chunk);

      callbacks?.onUpdate?.(chunk);
    }

    callbacks?.onSuccess?.(chunks);
  };

  private sseResponseHandler = async <Output = SSEOutput>(
    response: Response,
    callbacks?: SSERequestCallbacks<Output>,
    requestParams?: AnyObject,
  ) => {
    const chunks: Output[] = [];
    for await (const chunk of sseStream<Output>({
      readableStream: response.body!,
    })) {
      const streamError = createSSEStreamError(chunk);
      if (streamError) {
        callbacks?.onUpdate?.(chunk);
        throw streamError;
      }

      if (handleSSEErrorPayload(chunk, requestParams)) {
        callbacks?.onSuccess?.(chunks);
        return;
      }

      if ((chunk as SSEOutput).data === '[DONE]') {
        callbacks?.onSuccess?.(chunks);
        return;
      }

      chunks.push(chunk);
      callbacks?.onUpdate?.(chunk);
    }

    callbacks?.onSuccess?.(chunks);
  };

  private jsonResponseHandler = async <Output = SSEOutput>(
    response: Response,
    callbacks?: SSERequestCallbacks<Output>,
    requestParams?: AnyObject,
  ) => {
    const chunk: Output = await response.json();

    if (handleSSEErrorPayload(chunk, requestParams)) {
      callbacks?.onSuccess?.([]);
      return;
    }

    callbacks?.onUpdate?.(chunk);

    callbacks?.onSuccess?.([chunk]);
  };
}

export default HTTPSRequestClass;
