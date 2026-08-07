import { useCallback, useRef, useState } from 'react';
import { IManageResultData, IExecuteSqlParams } from '@/typings';
import executeSqlServer from '@/service/executeSql';
import transactionServer from '@/service/transaction';
import type { ISqlEditorExecuteRequest } from '@/service/dmlRequest';
import useAbortRequest from './useAbortRequest';
import { isDesktop } from '@/utils/env';
import {
  SqlExecutionEvent,
  cancelSqlExecutionWithReconciliation,
  onSqlExecutionEvent,
  startSqlExecution,
} from '@/service/sqlExecutionStream';
import { v4 as uuidv4 } from 'uuid';
import { useGlobalStore } from '@/store/global';
import { settingSelectors } from '@/store/global/selectors';
import { useWorkspaceStore } from '@/store/workspace';
import {
  beginSqlExecutionRequest,
  canBeginSqlExecutionRequest,
  createSqlExecutionRequestTracker,
  finalizeSqlExecutionRequest,
  finishSqlExecutionRequest,
  isSqlExecutionCancellationRequested,
  requestSqlExecutionCancellation,
  setSqlExecutionRequestId,
  SqlExecutionBusyError,
  SqlExecutionRequestTracker,
} from '@/service/sqlExecutionRequestTracker';

interface IUseSqlExecutorProps {
  // Whether to return only one piece of data
  onlyOne?: boolean;
  onExecutionRequestStart?: (requestSequence: number) => void;
  onExecutionRequestStartError?: (
    error: unknown,
    requestSequence: number,
    params: IExecuteSqlParams,
  ) => void;
  onExecutionEvent?: (event: SqlExecutionEvent, requestSequence: number) => void;
  onExecutionCancellationError?: (error: unknown) => void;
}

interface DesktopExecutionControl {
  requestSequence: number;
  unsubscribe?: () => void;
  resolve: (results: IManageResultData[]) => void;
}

