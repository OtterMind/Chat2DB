import { useCallback, useRef, useState } from 'react';
import { IManageResultData, IExecuteSqlParams } from '@/typings';
import executeSqlServer from '@/service/executeSql';
import useAbortRequest from './useAbortRequest';
import { isDesktop } from '@/utils/env';
import {
  SqlExecutionEvent,
  cancelSqlExecution,
  onSqlExecutionEvent,
  startSqlExecution,
} from '@/service/sqlExecutionStream';
import { v4 as uuidv4 } from 'uuid';
import { useGlobalStore } from '@/store/global';
import { settingSelectors } from '@/store/global/selectors';
import {
  beginSqlExecutionRequest,
  createSqlExecutionRequestTracker,
  finishSqlExecutionRequest,
  getActiveSqlExecutionId,
  setSqlExecutionRequestId,
  SqlExecutionRequestTracker,
} from '@/service/sqlExecutionRequestTracker';

interface IUseSqlExecutorProps {
  // Whether to return only one piece of data
  onlyOne?: boolean;
  onExecutionRequestStart?: (requestSequence: number) => void;
  onExecutionEvent?: (event: SqlExecutionEvent, requestSequence: number) => void;
}

const useSqlExecutor = (props?: IUseSqlExecutorProps) => {
  const { onlyOne, onExecutionRequestStart, onExecutionEvent } = props || {};
  const defaultPageSize = useGlobalStore((state) => settingSelectors.currentBaseSetting(state).defaultPageSize);
  const [executing, setExecuting] = useState(false);
  const executionRequestTrackerRef = useRef<SqlExecutionRequestTracker>();
  if (!executionRequestTrackerRef.current) {
    executionRequestTrackerRef.current = createSqlExecutionRequestTracker();
  }
  // interrupt request
  const [initSignal, abortRequest] = useAbortRequest();

  // Process data
  const handleData = (params: { data: any[] }) => {
    const { data } = params;
    if (onlyOne) {
      return data[0] ? [data[0]] : [];
    }
    return data;
  };

  // execute sql
  const executeSQL = useCallback((params: IExecuteSqlParams): Promise<IManageResultData[]> => {
    const executeSqlParams = {
      ...params,
      pageNo: params.pageNo ?? 1,
      pageSize: params.pageSize ?? defaultPageSize,
    };
    if (isDesktop && onExecutionEvent) {
      const requestUuid = uuidv4();
      const executionRequestTracker = executionRequestTrackerRef.current;
      const requestSequence = beginSqlExecutionRequest(executionRequestTracker);
      onExecutionRequestStart?.(requestSequence);
      setExecuting(true);
      return new Promise((resolve, reject) => {
        const subscription: { unsubscribe?: () => void } = {};
        subscription.unsubscribe = onSqlExecutionEvent(requestUuid, (event) => {
          onExecutionEvent(event, requestSequence);
          if (event.eventType === 'finished') {
            subscription.unsubscribe?.();
            if (finishSqlExecutionRequest(executionRequestTracker, requestSequence)) {
              setExecuting(false);
            }
            resolve([]);
          }
          if (event.eventType === 'failed' || event.eventType === 'cancelled') {
            subscription.unsubscribe?.();
            if (finishSqlExecutionRequest(executionRequestTracker, requestSequence)) {
              setExecuting(false);
            }
            if (event.eventType === 'cancelled') {
              resolve([]);
            } else {
              reject(event.message);
            }
          }
        });
        startSqlExecution(executeSqlParams, requestUuid)
          .then((res) => {
            if (!res?.executionId) {
              subscription.unsubscribe?.();
              if (finishSqlExecutionRequest(executionRequestTracker, requestSequence)) {
                setExecuting(false);
              }
              reject(getStartExecutionError(res));
              return;
            }
            setSqlExecutionRequestId(executionRequestTracker, requestSequence, res.executionId);
          })
          .catch((err) => {
            subscription.unsubscribe?.();
            if (finishSqlExecutionRequest(executionRequestTracker, requestSequence)) {
              setExecuting(false);
            }
            reject(err);
          });
      });
    }
    return new Promise((resolve, reject) => {
      // Parameters for executing sql
      setExecuting(true);

      // execute sql
      return executeSqlServer
        .executeSql(executeSqlParams, {
          signal: initSignal(),
        })
        .then((res) => {
          const data = handleData({ data: res });
          resolve(data);
        })
        .catch((err) => {
          reject(err);
        })
        .finally(() => {
          setExecuting(false);
        });
    });
  }, [defaultPageSize, onExecutionEvent, onExecutionRequestStart]);

  // Stop executing sql
  const stopExecuteSQL = useCallback(() => {
    const activeExecutionId = getActiveSqlExecutionId(executionRequestTrackerRef.current!);
    if (isDesktop && activeExecutionId) {
      cancelSqlExecution(activeExecutionId);
      return;
    }
    abortRequest();
    setExecuting(false);
  }, [abortRequest]);

  return {
    executing,
    executeSQL,
    stopExecuteSQL,
  };
};

function getStartExecutionError(response: any) {
  const message = response?.message;
  if (typeof message === 'string') {
    return message;
  }
  if (message?.message) {
    return message.message;
  }
  if (message?.errorMessage) {
    return message.errorMessage;
  }
  if (response?.errorMessage) {
    return response.errorMessage;
  }
  return 'SQL execution failed to start';
}

export default useSqlExecutor;
