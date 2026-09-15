import type { IInnodbStatusResponse } from '@/service/sql';
import { beginLatestRequest, isLatestRequest, type RequestGenerationRef } from '@/utils/latestRequest';

export interface InnodbStatusViewState {
  loading: boolean;
  result: IInnodbStatusResponse | null;
  lastSuccessAt: string | null;
  error: string | null;
}

export const initialInnodbStatusViewState: InnodbStatusViewState = {
  loading: false,
  result: null,
  lastSuccessAt: null,
  error: null,
};

export function beginInnodbStatusRefresh(state: InnodbStatusViewState): InnodbStatusViewState {
  return {
    ...state,
    loading: true,
    error: null,
  };
}

export function applyInnodbStatusSuccess(
  _state: InnodbStatusViewState,
  result: IInnodbStatusResponse,
  receivedAt: string,
): InnodbStatusViewState {
  return {
    loading: false,
    result,
    lastSuccessAt: result.capturedAt || receivedAt,
    error: null,
  };
}

export function applyInnodbStatusFailure(
  state: InnodbStatusViewState,
  error: unknown,
): InnodbStatusViewState {
  return {
    ...state,
    loading: false,
    error: formatInnodbStatusError(error),
  };
}

export function formatInnodbStatusError(error: unknown): string {
  if (typeof error === 'string') {
    return error;
  }
  if (error && typeof error === 'object') {
    const candidate = error as { errorMessage?: string; message?: string };
    return candidate.errorMessage || candidate.message || 'InnoDB status refresh failed.';
  }
  return 'InnoDB status refresh failed.';
}

export function getInnodbStatusCopyText(result: IInnodbStatusResponse | null): string {
  return result?.rawText || '';
}

export async function loadLatestInnodbStatus(
  requestGenerationRef: RequestGenerationRef,
  loadStatus: () => Promise<IInnodbStatusResponse>,
  updateState: (updater: (state: InnodbStatusViewState) => InnodbStatusViewState) => void,
  receivedAt: () => string,
) {
  const requestGeneration = beginLatestRequest(requestGenerationRef);
  updateState(beginInnodbStatusRefresh);
  try {
    const result = await loadStatus();
    if (isLatestRequest(requestGenerationRef, requestGeneration)) {
      updateState((state) => applyInnodbStatusSuccess(state, result, receivedAt()));
    }
  } catch (error) {
    if (isLatestRequest(requestGenerationRef, requestGeneration)) {
      updateState((state) => applyInnodbStatusFailure(state, error));
    }
  }
}
