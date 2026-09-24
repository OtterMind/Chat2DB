import type { SqlxStatus } from '@/typings/settings';

/** A redetect is not an operation: it only re-reads the status, so it never enters this state. */
export type SqlxPendingOperation = 'installing' | 'updating' | 'checking';

export interface SqlxLifecycleState {
  status: SqlxStatus | null;
  pending: SqlxPendingOperation | null;
  pendingOperationId: string | null;
  error: string | null;
  loaded: boolean;
}

export type SqlxLifecycleEvent =
  | { type: 'START'; operation: SqlxPendingOperation; operationId: string }
  | { type: 'STATUS'; status: SqlxStatus }
  | { type: 'FAILURE'; operationId: string; error: string };

export const initialSqlxLifecycleState: SqlxLifecycleState = {
  status: null,
  pending: null,
  pendingOperationId: null,
  error: null,
  loaded: false,
};

/** Whether the reported operation is still running; a failed step is a finished operation. */
export function isSqlxOperationRunning(status: SqlxStatus | null | undefined): boolean {
  const step = status?.operation?.step;
  return !!step && step !== 'failed';
}

export function reduceSqlxLifecycleState(
  state: SqlxLifecycleState,
  event: SqlxLifecycleEvent,
): SqlxLifecycleState {
  if (event.type === 'START') {
    return { ...state, pending: event.operation, pendingOperationId: event.operationId, error: null };
  }
  if (event.type === 'STATUS') {
    const runningOperationId = event.status.operation?.operationId;
    if (runningOperationId && state.pendingOperationId && runningOperationId !== state.pendingOperationId) {
      return state;
    }
    const finished = !!state.pendingOperationId && !isSqlxOperationRunning(event.status);
    return {
      status: event.status,
      loaded: true,
      pending: finished ? null : state.pending,
      pendingOperationId: finished ? null : state.pendingOperationId,
      error: null,
    };
  }
  if (state.pendingOperationId !== null && event.operationId !== state.pendingOperationId) {
    return state;
  }
  return { ...state, loaded: true, pending: null, pendingOperationId: null, error: event.error };
}

export function createSqlxOperationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36)
    .slice(2)}`;
}

export function canStartSqlxOperation(activeOperationId: string | null): boolean {
  return activeOperationId === null;
}

export function getSqlxErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}
