import { JavaPushActionType } from '@/jcef/eventBus';

const DEFAULT_FAILURE_MESSAGE = 'AI stream request failed';

export const streamEventName = (requestId: string) => `${JavaPushActionType.AI_SSE_MESSAGE}_${requestId}`;

/**
 * Shapes a request-level failure like a stream error event so the stream listener
 * settles through its existing error path (onError + rejected done + cleanup)
 * instead of waiting forever for stream events that will never arrive.
 */
export const buildStreamErrorOutput = (message: string) => ({
  event: 'error',
  data: JSON.stringify({ content: message }),
});

export const describeRequestFailure = (errorCode: unknown, errorMessage: unknown): string => {
  if (typeof errorMessage === 'string' && errorMessage.trim()) {
    return errorMessage;
  }
  if (errorCode === undefined || errorCode === null || `${errorCode}`.trim() === '') {
    return DEFAULT_FAILURE_MESSAGE;
  }
  return `${DEFAULT_FAILURE_MESSAGE} (${errorCode})`;
};

interface JavaQueryRequest {
  request: string;
  onSuccess: (data: unknown) => void;
  onFailure: (errorCode: unknown, errorMessage: unknown) => void;
}

interface DispatchDesktopStreamRequestOptions {
  requestId: string;
  serializedRequest: string;
  javaQuery?: (request: JavaQueryRequest) => unknown;
  publish: (eventName: string, output: ReturnType<typeof buildStreamErrorOutput>) => void;
  schedule?: (callback: () => void) => void;
}

const thrownFailureMessage = (error: unknown) => {
  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }
  return describeRequestFailure(undefined, error);
};

export const dispatchDesktopStreamRequest = ({
  requestId,
  serializedRequest,
  javaQuery,
  publish,
  schedule = queueMicrotask,
}: DispatchDesktopStreamRequestOptions) => {
  const publishFailure = (message: string) => {
    schedule(() => publish(streamEventName(requestId), buildStreamErrorOutput(message)));
  };

  if (!javaQuery) {
    publishFailure('Java Query is not available');
    return;
  }

  try {
    javaQuery({
      request: serializedRequest,
      onSuccess: (data) => {
        const errorMessage = extractEnvelopeErrorMessage(data);
        if (errorMessage !== undefined) {
          publishFailure(errorMessage);
        }
      },
      onFailure: (errorCode, errorMessage) => {
        publishFailure(describeRequestFailure(errorCode, errorMessage));
      },
    });
  } catch (error) {
    publishFailure(thrownFailureMessage(error));
  }
};

const parseJsonObject = (value: unknown): Record<string, any> | undefined => {
  if (typeof value === 'object' && value !== null) {
    return value as Record<string, any>;
  }
  if (typeof value !== 'string') {
    return undefined;
  }
  try {
    const parsed = JSON.parse(value);
    return typeof parsed === 'object' && parsed !== null ? parsed : undefined;
  } catch {
    return undefined;
  }
};

/**
 * Detects the ConsoleResult error envelope that the JCEF bridge returns through the
 * success callback when the controller fails before the stream is subscribed
 * (ConsoleHelper.error: actionType "error" + ActionResult {success: false}).
 * Returns undefined for regular acknowledgements.
 */
export const extractEnvelopeErrorMessage = (rawResponse: unknown): string | undefined => {
  const envelope = parseJsonObject(rawResponse);
  if (!envelope) {
    return undefined;
  }
  const actionResult = parseJsonObject(envelope.message);
  const failed = envelope.actionType === 'error' || actionResult?.success === false;
  if (!failed) {
    return undefined;
  }
  const errorMessage = actionResult?.errorMessage;
  return typeof errorMessage === 'string' && errorMessage.trim() ? errorMessage : DEFAULT_FAILURE_MESSAGE;
};
