export interface RequestSettlementOptions<T> {
  beforeSettle: () => void;
  success: boolean;
  successValue: T;
  error: unknown;
  suppressErrorReport: boolean;
  resolve?: (value: T) => void;
  reject?: (reason?: unknown) => void;
  reportError: () => void;
  cleanup: () => void;
}

export interface TrackedCommandLineRequest {
  requestTimeoutTimer?: unknown;
  resolve?: (value: unknown) => void;
  reject?: (reason?: unknown) => void;
  options: { fullResponse?: boolean };
  requestData: unknown;
}

export interface CommandLineRequestRegistry<TRequest extends TrackedCommandLineRequest> {
  get: (requestId: string) => TRequest | undefined;
  remove: (requestId: string) => void;
  clearTimer: (timer: unknown) => void;
}

export interface CommandLineResponseMessage {
  success?: boolean;
  data?: any;
  errorCode?: any;
  errorMessage?: string;
  errorDetail?: any;
  solutionLink?: string;
  eventualUrl?: string;
  [key: string]: unknown;
}

export function settleCommandLineRequest<T>(options: RequestSettlementOptions<T>) {
  try {
    try {
      options.beforeSettle();
    } catch (error) {
      options.reject?.(error);
      return;
    }
    if (options.success) {
      options.resolve?.(options.successValue);
      return;
    }

    options.reject?.(options.error);
    if (!options.suppressErrorReport) {
      options.reportError();
    }
  } finally {
    options.cleanup();
  }
}

function cleanupRequest<TRequest extends TrackedCommandLineRequest>(
  requestId: string,
  request: TRequest,
  registry: CommandLineRequestRegistry<TRequest>,
) {
  try {
    if (request.requestTimeoutTimer !== null && request.requestTimeoutTimer !== undefined) {
      registry.clearTimer(request.requestTimeoutTimer);
    }
  } finally {
    registry.remove(requestId);
  }
}

export function cleanupTrackedCommandLineRequest<TRequest extends TrackedCommandLineRequest>(
  requestId: string,
  registry: CommandLineRequestRegistry<TRequest>,
) {
  const request = registry.get(requestId);
  if (!request) {
    return false;
  }
  cleanupRequest(requestId, request, registry);
  return true;
}

export function rejectTrackedCommandLineRequest<TRequest extends TrackedCommandLineRequest>(
  requestId: string,
  reason: unknown,
  registry: CommandLineRequestRegistry<TRequest>,
) {
  const request = registry.get(requestId);
  if (!request) {
    return false;
  }
  try {
    request.reject?.(reason);
  } finally {
    cleanupRequest(requestId, request, registry);
  }
  return true;
}

export function settleTrackedCommandLineResponse<TRequest extends TrackedCommandLineRequest>(options: {
  requestId: string;
  message: CommandLineResponseMessage;
  registry: CommandLineRequestRegistry<TRequest>;
  beforeSettle: (request: TRequest, message: CommandLineResponseMessage) => void;
  suppressErrorReport: (errorCode: unknown) => boolean;
  reportError: (request: TRequest, message: CommandLineResponseMessage) => void;
}) {
  const request = options.registry.get(options.requestId);
  if (!request) {
    return false;
  }

  settleCommandLineRequest({
    beforeSettle: () => options.beforeSettle(request, options.message),
    success: options.message.success === true,
    successValue: request.options.fullResponse ? options.message : options.message.data,
    error: {
      errorCode: options.message.errorCode,
      errorMessage: options.message.errorMessage,
    },
    suppressErrorReport: options.suppressErrorReport(options.message.errorCode),
    resolve: request.resolve,
    reject: request.reject,
    reportError: () => options.reportError(request, options.message),
    cleanup: () => cleanupRequest(options.requestId, request, options.registry),
  });
  return true;
}
