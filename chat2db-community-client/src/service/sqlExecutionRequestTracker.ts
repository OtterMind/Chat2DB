import { beginLatestRequest, isLatestRequest, RequestGenerationRef } from '@/utils/latestRequest';

export interface SqlExecutionRequestTracker {
  generationRef: RequestGenerationRef;
  executionId?: string;
}

export function createSqlExecutionRequestTracker(): SqlExecutionRequestTracker {
  return { generationRef: { current: 0 } };
}

export function beginSqlExecutionRequest(tracker: SqlExecutionRequestTracker) {
  tracker.executionId = undefined;
  return beginLatestRequest(tracker.generationRef);
}

export function setSqlExecutionRequestId(
  tracker: SqlExecutionRequestTracker,
  requestSequence: number,
  executionId: string,
) {
  if (!isLatestRequest(tracker.generationRef, requestSequence)) {
    return false;
  }
  tracker.executionId = executionId;
  return true;
}

export function finishSqlExecutionRequest(tracker: SqlExecutionRequestTracker, requestSequence: number) {
  if (!isLatestRequest(tracker.generationRef, requestSequence)) {
    return false;
  }
  tracker.executionId = undefined;
  return true;
}

export function getActiveSqlExecutionId(tracker: SqlExecutionRequestTracker) {
  return tracker.executionId;
}
