import type { IInnodbStatusResponse } from '@/service/sql';

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
