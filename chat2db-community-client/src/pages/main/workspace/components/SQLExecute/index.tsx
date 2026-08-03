import {
  memo,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
  useCallback,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
} from 'react';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';
import SearchResult from '@/blocks/SearchResult';
import {
  createExecutionConsoleKeepHistoryStorageKey,
  getExecutionConsolePreferenceStorage,
  persistExecutionConsoleKeepHistory,
  readExecutionConsoleKeepHistory,
  subscribeExecutionConsoleKeepHistory,
} from '@/blocks/SearchResult/components/ExecutionConsole/executionConsolePreferences';
import {
  createResultTabKeepHistoryStorageKey,
  getResultTabPreferenceStorage,
  persistResultTabKeepHistory,
  readResultTabKeepHistory,
  subscribeResultTabKeepHistory,
} from '@/blocks/SearchResult/resultTabPreferences';
import { useWorkspaceStore } from '@/store/workspace';
import {
  IConsoleReturnExecuteSql,
  IBoundInfo,
  IDatabaseBaseInfo,
  IManageResultData,
  IExecuteSqlParams,
} from '@/typings';
import { Spin } from 'antd';
import { useStyles } from './style';
import { useUpdateEffect } from 'ahooks';
import SplitPane from 'react-split-pane';
import useRefreshTree from '@/blocks/SearchResult/hooks/useRefreshTree';
import { getDatabaseSupport, processResultDataList } from '@/utils/database';
import { EditorType, SQLEditorWithOperation } from '@/components/SQLEditor';
import { ISQLEditorWithOperationRef } from '@/components/SQLEditor/editor/SQLEditorWithOperation';
import SplitPaneUnpack from '@/components/SplitPaneUnpack';
import useSqlExecutor from '@/hooks/useSqlExecutor';
import i18n from '@/i18n';
import { staticMessage } from '@chat2db/ui';
import {
  ClosedSqlExecutionResults,
  SqlExecutionEvent,
  SqlExecutionResultIdentity,
  appendCompletedQueryResult,
  attachExecutionIdentity,
  clearClosedSqlExecutionResults,
  isSqlExecutionResultClosed,
  markSqlExecutionResultsClosed,
  mergeRows,
  sortExecutionResults,
  upsertResultFinished,
  upsertResultStarted,
} from '@/service/sqlExecutionStream';
import {
  createSqlResultHistoryMode,
  getNextResultDisplayBatchSequence,
  getSqlResultPreview,
  reduceSqlResultHistoryMode,
  retainLatestResultBatches,
  shouldAcceptExecutionResult,
} from '@/service/sqlExecutionBatch';
import {
  planSqlExecutionRetention,
  type SqlExecutionRetentionPreferences,
} from '@/service/sqlExecutionRetention';
import {
  beginWebSqlExecution,
  clearSqlExecutionLog,
  completeWebSqlExecution,
  createSqlExecutionLogState,
  failWebSqlExecution,
  prepareDesktopSqlExecutionLogForRequest,
  prepareSqlExecutionLogForExecution,
  reduceDesktopSqlExecutionEventWithHistoryPreference,
  rethrowNonCancellationSqlExecutionError,
  SqlExecutionLogContext,
} from '@/service/sqlExecutionLog';
import { isDesktop } from '@/utils/env';
import { v4 as uuidv4 } from 'uuid';

const SplitPaneAny = SplitPane as any;
const HISTORY_BATCH_LIMIT = 30;
const KEEP_EXECUTION_LOG_HISTORY_STORAGE_KEY = createExecutionConsoleKeepHistoryStorageKey(
  'community',
  __RUNTIME_ENV__,
);
const KEEP_RESULT_HISTORY_STORAGE_KEY = createResultTabKeepHistoryStorageKey('community', __RUNTIME_ENV__);

interface IProps {
  boundInfo: IBoundInfo;
  initDDL: string;
  type: EditorType;
  // Load SQL asynchronously.
  loadSQL?: () => Promise<string>;
  workspaceTabsTitle?: string;
  isActive?: boolean;
  onExecuteSQLCallback?: (params: { databaseInfo: IDatabaseBaseInfo; data: any }) => void;
  isConsole?: boolean;
  sqlActionEnabled?: boolean;
}

interface DesktopExecutionCallbackState {
  databaseInfo: IDatabaseBaseInfo;
  data: IManageResultData[];
}

