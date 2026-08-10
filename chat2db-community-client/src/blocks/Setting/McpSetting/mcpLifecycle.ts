import type { McpStatus } from '@/typings/settings';

export type McpOperation = 'loading' | 'saving' | 'restarting';

export interface McpLifecycleState {
  status: McpStatus | null;
  pendingOperation: McpOperation | null;
  pendingOperationId: string | null;
  error: string | null;
}

export type McpLifecycleEvent =
  | { type: 'START'; operation: McpOperation; operationId: string }
  | { type: 'STATUS'; status: McpStatus }
  | { type: 'FAILURE'; operationId: string; error: string };

export const initialMcpLifecycleState: McpLifecycleState = {
  status: null,
  pendingOperation: null,
  pendingOperationId: null,
  error: null,
};

export function reduceMcpLifecycleState(
  state: McpLifecycleState,
  event: McpLifecycleEvent,
): McpLifecycleState {
  if (event.type === 'START') {
    return {
      ...state,
      pendingOperation: event.operation,
      pendingOperationId: event.operationId,
      error: null,
    };
  }
  if (event.type === 'STATUS') {
    if (event.status.operationId !== state.pendingOperationId) {
      return state;
    }
    return {
      status: event.status,
      pendingOperation: null,
      pendingOperationId: null,
      error: null,
    };
  }
  if (event.operationId !== state.pendingOperationId) {
    return state;
  }
  return {
    ...state,
    pendingOperation: null,
    pendingOperationId: null,
    error: event.error,
  };
}

export function createMcpOperationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36)
    .slice(2)}`;
}

export function canStartMcpOperation(activeOperationId: string | null): boolean {
  return activeOperationId === null;
}

export function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}
