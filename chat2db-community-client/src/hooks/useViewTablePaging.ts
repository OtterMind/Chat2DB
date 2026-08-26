import { useCallback, useRef, useState } from 'react';
import type { IExecuteSqlParams } from '@/typings';
import type { SqlExecutionEvent } from '@/service/sqlExecutionStream';
import useSqlExecutor from './useSqlExecutor';
import {
  createViewTablePagingState,
  reduceViewTablePagingEvent,
  type ViewTablePagingState,
} from './viewTablePagingModel';

export default function useViewTablePaging() {
  const requestSequenceRef = useRef<number>();
  const paramsRef = useRef<IExecuteSqlParams>();
  const pagingStateRef = useRef<ViewTablePagingState>();
  const [resultData, setResultData] = useState<ViewTablePagingState['result']>();

  const handleRequestStart = useCallback((requestSequence: number) => {
    requestSequenceRef.current = requestSequence;
    if (paramsRef.current) {
      pagingStateRef.current = createViewTablePagingState(requestSequence, paramsRef.current);
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
    (params: IExecuteSqlParams) => {
      paramsRef.current = params;
      return executeSQL(params).then((data) => {
        if (data.length) {
          setResultData(data[0]);
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
