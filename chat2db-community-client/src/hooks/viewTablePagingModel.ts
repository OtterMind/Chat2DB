import type { IExecuteSqlParams, IManageResultData } from '@/typings';
import type { SqlExecutionEvent } from '@/service/sqlExecutionStream';
import { processResultDataList } from '@/utils/resultData';

export type ViewTableStreamEventType = 'resultStarted' | 'rows' | 'resultFinished';

export interface ViewTablePagingState {
  requestSequence: number;
  params: IExecuteSqlParams;
  confirmedResult?: IManageResultData;
  result?: IManageResultData;
  errorMessage?: string;
}

export interface ViewTablePagingTransition {
  state: ViewTablePagingState;
  completedResult?: IManageResultData;
  errorMessage?: string;
}

function isResultEvent(eventType: SqlExecutionEvent['eventType']): eventType is ViewTableStreamEventType {
  return eventType === 'resultStarted' || eventType === 'rows' || eventType === 'resultFinished';
}

function mergeResultEvent(
  current: IManageResultData | undefined,
  eventResult: IManageResultData,
  eventType: ViewTableStreamEventType,
) {
  const dataList =
    eventType === 'rows'
      ? [...(current?.dataList || []), ...(eventResult.dataList || [])]
      : eventType === 'resultFinished' && current?.dataList?.length
        ? current.dataList
        : eventResult.dataList || [];

  if (!current) {
    return { ...eventResult, dataList };
  }

  return {
    ...current,
    ...eventResult,
    uuid: current.uuid,
    executeSqlParams: current.executeSqlParams || eventResult.executeSqlParams,
    dataList,
  };
}

export function createViewTablePagingState(
  requestSequence: number,
  params: IExecuteSqlParams,
  confirmedResult?: IManageResultData,
) {
  return { requestSequence, params, confirmedResult } satisfies ViewTablePagingState;
}

export function normalizeViewTablePageResults(
  results: IManageResultData[],
  params: IExecuteSqlParams,
) {
  return processResultDataList(results, params);
}

export function getViewTablePagingErrorMessage(result: IManageResultData) {
  if (result.success !== false) {
    return undefined;
  }
  return result.message?.trim() || result.description?.trim() || 'SQL execution failed';
}

export function reduceViewTablePagingEvent(
  state: ViewTablePagingState,
  event: SqlExecutionEvent,
  requestSequence: number,
): ViewTablePagingTransition {
  if (state.requestSequence !== requestSequence) {
    return { state };
  }
  if (event.eventType === 'cancelled') {
    const completedResult = state.confirmedResult ? { ...state.confirmedResult } : undefined;
    return {
      state: { ...state, result: undefined, errorMessage: undefined },
      completedResult,
    };
  }
  if (!isResultEvent(event.eventType)) {
    return { state };
  }

  const eventResult = normalizeViewTablePageResults([event.message], state.params)[0];
  if (!eventResult) {
    return { state };
  }
  const errorMessage = getViewTablePagingErrorMessage(eventResult);
  if (errorMessage) {
    return {
      state: { ...state, result: undefined, errorMessage },
      errorMessage,
    };
  }

  const result = mergeResultEvent(state.result, eventResult, event.eventType);
  const nextState = { ...state, result };
  return {
    state: nextState,
    completedResult: event.eventType === 'resultFinished' ? result : undefined,
  };
}

export function replaceViewTableResult(
  current: IManageResultData[] | undefined,
  pagedResult: IManageResultData,
) {
  const currentResult = current?.[0];
  const baseQuerySql = currentResult?.originalSql || currentResult?.sql || currentResult?.executeSqlParams?.sql;

  return [
    {
      ...pagedResult,
      uuid: currentResult?.uuid || pagedResult.uuid,
      originalSql: baseQuerySql || pagedResult.originalSql,
    },
  ];
}
