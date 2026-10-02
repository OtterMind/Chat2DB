import { useCallback, useRef, useState } from 'react';
import type { IExecuteSqlParams, IManageResultData } from '@/typings';
import type { SqlExecutionEvent } from '@/service/sqlExecutionStream';
import useSqlExecutor from './useSqlExecutor';
import {
  createViewTablePagingState,
  getViewTablePagingErrorMessage,
  normalizeViewTablePageResults,
  reduceViewTablePagingEvent,
  type ViewTablePagingState,
} from './viewTablePagingModel';

export default function useViewTablePaging() {
  const requestSequenceRef = useRef<number>();
  const paramsRef = useRef<IExecuteSqlParams>();
  const confirmedResultRef = useRef<IManageResultData>();
  const pagingStateRef = useRef<ViewTablePagingState>();
  const [resultData, setResultData] = useState<ViewTablePagingState['result']>();

  const handleRequestStart = useCallback((requestSequence: number) => {
    requestSequenceRef.current = requestSequence;
    if (paramsRef.current) {
      pagingStateRef.current = createViewTablePagingState(
        requestSequence,
        paramsRef.current,
        confirmedResultRef.current,
      );
    }
  }, []);

  const handleExecutionEvent = useCallback((event: SqlExecutionEvent, requestSequence: number) => {
    if (requestSequenceRef.current !== requestSequence || !pagingStateRef.current) {
      return;
    }

    const transition = reduceViewTablePagingEvent(pagingStateRef.current, event, requestSequence);
    pagingStateRef.current = transition.state;
    if (transition.completedResult) {
      setResultData(transition.completedResult);
    }
  }, []);

  const { executeSQL, executing, stopExecuteSQL } = useSqlExecutor({
    onExecutionRequestStart: handleRequestStart,
    onExecutionEvent: handleExecutionEvent,
  });

  const executePage = useCallback(
    (params: IExecuteSqlParams, confirmedResult: IManageResultData) => {
      paramsRef.current = params;
      confirmedResultRef.current = confirmedResult;
      return executeSQL(params).then((data) => {
        const streamState = pagingStateRef.current;
        if (streamState?.params === params && streamState.errorMessage) {
          throw new Error(streamState.errorMessage);
        }
        const normalizedData = normalizeViewTablePageResults(data, params);
        const responseError = normalizedData
          .map(getViewTablePagingErrorMessage)
          .find((message): message is string => !!message);
        if (responseError) {
          throw new Error(responseError);
        }
        if (normalizedData.length) {
          setResultData(normalizedData[0]);
        }
        return data;
      });
    },
    [executeSQL],
  );

  return {
    resultData,
    executing,
    executePage,
    stopExecuteSQL,
  };
}