export interface SQLExecuteRef {
  executeSQL: any;
  getDatabaseInfo: () => IDatabaseBaseInfo;
}

function getResultDisplayName(params: {
  executionSequence: number;
  statementSequence: number;
  resultSequence: number;
  sql?: string;
}) {
  const { executionSequence, statementSequence, resultSequence, sql } = params;
  const preview = getSqlResultPreview(sql);
  const maxLength = 36;
  const shortPreview = preview.length > maxLength ? `${preview.slice(0, maxLength - 1)}...` : preview;
  const resultNamePrefix = `#${executionSequence}-${statementSequence}`;
  const resultSetSuffix = resultSequence > 1 ? ` ${i18n('common.text.executionResult', resultSequence)}` : '';
  const label = `${resultNamePrefix}${resultSetSuffix}`;
  return shortPreview ? `${label} ${shortPreview}` : label;
}

function getResultSequence(result: IManageResultData) {
  const streamResultId = result.extra?.streamResultId;
  if (typeof streamResultId === 'number') {
    return streamResultId;
  }
  return result.resultSetId || 1;
}

function buildResultKey(executionId: string, statementSequence?: number, streamResultId?: number) {
  return [executionId, statementSequence || 0, streamResultId || 0].join(':');
}

function getEventStatementSequence(event: SqlExecutionEvent, fallback?: number) {
  if (typeof event.statementSequence === 'number') {
    return event.statementSequence;
  }
  return fallback;
}

function getEventResultSequence(event: SqlExecutionEvent, result?: IManageResultData, fallback?: number) {
  if (typeof event.resultSequence === 'number') {
    return event.resultSequence;
  }
  if (typeof result?.extra?.streamResultId === 'number') {
    return result.extra.streamResultId;
  }
  if (typeof result?.resultSetId === 'number') {
    return result.resultSetId;
  }
  return fallback;
}

function getExecutionLogContext(boundInfo: IBoundInfo): SqlExecutionLogContext {
  return {
    dataSourceId: boundInfo.dataSourceId,
    dataSourceName: boundInfo.dataSourceName,
    databaseType: boundInfo.databaseType,
    databaseName: boundInfo.databaseName,
    schemaName: boundInfo.schemaName,
  };
}

