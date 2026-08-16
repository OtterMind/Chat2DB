import { isDesktop } from '@/utils/env';
import { SSEOutput, SSEStreamProps } from './sseStream';
import ClientRequestClass from './sseClientRequest';
import HTTPSRequestClass from './sseHttpsRequest';

export type AnyObject = Record<PropertyKey, any>;
export type { SSEOutput };

export enum SSERequestStatus {
  ERROR = 'error',
  FINISH = 'finish',
  IDLE = 'idle',
  LOADING = 'loading',
  THINKING = 'thinking',
}

export interface SSERequestOptions {
  /**
   * @description Base URL.
   */
  baseURL: string;
  /**
   * @description Model name, e.g., 'gpt-3.5-turbo'
   */
  model?: string;

  dangerouslyApiKey?: string;

  lang?: string;
}

type SSERequestMessageContent = string | AnyObject;
interface SSERequestMessage extends AnyObject {
  role?: string;
  content?: SSERequestMessageContent;
}

/**
 * Compatible with the parameters of OpenAI's chat.completions.create,
 * with plans to support more parameters and adapters in the future
 */
export interface SSERequestParams {
  /**
   * @description Model name, e.g., 'gpt-3.5-turbo'
   * @default XRequestOptions.model
   */
  model?: string;

  /**
   * @description Indicates whether to use streaming for the response
   */
  stream?: boolean;

  /**
   * @description The messages to be sent to the model
   */
  messages?: SSERequestMessage[];
}

export interface SSERequestCallbacks<Output> {
  /**
   * @description Callback when the request is successful
   */
  onSuccess: (chunks: Output[]) => void;

  /**
   * @description Callback when the request fails
   */
  onError: (error: Error) => void;

  /**
   * @description Callback when the request is updated
   */
  onUpdate: (chunk: Output) => void;

  /**
   * @description Callback when the request is stopped
   */
  onStop?: () => void;
}

export interface SSERequestHandle extends Promise<void> {
  /** Resolves when this request succeeds or is stopped, and rejects on request failure. */
  done: Promise<void>;

  /** Stops only the request represented by this handle. */
  stop: () => void;
}

export type SSERequestFunction<Input = AnyObject, Output = SSEOutput> = (
  params: SSERequestParams & Input,
  callbacks: SSERequestCallbacks<Output>,
  transformStream?: SSEStreamProps<Output>['transformStream'],
) => SSERequestHandle;

const SSERequest = (options: SSERequestOptions) => {
  if (isDesktop) {
    return ClientRequestClass.init(options);
  } else {
    return HTTPSRequestClass.init(options);
  }
};

export default SSERequest;
