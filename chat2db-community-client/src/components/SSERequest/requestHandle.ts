import type { SSERequestHandle } from './index';

interface AbortableRequestCallbacks {
  onError?: (error: Error) => void;
  onStop?: () => void;
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

export const createSSEStreamError = (output: any) => {
  if (typeof output?.event !== 'string' || output.event.trim() !== 'error') {
    return undefined;
  }

  const payload = parseJsonObject(output.data);
  const content = payload?.content;
  return new Error(typeof content === 'string' && content.trim() ? content : 'AI stream failed');
};

export const createRequestHandle = (done: Promise<void>, stop: () => void): SSERequestHandle =>
  Object.assign(done, { done, stop });

export const createAbortableRequestHandle = (
  execute: (signal: AbortSignal) => Promise<void>,
  callbacks: AbortableRequestCallbacks = {},
): SSERequestHandle => {
  const abortController = new AbortController();
  let settled = false;
  const done = Promise.resolve()
    .then(() => execute(abortController.signal))
    .catch((error: unknown) => {
      if (error instanceof Error && error.name === 'AbortError') {
        callbacks.onStop?.();
        return;
      }

      const normalizedError = normalizeError(error);
      callbacks.onError?.(normalizedError);
      throw normalizedError;
    })
    .finally(() => {
      settled = true;
    });

  return createRequestHandle(done, () => {
    if (!settled) {
      abortController.abort();
    }
  });
};
