import type { IExecuteSqlParams, IManageResultData } from '@/typings';
import type { SqlExecutionEvent } from '@/service/sqlExecutionStream';
import { processResultDataList } from '@/utils/resultData';

export type ViewTableStreamEventType = 'resultStarted' | 'rows' | 'resultFinished';

export interface ViewTablePagingState {
  requestSequence: number;
  params: IExecuteSqlParams;
  result?: IManageResultData;
}

export interface ViewTablePagingTransition {
  state: ViewTablePagingState;
  completedResult?: IManageResultData;
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

export function createViewTablePagingState(requestSequence: number, params: IExecuteSqlParams) {
  return { requestSequence, params } satisfies ViewTablePagingState;
}

export function normalizeViewTablePageResults(
  results: IManageResultData[],
  params: IExecuteSqlParams,
) {
  return processResultDataList(results, params);
}

export function reduceViewTablePagingEvent(
  state: ViewTablePagingState,
  event: SqlExecutionEvent,
  requestSequence: number,
): ViewTablePagingTransition {
  if (state.requestSequence !== requestSequence || !isResultEvent(event.eventType)) {
    return { state };
  }

  const eventResult = normalizeViewTablePageResults([event.message], state.params)[0];
  if (!eventResult) {
    return { state };
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
  return [
    {
      ...pagedResult,
      uuid: current?.[0]?.uuid || pagedResult.uuid,
    },
  ];
}