const SQLExecute = forwardRef((props: IProps, ref: ForwardedRef<SQLExecuteRef>) => {
  const {
    boundInfo: _boundInfo,
    initDDL,
    type,
    loadSQL,
    workspaceTabsTitle,
    onExecuteSQLCallback,
    isConsole = true,
    sqlActionEnabled = true,
  } = props;
  const { styles, cx } = useStyles();
  const sqlEditorRef = useRef<ISQLEditorWithOperationRef>(null);
  const [boundInfo, setBoundInfo] = useState<IBoundInfo>(_boundInfo);
  const boundInfoRef = useRef<IBoundInfo>(_boundInfo);
  const editorId = boundInfo.workspaceTabId ?? boundInfo.consoleId;
  const [boxRightConsoleHeight, setBoxRightConsoleHeight] = useState<number | string>(0);
  const executionSequenceRef = useRef(0);
  const resultDisplayBatchSequenceRef = useRef(0);
  const resultDisplayBatchSequenceByExecutionRef = useRef<Record<number, number>>({});
  const latestResultReplacementExecutionSequenceRef = useRef(0);
  const pendingDesktopExecutionSequenceRef = useRef<number>();
  const executionSequenceByIdRef = useRef<Record<string, number>>({});
  const executionSequenceByRequestRef = useRef<Record<number, number>>({});
  const keepExistingOutputByExecutionSequenceRef = useRef<Record<number, boolean>>({});
  const desktopExecutionCallbackBySequenceRef = useRef<Record<number, DesktopExecutionCallbackState>>({});
  const currentStatementSequenceByExecutionIdRef = useRef<Record<string, number>>({});
  const [resultBatchKey, setResultBatchKey] = useState(0);
  const [forceOutputTab, setForceOutputTab] = useState(false);
  const requestGenerationRef = useRef(0);
  const { activeConsoleId, setEditorToList, deleteEditor, updateWorkspaceTabBoundInfo } = useWorkspaceStore(
    (state) => ({
      activeConsoleId: state.activeConsoleId,
      setEditorToList: state.setEditorToList,
      deleteEditor: state.deleteEditor,
      updateWorkspaceTabBoundInfo: state.updateWorkspaceTabBoundInfo,
    }),
  );
  const [resultDataList, setResultDataList] = useState<IManageResultData[]>([]);
  const closedSqlExecutionResultsRef = useRef<ClosedSqlExecutionResults>(new Map());
  const [sqlExecutionLogState, setSqlExecutionLogState] = useState(createSqlExecutionLogState);
  const [keepExecutionLogHistory, setKeepExecutionLogHistory] = useState(() =>
    readExecutionConsoleKeepHistory(
      getExecutionConsolePreferenceStorage(),
      KEEP_EXECUTION_LOG_HISTORY_STORAGE_KEY,
    ),
  );
  const [resultHistoryMode, dispatchResultHistoryMode] = useReducer(
    reduceSqlResultHistoryMode,
    undefined,
    () =>
      createSqlResultHistoryMode(
        readResultTabKeepHistory(
          getResultTabPreferenceStorage(),
          KEEP_RESULT_HISTORY_STORAGE_KEY,
        ),
      ),
  );
  const {
    keepHistory: keepResultHistory,
    showResultCoordinates,
    resetResultSessionOnNextExecution,
  } = resultHistoryMode;
  const handleClearExecutionLog = useCallback(() => {
    setSqlExecutionLogState(clearSqlExecutionLog);
  }, []);
  const handleKeepExecutionLogHistoryChange = useCallback((keepHistory: boolean) => {
    setKeepExecutionLogHistory(keepHistory);
    persistExecutionConsoleKeepHistory(
      getExecutionConsolePreferenceStorage(),
      KEEP_EXECUTION_LOG_HISTORY_STORAGE_KEY,
      keepHistory,
    );
  }, []);
  useEffect(
    () =>
      subscribeExecutionConsoleKeepHistory((storageKey, keepHistory) => {
        if (storageKey === KEEP_EXECUTION_LOG_HISTORY_STORAGE_KEY) {
          setKeepExecutionLogHistory(keepHistory);
        }
      }),
    [],
  );
  const handleKeepResultHistoryChange = useCallback((keepHistory: boolean) => {
    dispatchResultHistoryMode({ type: 'setPreference', keepHistory });
    persistResultTabKeepHistory(
      getResultTabPreferenceStorage(),
      KEEP_RESULT_HISTORY_STORAGE_KEY,
      keepHistory,
    );
  }, []);
  useEffect(
    () =>
      subscribeResultTabKeepHistory((storageKey, keepHistory) => {
        if (storageKey === KEEP_RESULT_HISTORY_STORAGE_KEY) {
          dispatchResultHistoryMode({ type: 'setPreference', keepHistory });
        }
      }),
    [],
  );
  const handleRefreshTreeByExecuteSQL = useRefreshTree({ setBoundInfo });
  const getExecutionSequence = useCallback((executionId: string, preferredSequence?: number) => {
    if (!executionSequenceByIdRef.current[executionId]) {
      const nextExecutionSequence = preferredSequence ?? executionSequenceRef.current + 1;
      executionSequenceRef.current = Math.max(executionSequenceRef.current, nextExecutionSequence);
      executionSequenceByIdRef.current[executionId] = nextExecutionSequence;
    }
    return executionSequenceByIdRef.current[executionId];
  }, []);
  const getDisplayBatchSequence = useCallback((executionSequence: number) => {
    const existingSequence = resultDisplayBatchSequenceByExecutionRef.current[executionSequence];
    if (existingSequence !== undefined) {
      return existingSequence;
    }
    const displayBatchSequence = getNextResultDisplayBatchSequence(
      resultDisplayBatchSequenceRef.current,
      false,
    );
    resultDisplayBatchSequenceRef.current = displayBatchSequence;
    resultDisplayBatchSequenceByExecutionRef.current[executionSequence] = displayBatchSequence;
    return displayBatchSequence;
  }, []);
  const beginExecutionBatch = useCallback(
    (retentionPreferences: SqlExecutionRetentionPreferences) => {
      const {
        keepResultHistory: keepResultHistoryForExecution,
        resetResultSession,
      } = retentionPreferences;
      const { keepExistingOutput, keepExistingResults } = planSqlExecutionRetention(retentionPreferences);
      const executionSequence = executionSequenceRef.current + 1;
      executionSequenceRef.current = executionSequence;
      const displayBatchSequence = getNextResultDisplayBatchSequence(
        resultDisplayBatchSequenceRef.current,
        resetResultSession,
      );
      resultDisplayBatchSequenceRef.current = displayBatchSequence;
      resultDisplayBatchSequenceByExecutionRef.current[executionSequence] = displayBatchSequence;
      keepExistingOutputByExecutionSequenceRef.current[executionSequence] = keepExistingOutput;
      dispatchResultHistoryMode({
        type: 'beginExecution',
        keepHistory: keepResultHistoryForExecution,
      });
      setForceOutputTab(false);
      if (!keepExistingResults) {
        latestResultReplacementExecutionSequenceRef.current = executionSequence;
        setResultDataList([]);
      }
      setResultBatchKey((value) => value + 1);
      return {
        executionSequence,
        displayBatchSequence,
        keepExistingOutput,
      };
    },
    [],
  );
  const cleanupDesktopExecutionRequest = useCallback((requestSequence: number, executionId?: string) => {
    const executionSequence = executionSequenceByRequestRef.current[requestSequence];
    if (executionId) {
      delete currentStatementSequenceByExecutionIdRef.current[executionId];
      delete executionSequenceByIdRef.current[executionId];
      clearClosedSqlExecutionResults(closedSqlExecutionResultsRef.current, executionId);
    }
    delete executionSequenceByRequestRef.current[requestSequence];
    if (executionSequence !== undefined) {
      delete keepExistingOutputByExecutionSequenceRef.current[executionSequence];
      delete resultDisplayBatchSequenceByExecutionRef.current[executionSequence];
      delete desktopExecutionCallbackBySequenceRef.current[executionSequence];
    }
  }, []);
  const handleSqlExecutionRequestStart = useCallback(
    (requestSequence: number) => {
      const executionSequence = pendingDesktopExecutionSequenceRef.current ?? executionSequenceRef.current;
      pendingDesktopExecutionSequenceRef.current = undefined;
      executionSequenceByRequestRef.current[requestSequence] = executionSequence;
      const keepExistingOutput =
        keepExistingOutputByExecutionSequenceRef.current[executionSequence] ?? keepExecutionLogHistory;
      setSqlExecutionLogState((state) =>
        prepareDesktopSqlExecutionLogForRequest(state, requestSequence, keepExistingOutput),
      );
    },
    [keepExecutionLogHistory],
  );
  const handleSqlExecutionRequestStartError = useCallback(
    (error: unknown, requestSequence: number, params: IExecuteSqlParams) => {
      const executionSequence = executionSequenceByRequestRef.current[requestSequence];
      if (executionSequence !== undefined) {
        setSqlExecutionLogState((state) =>
          failWebSqlExecution(state, {
            executionId: `desktop-start-${requestSequence}`,
            executionSequence,
            sql: params.sql,
            context: getExecutionLogContext(boundInfoRef.current),
            error,
          }),
        );
      }
      cleanupDesktopExecutionRequest(requestSequence);
    },
    [cleanupDesktopExecutionRequest],
  );
  const handleSqlExecutionEvent = useCallback(
    (event: SqlExecutionEvent, requestSequence: number) => {
      const requestExecutionSequence = executionSequenceByRequestRef.current[requestSequence];
      if (requestExecutionSequence === undefined) {
        return;
      }
      const cleanupTerminalExecution = () => {
        if (event.eventType !== 'finished' && event.eventType !== 'failed' && event.eventType !== 'cancelled') {
          return;
        }
        cleanupDesktopExecutionRequest(requestSequence, event.executionId);
      };
      const executionSequence = getExecutionSequence(event.executionId, requestExecutionSequence);
      const keepExistingOutput =
        keepExistingOutputByExecutionSequenceRef.current[executionSequence] ?? keepExecutionLogHistory;
      setSqlExecutionLogState((state) =>
        reduceDesktopSqlExecutionEventWithHistoryPreference(
          state,
          event,
          getExecutionLogContext(boundInfoRef.current),
          keepExistingOutput,
          requestSequence,
          executionSequence,
        ),
      );
      if (
        !shouldAcceptExecutionResult(
          executionSequence,
          latestResultReplacementExecutionSequenceRef.current,
        )
      ) {
        cleanupTerminalExecution();
        return;
      }
      const displayBatchSequence = getDisplayBatchSequence(executionSequence);
      if (event.eventType === 'started') {
        return;
      }
      if (event.eventType === 'statementStarted') {
        const statementSequence =
          event.statementSequence ?? (currentStatementSequenceByExecutionIdRef.current[event.executionId] || 0) + 1;
        currentStatementSequenceByExecutionIdRef.current[event.executionId] = statementSequence;
        return;
      }
      if (event.eventType === 'resultStarted') {
        const statementSequence =
          getEventStatementSequence(event, currentStatementSequenceByExecutionIdRef.current[event.executionId]) || 1;
        const result = processResultDataList([event.message], {
          databaseType: boundInfoRef.current.databaseType,
          dataSourceId: boundInfoRef.current.dataSourceId,
          databaseName: boundInfoRef.current.databaseName,
          schemaName: boundInfoRef.current.schemaName,
          sql: event.message?.originalSql,
        })[0];
        const resultWithIdentity = attachExecutionIdentity(result, event.executionId, statementSequence);
        const resultSequence =
          getEventResultSequence(event, resultWithIdentity, getResultSequence(resultWithIdentity)) || 1;
        const resultKey = event.resultKey || buildResultKey(event.executionId, statementSequence, resultSequence);
        if (isSqlExecutionResultClosed(closedSqlExecutionResultsRef.current, event.executionId, resultKey)) {
          return;
        }
        setResultDataList((prev) => {
          const nextResult = {
            ...resultWithIdentity,
            extra: {
              ...(resultWithIdentity.extra || {}),
              executionSequence,
              resultKey,
              resultSequence,
            },
            displayName: getResultDisplayName({
              executionSequence: displayBatchSequence,
              statementSequence,
              resultSequence: resultWithIdentity.resultSetId || resultSequence,
              sql: resultWithIdentity.originalSql,
            }),
          };
          const nextResultDataList = upsertResultStarted(prev, nextResult);
          const sortedResultDataList = retainLatestResultBatches(
            sortExecutionResults(nextResultDataList),
            HISTORY_BATCH_LIMIT,
          );
          return sortedResultDataList;
        });
        return;
      }
      if (event.eventType === 'rows') {
        const statementSequence =
          getEventStatementSequence(event, currentStatementSequenceByExecutionIdRef.current[event.executionId]) || 1;
        const chunk = processResultDataList([event.message], {
          databaseType: boundInfoRef.current.databaseType,
          dataSourceId: boundInfoRef.current.dataSourceId,
          databaseName: boundInfoRef.current.databaseName,
          schemaName: boundInfoRef.current.schemaName,
          sql: event.message?.originalSql,
        })[0];
        const chunkWithIdentity = attachExecutionIdentity(chunk, event.executionId, statementSequence);
        const resultSequence =
          getEventResultSequence(event, chunkWithIdentity, getResultSequence(chunkWithIdentity)) || 1;
        const resultKey = event.resultKey || buildResultKey(event.executionId, statementSequence, resultSequence);
        if (isSqlExecutionResultClosed(closedSqlExecutionResultsRef.current, event.executionId, resultKey)) {
          return;
        }
        setResultDataList((prev) => {
          const nextResultDataList = mergeRows(prev, {
            ...chunkWithIdentity,
            displayName: getResultDisplayName({
              executionSequence: displayBatchSequence,
              statementSequence,
              resultSequence: chunkWithIdentity.resultSetId || resultSequence,
              sql: chunkWithIdentity.originalSql,
            }),
            extra: {
              ...(chunkWithIdentity.extra || {}),
              executionSequence,
              resultKey,
              resultSequence,
            },
          });
          const retainedResultDataList = retainLatestResultBatches(nextResultDataList, HISTORY_BATCH_LIMIT);
          return retainedResultDataList;
        });
        return;
      }
      if (event.eventType === 'updateCount' || event.eventType === 'resultFinished') {
        if (event.eventType === 'resultFinished') {
          const callbackState = desktopExecutionCallbackBySequenceRef.current[executionSequence];
          if (callbackState) {
            callbackState.data = appendCompletedQueryResult(callbackState.data, event);
          }
        }
        const statementSequence =
          getEventStatementSequence(event, currentStatementSequenceByExecutionIdRef.current[event.executionId]) || 1;
        const result = processResultDataList([event.message], {
          databaseType: boundInfoRef.current.databaseType,
          dataSourceId: boundInfoRef.current.dataSourceId,
          databaseName: boundInfoRef.current.databaseName,
          schemaName: boundInfoRef.current.schemaName,
          sql: event.message?.originalSql,
        })[0];
        const resultWithIdentity = attachExecutionIdentity(result, event.executionId, statementSequence);
        const resultSequence =
          getEventResultSequence(event, resultWithIdentity, getResultSequence(resultWithIdentity)) || 1;
        const resultKey = event.resultKey || buildResultKey(event.executionId, statementSequence, resultSequence);
        if (!isSqlExecutionResultClosed(closedSqlExecutionResultsRef.current, event.executionId, resultKey)) {
          setResultDataList((prev) => {
            const nextResult = {
              ...resultWithIdentity,
              displayName: getResultDisplayName({
                executionSequence: displayBatchSequence,
                statementSequence,
                resultSequence: resultWithIdentity.resultSetId || resultSequence,
                sql: resultWithIdentity.originalSql,
              }),
              extra: {
                ...(resultWithIdentity.extra || {}),
                executionSequence,
                resultKey,
                resultSequence,
              },
            };
            const nextResultDataList = upsertResultFinished(prev, nextResult);
            const sortedResultDataList = retainLatestResultBatches(
              sortExecutionResults(nextResultDataList),
              HISTORY_BATCH_LIMIT,
            );
            return sortedResultDataList;
          });
        }
        if (event.eventType === 'resultFinished' && boundInfoRef.current.databaseType) {
          handleRefreshTreeByExecuteSQL([result], boundInfoRef.current.databaseType);
        }
        return;
      }
      if (event.eventType === 'message') {
        return;
      }
      if (event.eventType === 'finished' || event.eventType === 'failed' || event.eventType === 'cancelled') {
        try {
          const callbackState = desktopExecutionCallbackBySequenceRef.current[executionSequence];
          if (event.eventType === 'finished' && callbackState?.data.length) {
            onExecuteSQLCallback?.(callbackState);
          }
        } finally {
          cleanupTerminalExecution();
        }
      }
    },
    [
      cleanupDesktopExecutionRequest,
      getDisplayBatchSequence,
      getExecutionSequence,
      handleRefreshTreeByExecuteSQL,
      keepExecutionLogHistory,
      onExecuteSQLCallback,
    ],
  );
  const { executing, canExecuteSQL, executeSQL, stopExecuteSQL } = useSqlExecutor({
    onExecutionRequestStart: handleSqlExecutionRequestStart,
    onExecutionRequestStartError: handleSqlExecutionRequestStartError,
    onExecutionEvent: handleSqlExecutionEvent,
    onExecutionCancellationError: () => {
      staticMessage.error(i18n('common.text.cancelRequestFailed'));
    },
  });

  // Whether to show the split panel.
  const isSplitPane = useMemo(() => {
    const _isSplitPane =
      resultDataList.length > 0 ||
      sqlExecutionLogState.records.length > 0 ||
      executing === true;
    if (!_isSplitPane) {
      setBoxRightConsoleHeight(0);
    }
    return _isSplitPane;
  }, [resultDataList, sqlExecutionLogState.records.length, executing]);

  const isActive = useMemo(() => {
    return activeConsoleId === editorId || !!props.isActive;
  }, [activeConsoleId, editorId, props.isActive]);

  useEffect(() => {
    if (editorId) {
      setEditorToList(editorId, sqlEditorRef.current);
    }
    return () => {
      if (editorId) {
        deleteEditor(editorId);
      }
    };
  }, [editorId]);

  useUpdateEffect(() => {
    boundInfoRef.current = boundInfo;
    updateWorkspaceTabBoundInfo(boundInfo);
  }, [boundInfo]);

  useEffect(() => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    if (loadSQL) {
      loadSQL().then((sql) => {
        if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
        sqlEditorRef.current?.setValue(sql, 'reset');
        updateWorkspaceTabBoundInfo({
          ...boundInfoRef.current,
          ddl: sql,
        });
      });
    }
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  const handleUnfold = () => {
    setBoxRightConsoleHeight('50%');
  };

  const handlePackUp = () => {
    setBoxRightConsoleHeight(0);
  };

  const handleResultDataListChange = useCallback(
    (params: {
      resultDataList: IManageResultData[];
      historyResultDataList: IManageResultData[];
      closedResultIdentities: SqlExecutionResultIdentity[];
    }) => {
      markSqlExecutionResultsClosed(
        closedSqlExecutionResultsRef.current,
        params.closedResultIdentities.filter(
          ({ executionId }) => executionSequenceByIdRef.current[executionId] !== undefined,
        ),
      );
      const nextResultDataList = sortExecutionResults([
        ...params.resultDataList,
        ...params.historyResultDataList,
      ]);
      setResultDataList(nextResultDataList);
    },
    [],
  );

  const handleChangeDBInfo = (newBoundInfo: IBoundInfo) => {
    const { databaseType } = newBoundInfo;
    setBoundInfo({
      ...boundInfo,
      ...newBoundInfo,
      ...getDatabaseSupport(databaseType),
    });
  };

  const handleExecuteSQL = (params: IConsoleReturnExecuteSql): Promise<any> => {
    // Do not execute without a selected dataSourceId.
    if (!boundInfo.dataSourceId) {
      staticMessage.warning(i18n('workspace.text.pleaseSelectDataSource'));
      return Promise.resolve();
    }
    if (!canExecuteSQL()) {
      staticMessage.warning(i18n('common.text.currentExecution'));
      return Promise.resolve();
    }

    if (!boxRightConsoleHeight) {
      setBoxRightConsoleHeight('50%');
    }
    const { executionSequence, displayBatchSequence, keepExistingOutput } = beginExecutionBatch({
      keepOutputHistory: keepExecutionLogHistory,
      keepResultHistory,
      resetResultSession: resetResultSessionOnNextExecution,
    });
    if (isDesktop) {
      pendingDesktopExecutionSequenceRef.current = executionSequence;
    }

    const executeSqlParams = {
      ...params,
      databaseType: boundInfo.databaseType,
      dataSourceId: boundInfo.dataSourceId,
      dataSourceName: boundInfo.dataSourceName,
      databaseName: boundInfo.databaseName,
      schemaName: boundInfo.schemaName,
    };

    const webExecutionId = isDesktop ? undefined : uuidv4();
    const executionLogContext = getExecutionLogContext(boundInfo);
    if (isDesktop && onExecuteSQLCallback) {
      desktopExecutionCallbackBySequenceRef.current[executionSequence] = {
        databaseInfo: {
          ...boundInfo,
          ...params,
        },
        data: [],
      };
    }
    if (webExecutionId) {
      executionSequenceByIdRef.current[webExecutionId] = executionSequence;
      setSqlExecutionLogState((state) =>
        beginWebSqlExecution(
          prepareSqlExecutionLogForExecution(state, webExecutionId, keepExistingOutput),
          {
            executionId: webExecutionId,
            executionSequence,
            sql: params.sql,
            context: executionLogContext,
          },
        ),
      );
    }

    return executeSQL(executeSqlParams).then((res) => {
      if (!res?.length) {
        if (webExecutionId) {
          setSqlExecutionLogState((state) =>
            completeWebSqlExecution(state, {
              executionId: webExecutionId,
              executionSequence,
              sql: params.sql,
              context: executionLogContext,
              results: [],
            }),
          );
        }
        return;
      }
      const _resultDataList = processResultDataList(res, executeSqlParams).map((item, index) => {
        const sql = item.originalSql || params.sql;
        const statementSequence = item.statementSequence ?? (Number(item.extra?.statementSequence) || index + 1);
        const resultSequence = Number(item.extra?.streamResultId) || index + 1;
        const executionId = webExecutionId || `legacy-${executionSequence}`;
        const itemWithIdentity = attachExecutionIdentity(item, executionId, statementSequence);
        return {
          ...itemWithIdentity,
          extra: {
            ...(itemWithIdentity.extra || {}),
            executionSequence,
            statementSequence,
            resultKey: buildResultKey(executionId, statementSequence, resultSequence),
            resultSequence,
          },
          displayName: getResultDisplayName({
            executionSequence: displayBatchSequence,
            statementSequence,
            resultSequence: item.resultSetId || resultSequence,
            sql,
          }),
        };
      });

      if (boundInfo.databaseType) {
        // Refresh the tree; only relational databases are supported.
        handleRefreshTreeByExecuteSQL(_resultDataList, boundInfo.databaseType);
      }

      if (
        shouldAcceptExecutionResult(
          executionSequence,
          latestResultReplacementExecutionSequenceRef.current,
        )
      ) {
        setResultDataList((prev) => {
          const nextResultDataList = _resultDataList.reduce((currentResultDataList, item) => {
            return upsertResultFinished(currentResultDataList, item);
          }, prev);
          const sortedResultDataList = retainLatestResultBatches(
            sortExecutionResults(nextResultDataList),
            HISTORY_BATCH_LIMIT,
          );
          return sortedResultDataList;
        });
      }

      if (webExecutionId) {
        setSqlExecutionLogState((state) =>
          completeWebSqlExecution(state, {
            executionId: webExecutionId,
            executionSequence,
            sql: params.sql,
            context: executionLogContext,
            results: _resultDataList,
          }),
        );
      }

      const data = res.filter((item) => item.dataList !== null);

      onExecuteSQLCallback?.({
        databaseInfo: {
          ...boundInfo,
          ...params,
        },
        data,
      });
    })
      .catch((error) => {
        if (executionSequence === executionSequenceRef.current) {
          setForceOutputTab(true);
        }
        if (webExecutionId) {
          setSqlExecutionLogState((state) =>
            failWebSqlExecution(state, {
              executionId: webExecutionId,
              executionSequence,
              sql: params.sql,
              context: executionLogContext,
              error,
            }),
          );
        }
        rethrowNonCancellationSqlExecutionError(error);
      })
      .finally(() => {
        if (webExecutionId) {
          delete executionSequenceByIdRef.current[webExecutionId];
        }
        delete keepExistingOutputByExecutionSequenceRef.current[executionSequence];
        delete resultDisplayBatchSequenceByExecutionRef.current[executionSequence];
        delete desktopExecutionCallbackBySequenceRef.current[executionSequence];
      });
  };

  const stopExecuteSql = () => {
    stopExecuteSQL();
  };

  useImperativeHandle(ref, () => ({
    executeSQL: handleExecuteSQL,
    getDatabaseInfo: () => {
      return { ...boundInfo, sql: sqlEditorRef.current?.getValue() };
    },
  }));

  return (
    <SplitPaneAny
      className={cx(
        { ResizerSizeIsZeroTop: boxRightConsoleHeight === 0 },
        { ResizerHidden: !isSplitPane },
        styles.boxRightCenter,
      )}
      pane1Style={{ height: 0 }}
      pane2Style={{ display: 'block' }}
      size={boxRightConsoleHeight}
      split="horizontal"
      primary="second"
      minSize={0}
      onChange={(_size) => {
        if (_size < 50) {
          setBoxRightConsoleHeight(0);
          return;
        }
        setBoxRightConsoleHeight(_size);
      }}
    >
      <div className={styles.boxRightConsole}>
        <SQLEditorWithOperation
          type={type}
          id={editorId?.toString() || ''}
          ref={sqlEditorRef}
          defaultSQL={initDDL}
          workspaceTabsTitle={workspaceTabsTitle}
          dbInfo={boundInfo}
          setDBInfo={handleChangeDBInfo}
          active={isActive}
          onExecuteSQL={handleExecuteSQL}
          reloadSQL={loadSQL}
          isConsole={isConsole}
          sqlActionEnabled={sqlActionEnabled}
        />
      </div>
      <SplitPaneUnpack onUnfold={handleUnfold} onPackUp={handlePackUp} className={styles.boxRightResult}>
        {isSplitPane && (
          <>
            {!!(resultDataList.length || sqlExecutionLogState.records.length) && (
              <SearchResult
                resultDataList={resultDataList}
                executionLogRecords={sqlExecutionLogState.records}
                keepExecutionLogHistory={keepExecutionLogHistory}
                keepResultHistory={keepResultHistory}
                showExecutionResultCoordinates={showResultCoordinates}
                closeActiveResultShortcutEnabled={isActive}
                resultBatchKey={resultBatchKey}
                forceOutputTab={forceOutputTab}
                onClearExecutionLog={handleClearExecutionLog}
                onKeepExecutionLogHistoryChange={handleKeepExecutionLogHistoryChange}
                onKeepResultHistoryChange={handleKeepResultHistoryChange}
                onResultDataListChange={handleResultDataListChange}
              />
            )}
            {executing && (
              <div
                className={
                  resultDataList.length || sqlExecutionLogState.records.length
                    ? styles.executingBar
                    : styles.tableLoading
                }
              >
                <Spin size={resultDataList.length || sqlExecutionLogState.records.length ? 'small' : 'default'} />
                <div className={styles.executingText}>{i18n('common.text.currentExecution')}</div>
                <div className={styles.stopExecuteSql} onClick={stopExecuteSql}>
                  {i18n('common.button.cancelRequest')}
                </div>
              </div>
            )}
          </>
        )}
      </SplitPaneUnpack>
    </SplitPaneAny>
  );
});

export default memo(SQLExecute);
