import { beginLatestRequest, isLatestRequest, RequestGenerationRef } from '@/utils/latestRequest';

export interface SqlExecutionRequestTracker {
  generationRef: RequestGenerationRef;
  activeRequestSequence?: number;
  executionId?: string;
  cancelRequested?: boolean;
}

export const SQL_EXECUTION_BUSY_ERROR_CODE = 'SQL_EXECUTION_BUSY';

export class SqlExecutionBusyError extends Error {
  readonly code = SQL_EXECUTION_BUSY_ERROR_CODE;

  constructor() {
    super('SQL execution is already in progress');
    this.name = 'SqlExecutionBusyError';
  }
}

export function createSqlExecutionRequestTracker(): SqlExecutionRequestTracker {
  return { generationRef: { current: 0 } };
}

export function beginSqlExecutionRequest(tracker: SqlExecutionRequestTracker) {
  if (!canBeginSqlExecutionRequest(tracker)) {
    return undefined;
  }
  tracker.executionId = undefined;
  tracker.cancelRequested = false;
  const requestSequence = beginLatestRequest(tracker.generationRef);
  tracker.activeRequestSequence = requestSequence;
  return requestSequence;
}

export function canBeginSqlExecutionRequest(tracker: SqlExecutionRequestTracker) {
  return tracker.activeRequestSequence === undefined;
}

export function setSqlExecutionRequestId(
  tracker: SqlExecutionRequestTracker,
  requestSequence: number,
  executionId: string,
) {
  if (
    !isLatestRequest(tracker.generationRef, requestSequence) ||
    tracker.activeRequestSequence !== requestSequence
  ) {
    return false;
  }
  tracker.executionId = executionId;
  return true;
}

export function finishSqlExecutionRequest(tracker: SqlExecutionRequestTracker, requestSequence: number) {
  if (
    !isLatestRequest(tracker.generationRef, requestSequence) ||
    tracker.activeRequestSequence !== requestSequence
  ) {
    return false;
  }
  tracker.activeRequestSequence = undefined;
  tracker.executionId = undefined;
  tracker.cancelRequested = false;
  return true;
}

export function finalizeSqlExecutionRequest<T>(
  tracker: SqlExecutionRequestTracker,
  requestSequence: number,
  request: Promise<T>,
  onFinished?: () => void,
) {
  return request.finally(() => {
    if (finishSqlExecutionRequest(tracker, requestSequence)) {
      onFinished?.();
    }
  });
}

export function getActiveSqlExecutionId(tracker: SqlExecutionRequestTracker) {
  return tracker.executionId;
}

export function requestSqlExecutionCancellation(tracker: SqlExecutionRequestTracker) {
  if (tracker.activeRequestSequence === undefined) {
    return undefined;
  }
  tracker.cancelRequested = true;
  return tracker.executionId;
}

export function isSqlExecutionCancellationRequested(
  tracker: SqlExecutionRequestTracker,
  requestSequence: number,
) {
  return tracker.activeRequestSequence === requestSequence && tracker.cancelRequested === true;
}
