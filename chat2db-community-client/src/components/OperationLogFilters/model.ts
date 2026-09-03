import type { IGetHistoryListParams, OperationTypeEnum } from '@/service/history';

export interface OperationLogFilterValues {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  searchKey?: string;
}

export type OperationLogFilterChange =
  | { field: 'dataSourceId'; value?: number }
  | { field: 'databaseName' | 'schemaName' | 'searchKey'; value?: string };

function normalizeText(value?: string) {
  const normalizedValue = value?.trim();
  return normalizedValue || undefined;
}

export function normalizeOperationLogFilters(filters: OperationLogFilterValues): OperationLogFilterValues {
  const normalizedFilters: OperationLogFilterValues = {};

  if (filters.dataSourceId !== undefined) {
    normalizedFilters.dataSourceId = filters.dataSourceId;
  }

  const databaseName = normalizeText(filters.databaseName);
  const schemaName = normalizeText(filters.schemaName);
  const searchKey = normalizeText(filters.searchKey);

  if (databaseName) {
    normalizedFilters.databaseName = databaseName;
  }
  if (schemaName) {
    normalizedFilters.schemaName = schemaName;
  }
  if (searchKey) {
    normalizedFilters.searchKey = searchKey;
  }

  return normalizedFilters;
}

export function updateOperationLogFilters(
  filters: OperationLogFilterValues,
  change: OperationLogFilterChange,
): OperationLogFilterValues {
  if (change.field === 'dataSourceId') {
    return {
      ...filters,
      dataSourceId: change.value,
      databaseName: undefined,
      schemaName: undefined,
    };
  }

  if (change.field === 'databaseName') {
    return {
      ...filters,
      databaseName: change.value,
      schemaName: undefined,
    };
  }

  return {
    ...filters,
    [change.field]: change.value,
  };
}

export interface OperationLogPageRequestState {
  currentGeneration: number;
  finished: boolean;
  activeRequestGeneration?: number;
}

export function shouldStartOperationLogPageRequest(
  generation: number,
  replace: boolean,
  state: OperationLogPageRequestState,
): boolean {
  if (generation !== state.currentGeneration) {
    return false;
  }
  if (!replace && state.finished) {
    return false;
  }
  return state.activeRequestGeneration !== generation;
}

export function shouldApplyOperationLogPageResponse(
  generation: number,
  state: { mounted: boolean; currentGeneration: number },
): boolean {
  return state.mounted && generation === state.currentGeneration;
}

export function buildOperationLogListParams(
  filters: OperationLogFilterValues,
  pageNo: number,
  pageSize: number,
  operationType: OperationTypeEnum,
): IGetHistoryListParams {
  return {
    pageNo,
    pageSize,
    operationType,
    ...normalizeOperationLogFilters(filters),
  };
}
