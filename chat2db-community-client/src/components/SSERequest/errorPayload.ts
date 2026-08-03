import { ErrorCode, ErrorCodesWithoutToast } from '@/constants/request';
import { staticMessage } from '@chat2db/ui';
import interceptorsResponse from '@/service/interceptorsResponse';

interface ISSEErrorPayload {
  success?: boolean;
  errorCode?: string;
  errorMessage?: string;
}

const isObject = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const tryParseJson = (value: unknown) => {
  if (typeof value !== 'string') {
    return value;
  }

  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
};

const tryExtractEmbeddedJson = (value: unknown) => {
  if (typeof value !== 'string') {
    return undefined;
  }

  const firstBraceIndex = value.indexOf('{');
  const lastBraceIndex = value.lastIndexOf('}');
  if (firstBraceIndex < 0 || lastBraceIndex <= firstBraceIndex) {
    return undefined;
  }

  const possibleJson = value.slice(firstBraceIndex, lastBraceIndex + 1);
  const parsedValue = tryParseJson(possibleJson);
  return isObject(parsedValue) ? parsedValue : undefined;
};

const normalizeErrorPayload = (value: unknown): ISSEErrorPayload | undefined => {
  if (value === ErrorCode.FreeTrialUSageLimit) {
    return {
      success: false,
      errorCode: ErrorCode.FreeTrialUSageLimit,
    };
  }

  const parsedValue = tryParseJson(value);

  if (!isObject(parsedValue)) {
    const embeddedPayload = tryExtractEmbeddedJson(parsedValue);
    if (embeddedPayload) {
      return normalizeErrorPayload(embeddedPayload);
    }
    return undefined;
  }

  if ('data' in parsedValue) {
    const nestedPayload = normalizeErrorPayload(parsedValue.data);
    if (nestedPayload) {
      return nestedPayload;
    }
  }

  const messageType = parsedValue.messageType || parsedValue.type;
  if (messageType === 'error' && 'content' in parsedValue) {
    const nestedPayload = normalizeErrorPayload(parsedValue.content);
    if (nestedPayload) {
      return nestedPayload;
    }
  }

  if (typeof parsedValue.errorCode !== 'string') {
    return undefined;
  }

  return parsedValue as ISSEErrorPayload;
};

export const handleSSEErrorPayload = (value: unknown, requestParams?: any) => {
  const payload = normalizeErrorPayload(value);

  if (!payload?.errorCode) {
    return false;
  }

  interceptorsResponse({
    errorCode: payload.errorCode as ErrorCode,
    errorMessage: payload.errorMessage || '',
    requestParams,
    errorLevel: false,
    permissionError: false,
  });

  if (!ErrorCodesWithoutToast.includes(payload.errorCode as ErrorCode) && payload.errorMessage) {
    staticMessage.error(payload.errorMessage);
  }

  return true;
};

/**
 * Subscription chat failures are valid stream events, not transport/API envelope failures.
 * They must reach the chat renderer so the current message can show a durable error state.
 */
export const isApplicationStreamErrorPayload = (value: unknown) => {
  const parsedValue = tryParseJson(value);
  if (!isObject(parsedValue)) {
    return false;
  }
  const messageType = parsedValue.messageType || parsedValue.type;
  return messageType === 'error' && typeof parsedValue.errorCode === 'string';
};

export const parseSSEChunkData = <T = unknown>(value: unknown): T | undefined => {
  const parsedValue = tryParseJson(value);

  if (!isObject(parsedValue)) {
    return undefined;
  }

  return parsedValue as T;
};