const useSqlExecutor = (props?: IUseSqlExecutorProps) => {
  const {
    onlyOne,
    onExecutionRequestStart,
    onExecutionRequestStartError,
    onExecutionEvent,
    onExecutionCancellationError,
  } = props || {};
  const defaultPageSize = useGlobalStore((state) => settingSelectors.currentBaseSetting(state).defaultPageSize);
  const [executing, setExecuting] = useState(false);
  const executionRequestTrackerRef = useRef<SqlExecutionRequestTracker>();
  if (!executionRequestTrackerRef.current) {
    executionRequestTrackerRef.current = createSqlExecutionRequestTracker();
  }
  const desktopExecutionControlRef = useRef<DesktopExecutionControl>();
  // interrupt request
  const [initSignal, abortRequest] = useAbortRequest();
  const canExecuteSQL = useCallback(
    () => canBeginSqlExecutionRequest(executionRequestTrackerRef.current!),
    [],
  );

  const requestDesktopCancellation = useCallback(
    (executionId: string, requestSequence: number) => {
      void cancelSqlExecutionWithReconciliation(executionId, {
        onExecutionMissing: () => {
          const control = desktopExecutionControlRef.current;
          if (!control || control.requestSequence !== requestSequence) {
            return;
          }
          try {
            onExecutionEvent?.(
              {
                executionId,
                occurredAtEpochMs: Date.now(),
                eventType: 'cancelled',
                message: {},
              },
              requestSequence,
            );
          } finally {
            control.unsubscribe?.();
            finishSqlExecutionRequest(executionRequestTrackerRef.current!, requestSequence);
            desktopExecutionControlRef.current = undefined;
            setExecuting(false);
            control.resolve([]);
          }
        },
        onError: (error) => {
          onExecutionCancellationError?.(error);
        },
      });
    },
    [onExecutionCancellationError, onExecutionEvent],
  );

  // When the console is in manual transaction mode, open a server-side transaction before the
  // first execution so subsequent executions reuse the bound connection. No-op in auto mode or
  // when a transaction is already open. Failures fall back to auto-commit (the execution still
  // runs, just without a bound transaction) so a transient begin error never blocks the user.
  const ensureTransactionBegun = useCallback(async (params: IExecuteSqlParams) => {
    const consoleId = params.consoleId;
    if (consoleId == null || typeof consoleId !== 'number') {
      return;
    }
    if (params.dataSourceId == null) {
      return;
    }
    const store = useWorkspaceStore.getState();
    const state = store.getTransactionState(consoleId);
    if (!state || state.mode !== 'manual' || state.inTransaction) {
      return;
    }
    try {
      const result = await transactionServer.beginTransaction({
        dataSourceId: params.dataSourceId,
        databaseName: params.databaseName,
        schemaName: params.schemaName,
        consoleId,
      });
      store.setTransactionState(consoleId, {
        inTransaction: result?.inTransaction ?? false,
        lastError: result?.lastError,
      });
    } catch (error) {
      // Begin failed: record the error and let execution proceed in auto-commit mode.
      store.setTransactionState(consoleId, { inTransaction: false, lastError: String(error) });
    }
  }, []);

  // execute sql
  const executeSQL = useCallback(async (params: IExecuteSqlParams): Promise<IManageResultData[]> => {
    if (params.dataSourceId == null) {
      return Promise.reject(new Error('dataSourceId is required'));
    }
    // In manual transaction mode, ensure a server-side transaction is open before executing so
    // this execution reuses the console's bound connection. begin is idempotent on the server.
    await ensureTransactionBegun(params);
    const executeSqlParams: ISqlEditorExecuteRequest = {
      dataSourceId: params.dataSourceId,
      databaseName: params.databaseName,
      schemaName: params.schemaName,
      sql: params.sql,
      consoleId: params.consoleId,
      applyId: params.applyId,
      single: params.single,
      pageNo: params.pageNo ?? 1,
      pageSize: params.pageSize ?? defaultPageSize,
      resultSetId: params.resultSetId,
      errorContinue: params.errorContinue,
      explain: params.explain,
    };
    const executionRequestTracker = executionRequestTrackerRef.current!;
    const requestSequence = beginSqlExecutionRequest(executionRequestTracker);
    if (requestSequence === undefined) {
      return Promise.reject(new SqlExecutionBusyError());
    }
    if (isDesktop && onExecutionEvent) {
      const requestUuid = uuidv4();
      setExecuting(true);
      return new Promise((resolve, reject) => {
        const subscription: { unsubscribe?: () => void } = {};
        const control: DesktopExecutionControl = { requestSequence, resolve };
        desktopExecutionControlRef.current = control;
        const clearControl = () => {
          if (desktopExecutionControlRef.current === control) {
            desktopExecutionControlRef.current = undefined;
          }
        };
        const rejectStart = (error: unknown) => {
          subscription.unsubscribe?.();
          clearControl();
          let rejection = error;
          if (finishSqlExecutionRequest(executionRequestTracker, requestSequence)) {
            setExecuting(false);
            try {
              onExecutionRequestStartError?.(error, requestSequence, params);
            } catch (callbackError) {
              rejection = callbackError;
            }
          }
          reject(rejection);
        };
        try {
          onExecutionRequestStart?.(requestSequence);
          subscription.unsubscribe = onSqlExecutionEvent(requestUuid, (event) => {
            const terminalEvent =
              event.eventType === 'finished' ||
              event.eventType === 'failed' ||
              event.eventType === 'cancelled';
            if (!terminalEvent) {
              onExecutionEvent(event, requestSequence);
              return;
            }

            let callbackError: unknown;
            try {
              onExecutionEvent(event, requestSequence);
            } catch (error) {
              callbackError = error;
            }
            subscription.unsubscribe?.();
            clearControl();
            if (finishSqlExecutionRequest(executionRequestTracker, requestSequence)) {
              setExecuting(false);
            }
            if (callbackError !== undefined) {
              reject(callbackError);
            } else if (event.eventType === 'failed') {
              reject(event.message);
            } else {
              resolve([]);
            }
          });
        } catch (error) {
          rejectStart(error);
          return;
        }
        control.unsubscribe = subscription.unsubscribe;
        Promise.resolve()
          .then(() => startSqlExecution(executeSqlParams, requestUuid))
          .then((res) => {
            if (!res?.executionId) {
              rejectStart(getStartExecutionError(res));
              return;
            }
            const executionIdAttached = setSqlExecutionRequestId(
              executionRequestTracker,
              requestSequence,
              res.executionId,
            );
            if (
              executionIdAttached &&
              isSqlExecutionCancellationRequested(executionRequestTracker, requestSequence)
            ) {
              requestDesktopCancellation(res.executionId, requestSequence);
            }
          })
          .catch((err) => {
            rejectStart(err);
          });
      });
    }
    setExecuting(true);
    const request = Promise.resolve()
      .then(() =>
        executeSqlServer.executeSql(executeSqlParams, {
          signal: initSignal(),
        }),
      )
      .then((data) => (onlyOne ? (data[0] ? [data[0]] : []) : data));
    return finalizeSqlExecutionRequest(executionRequestTracker, requestSequence, request, () => {
      setExecuting(false);
    });
  }, [
    defaultPageSize,
    ensureTransactionBegun,
    initSignal,
    onlyOne,
    onExecutionEvent,
    onExecutionRequestStart,
    onExecutionRequestStartError,
    requestDesktopCancellation,
  ]);

  // Stop executing sql
  const stopExecuteSQL = useCallback(() => {
    const executionRequestTracker = executionRequestTrackerRef.current!;
    if (isDesktop && onExecutionEvent) {
      const activeRequestSequence = executionRequestTracker.activeRequestSequence;
      const activeExecutionId = requestSqlExecutionCancellation(executionRequestTracker);
      if (activeExecutionId && activeRequestSequence !== undefined) {
        requestDesktopCancellation(activeExecutionId, activeRequestSequence);
      }
      return;
    }
    abortRequest();
  }, [abortRequest, onExecutionEvent, requestDesktopCancellation]);

  return {
    executing,
    canExecuteSQL,
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
