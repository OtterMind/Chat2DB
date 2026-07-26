import { useCallback, useState } from 'react';
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

interface IUseSqlExecutorProps {
  // Whether to return only one piece of data
  onlyOne?: boolean;
  onExecutionEvent?: (event: SqlExecutionEvent) => void;
}

const useSqlExecutor = (props?: IUseSqlExecutorProps) => {
  const { onlyOne, onExecutionEvent } = props || {};
  const defaultPageSize = useGlobalStore((state) => settingSelectors.currentBaseSetting(state).defaultPageSize);
  const [executing, setExecuting] = useState(false);
  const [executionId, setExecutionId] = useState<string>();
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
      setExecuting(true);
      return new Promise((resolve, reject) => {
        const subscription: { unsubscribe?: () => void } = {};
        subscription.unsubscribe = onSqlExecutionEvent(requestUuid, (event) => {
          onExecutionEvent(event);
          if (event.eventType === 'finished') {
            subscription.unsubscribe?.();
            setExecuting(false);
            setExecutionId(undefined);
            resolve([]);
          }
          if (event.eventType === 'failed' || event.eventType === 'cancelled') {
            subscription.unsubscribe?.();
            setExecuting(false);
            setExecutionId(undefined);
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
              setExecuting(false);
              reject(getStartExecutionError(res));
              return;
            }
            setExecutionId(res.executionId);
          })
          .catch((err) => {
            subscription.unsubscribe?.();
            setExecuting(false);
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
  }, [defaultPageSize, onExecutionEvent]);

  // Stop executing sql
  const stopExecuteSQL = useCallback(() => {
    if (isDesktop && executionId) {
      cancelSqlExecution(executionId);
      return;
    }
    abortRequest();
    setExecuting(false);
  }, [abortRequest, executionId]);

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
