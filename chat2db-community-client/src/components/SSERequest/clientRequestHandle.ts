import type { AnyObject, SSERequestCallbacks, SSERequestHandle } from './index';
import { createRequestHandle, createSSEStreamError } from './requestHandle';

interface ClientRequestEventBus {
  on: (eventName: string, listener: (output: any) => void) => void;
  off: (eventName: string, listener: (output: any) => void) => void;
}

interface ClientRequestHandleOptions<Output> {
  callbacks: SSERequestCallbacks<Output>;
  eventBus: ClientRequestEventBus;
  eventName: string;
  handleErrorPayload: (value: unknown, requestParams?: AnyObject) => boolean;
  requestParams?: AnyObject;
}

const normalizeError = (error: unknown) => (error instanceof Error ? error : new Error('Unknown error!'));

const parseJsonObject = (value: unknown): Record<string, unknown> | undefined => {
  if (typeof value !== 'string') {
    return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : undefined;
  }

  try {
    const parsed = JSON.parse(value);
    return typeof parsed === 'object' && parsed !== null ? parsed : undefined;
  } catch {
    return undefined;
  }
};

const parseDonePayload = (data: unknown) => {
  const parsed = parseJsonObject(data);
  return parsed?.type === 'done' ? parsed : undefined;
};

export const createClientRequestHandle = <Output = unknown>({
  callbacks,
  eventBus,
  eventName,
  handleErrorPayload,
  requestParams,
}: ClientRequestHandleOptions<Output>): SSERequestHandle => {
  let settled = false;
  let resolveDone!: () => void;
  let rejectDone!: (error: Error) => void;
  const done = new Promise<void>((resolve, reject) => {
    resolveDone = resolve;
    rejectDone = reject;
  });

  const cleanup = () => {
    eventBus.off(eventName, listener);
  };

  const notifyError = (error: Error) => {
    let rejection = error;
    try {
      callbacks.onError(error);
    } catch (callbackError) {
      rejection = normalizeError(callbackError);
    }
    rejectDone(rejection);
  };

  const finish = (onFinish: () => void) => {
    if (settled) {
      return;
    }
    settled = true;
    cleanup();
    try {
      onFinish();
      resolveDone();
    } catch (error) {
      notifyError(normalizeError(error));
    }
  };

  const fail = (error: unknown) => {
    if (settled) {
      return;
    }
    settled = true;
    cleanup();
    notifyError(normalizeError(error));
  };

  const listener = (sseOutput: any) => {
    try {
      if (!sseOutput) {
        finish(() => callbacks.onSuccess([]));
        return;
      }

      const streamError = createSSEStreamError(sseOutput);
      if (streamError) {
        callbacks.onUpdate(sseOutput as Output);
        fail(streamError);
        return;
      }

      const parsedDonePayload = parseDonePayload(sseOutput.data);
      if (sseOutput.data === '[DONE]') {
        finish(() => callbacks.onSuccess([sseOutput] as Output[]));
      } else if (parsedDonePayload) {
        const doneChunk = {
          ...sseOutput,
          data: parsedDonePayload,
        };
        callbacks.onUpdate(doneChunk as Output);
        finish(() => callbacks.onSuccess([doneChunk] as Output[]));
      } else if (handleErrorPayload(sseOutput, requestParams)) {
        finish(() => callbacks.onSuccess([sseOutput] as Output[]));
      } else {
        callbacks.onUpdate(sseOutput);
      }
    } catch (error) {
      fail(error);
    }
  };

  const handle = createRequestHandle(done, () => {
    // JCEF has no request-level cancel command, so stop owns listener cleanup only.
    finish(() => callbacks.onStop?.());
  });

  try {
    eventBus.on(eventName, listener);
  } catch (error) {
    fail(error);
  }

  return handle;
};
