import {
  memo,
  useEffect,
  useMemo,
  useRef,
  useState,
  useCallback,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
} from 'react';
import SearchResult from '@/blocks/SearchResult';
import { useWorkspaceStore } from '@/store/workspace';
import { IConsoleReturnExecuteSql, IBoundInfo, IDatabaseBaseInfo, IManageResultData } from '@/typings';
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
  SqlExecutionEvent,
  SqlExecutionStatement,
  appendResultStarted,
  appendResultFinished,
  attachExecutionIdentity,
  mergeRows,
  sortExecutionResults,
  upsertResultFinished,
  upsertResultStarted,
} from '@/service/sqlExecutionStream';
import {
  beginWebSqlExecution,
  clearSqlExecutionLog,
  completeWebSqlExecution,
  createSqlExecutionLogState,
  failWebSqlExecution,
  reduceDesktopSqlExecutionEvent,
  rethrowNonCancellationSqlExecutionError,
  SqlExecutionLogContext,
} from '@/service/sqlExecutionLog';
import { isDesktop } from '@/utils/env';
import { v4 as uuidv4 } from 'uuid';

const SplitPaneAny = SplitPane as any;
const HISTORY_RESULT_LIMIT = 30;

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

export interface SQLExecuteRef {
  executeSQL: any;
  getDatabaseInfo: () => IDatabaseBaseInfo;
}

function getSqlPreview(sql?: string) {
  const lines = (sql || '')
    .replace(/\r\n/g, '\n')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  const targetLine = lines.find((line) => !line.startsWith('--')) || lines[0] || '';
  return targetLine.replace(/\s+/g, ' ');
}

function getSqlIdentity(sql?: string) {
  return (sql || '')
    .replace(/\r\n/g, '\n')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('--'))
    .join(' ')
    .replace(/\s+/g, ' ')
    .replace(/;+\s*$/, '')
    .trim();
}

function getHistorySequenceKey(sql?: string) {
  return getSqlIdentity(sql).toLowerCase();
}

function getResultDisplayName(params: {
  executionSequence: number;
  resultSequence: number;
  sql?: string;
  showPrefix: boolean;
}) {
  const { executionSequence, resultSequence, sql, showPrefix } = params;
  const preview = getSqlPreview(sql);
  const maxLength = 36;
  const shortPreview = preview.length > maxLength ? `${preview.slice(0, maxLength - 1)}...` : preview;
  if (!showPrefix) {
    return shortPreview;
  }
  const resultNamePrefix = `#${executionSequence}-${resultSequence}`;
  return shortPreview ? `${resultNamePrefix} ${shortPreview}` : resultNamePrefix;
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

function buildHistoryKey(boundInfo: IBoundInfo, sql?: string, historySequence?: number, resultSequence?: number) {
  return [
    boundInfo.dataSourceId || '',
    boundInfo.databaseName || '',
    boundInfo.schemaName || '',
    getSqlIdentity(sql),
    historySequence || 1,
    resultSequence || '',
  ].join('|');
}

function archiveResultDataList(historyResultDataList: IManageResultData[], currentResultDataList: IManageResultData[]) {
  if (!currentResultDataList.length) {
    return historyResultDataList;
  }
  return [...currentResultDataList, ...historyResultDataList].slice(0, HISTORY_RESULT_LIMIT);
}

function getEventStatementSequence(event: SqlExecutionEvent, fallback?: SqlExecutionStatement) {
  if (typeof event.statementSequence === 'number') {
    return event.statementSequence;
  }
  return fallback?.sequence;
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
  const executionSequenceByIdRef = useRef<Record<string, number>>({});
  const statementSequenceRef = useRef(0);
  const statementSqlCountsRef = useRef<Record<string, number>>({});
  const statementBySequenceRef = useRef<Record<number, SqlExecutionStatement>>({});
  const currentStatementRef = useRef<SqlExecutionStatement>();
  const [resultBatchKey, setResultBatchKey] = useState(0);
  const [forceOutputTab, setForceOutputTab] = useState(false);
  const { activeConsoleId, setEditorToList, deleteEditor, updateWorkspaceTabBoundInfo } = useWorkspaceStore(
    (state) => ({
      activeConsoleId: state.activeConsoleId,
      setEditorToList: state.setEditorToList,
      deleteEditor: state.deleteEditor,
      updateWorkspaceTabBoundInfo: state.updateWorkspaceTabBoundInfo,
    }),
  );
  const [resultDataList, setResultDataList] = useState<IManageResultData[]>([]);
  const resultDataListRef = useRef<IManageResultData[]>([]);
  const [historyResultDataList, setHistoryResultDataList] = useState<IManageResultData[]>([]);
  const historyResultDataListRef = useRef<IManageResultData[]>([]);
  const [sqlExecutionLogState, setSqlExecutionLogState] = useState(createSqlExecutionLogState);
  const handleClearExecutionLog = useCallback(() => setSqlExecutionLogState(clearSqlExecutionLog), []);
  const archivedForExecutionRef = useRef(false);
  const executionSnapshotRef = useRef<{
    resultDataList: IManageResultData[];
    historyResultDataList: IManageResultData[];
  } | null>(null);
  const handleRefreshTreeByExecuteSQL = useRefreshTree({ setBoundInfo });
  const archiveCurrentResultDataList = useCallback(() => {
    if (archivedForExecutionRef.current) {
      return;
    }
    archivedForExecutionRef.current = true;
    const currentResultDataList = resultDataListRef.current;
    if (currentResultDataList.length) {
      const nextHistoryResultDataList = archiveResultDataList(historyResultDataListRef.current, currentResultDataList);
      historyResultDataListRef.current = nextHistoryResultDataList;
      resultDataListRef.current = [];
      setHistoryResultDataList(nextHistoryResultDataList);
      setResultDataList([]);
    }
    setResultBatchKey((value) => value + 1);
  }, []);
  const restoreExecutionSnapshotIfEmpty = useCallback(() => {
    const executionSnapshot = executionSnapshotRef.current;
    if (!executionSnapshot || resultDataListRef.current.length) {
      return;
    }
    resultDataListRef.current = executionSnapshot.resultDataList;
    historyResultDataListRef.current = executionSnapshot.historyResultDataList;
    setResultDataList(executionSnapshot.resultDataList);
    setHistoryResultDataList(executionSnapshot.historyResultDataList);
    setResultBatchKey((value) => value + 1);
  }, []);
  const getExecutionSequence = useCallback((executionId: string) => {
    if (!executionSequenceByIdRef.current[executionId]) {
      const nextExecutionSequence = executionSequenceRef.current + 1;
      executionSequenceRef.current = nextExecutionSequence;
      executionSequenceByIdRef.current[executionId] = nextExecutionSequence;
    }
    return executionSequenceByIdRef.current[executionId];
  }, []);
  const resetCurrentExecutionState = useCallback((resetExecutionSequence = false) => {
    if (resetExecutionSequence) {
      executionSequenceRef.current = 0;
      executionSequenceByIdRef.current = {};
    }
    statementSequenceRef.current = 0;
    statementSqlCountsRef.current = {};
    statementBySequenceRef.current = {};
    currentStatementRef.current = undefined;
  }, []);
  const beginExecutionResultBatch = useCallback(() => {
    setForceOutputTab(false);
    executionSnapshotRef.current = {
      resultDataList: resultDataListRef.current,
      historyResultDataList: historyResultDataListRef.current,
    };
    archivedForExecutionRef.current = false;
    archiveCurrentResultDataList();
    resetCurrentExecutionState(false);
  }, [archiveCurrentResultDataList, resetCurrentExecutionState]);
  const handleSqlExecutionEvent = useCallback(
    (event: SqlExecutionEvent) => {
      setSqlExecutionLogState((state) =>
        reduceDesktopSqlExecutionEvent(state, event, getExecutionLogContext(boundInfoRef.current)),
      );
      // Execution always replaces the previous result; use View History to inspect older results.
      const shouldOverwriteResultTabs = true;
      if (event.eventType === 'started') {
        archiveCurrentResultDataList();
        resetCurrentExecutionState(false);
        return;
      }
      if (event.eventType === 'statementStarted') {
        const statementSequence = event.statementSequence ?? statementSequenceRef.current + 1;
        statementSequenceRef.current = Math.max(statementSequenceRef.current, statementSequence);
        const statementSqlIdentity = getHistorySequenceKey(event.message?.originalSql || event.message?.sql);
        const statementSqlCount = (statementSqlCountsRef.current[statementSqlIdentity] || 0) + 1;
        statementSqlCountsRef.current[statementSqlIdentity] = statementSqlCount;
        const nextStatement = {
          ...(event.message || {}),
          sequence: statementSequence,
          historySequence: statementSqlCount,
        };
        currentStatementRef.current = nextStatement;
        statementBySequenceRef.current[statementSequence] = nextStatement;
        return;
      }
      if (event.eventType === 'resultStarted') {
        const statementSequence = getEventStatementSequence(event, currentStatementRef.current);
        const statementSnapshot =
          (statementSequence && statementBySequenceRef.current[statementSequence]) || currentStatementRef.current;
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
        const historyKey = buildHistoryKey(
          boundInfoRef.current,
          resultWithIdentity.originalSql,
          statementSnapshot?.historySequence,
          resultSequence,
        );
        setResultDataList((prev) => {
          const executionSequence = getExecutionSequence(event.executionId);
          const nextResult = {
            ...resultWithIdentity,
            extra: {
              ...(resultWithIdentity.extra || {}),
              executionSequence,
              resultKey,
              resultSequence,
              historyKey,
            },
            displayName: getResultDisplayName({
              executionSequence,
              resultSequence,
              sql: resultWithIdentity.originalSql,
              showPrefix: !shouldOverwriteResultTabs,
            }),
          };
          const nextResultDataList = shouldOverwriteResultTabs
            ? appendResultStarted(prev, nextResult)
            : upsertResultStarted(prev, nextResult);
          const sortedResultDataList = sortExecutionResults(nextResultDataList);
          resultDataListRef.current = sortedResultDataList;
          return sortedResultDataList;
        });
        return;
      }
      if (event.eventType === 'rows') {
        const statementSequence = getEventStatementSequence(event, currentStatementRef.current);
        const resultSequence = getEventResultSequence(event, event.message);
        const chunkWithIdentity = attachExecutionIdentity(
          event.message,
          event.executionId,
          statementSequence,
        );
        setResultDataList((prev) => {
          const nextResultDataList = mergeRows(prev, {
            ...chunkWithIdentity,
            extra: {
              ...(chunkWithIdentity.extra || {}),
              resultKey: event.resultKey || buildResultKey(event.executionId, statementSequence, resultSequence),
              resultSequence,
            },
          });
          resultDataListRef.current = nextResultDataList;
          return nextResultDataList;
        });
        return;
      }
      if (event.eventType === 'updateCount' || event.eventType === 'resultFinished') {
        const statementSequence = getEventStatementSequence(event, currentStatementRef.current);
        const statementSnapshot =
          (statementSequence && statementBySequenceRef.current[statementSequence]) || currentStatementRef.current;
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
        const historyKey = buildHistoryKey(
          boundInfoRef.current,
          resultWithIdentity.originalSql,
          statementSnapshot?.historySequence,
          resultSequence,
        );
        setResultDataList((prev) => {
          const executionSequence = getExecutionSequence(event.executionId);
          const nextResult = {
            ...resultWithIdentity,
            displayName: getResultDisplayName({
              executionSequence,
              resultSequence,
              sql: resultWithIdentity.originalSql,
              showPrefix: !shouldOverwriteResultTabs,
            }),
            extra: {
              ...(resultWithIdentity.extra || {}),
              executionSequence,
              resultKey,
              resultSequence,
              historyKey,
            },
          };
          const nextResultDataList = shouldOverwriteResultTabs
            ? appendResultFinished(prev, nextResult)
            : upsertResultFinished(prev, nextResult);
          const sortedResultDataList = sortExecutionResults(nextResultDataList);
          resultDataListRef.current = sortedResultDataList;
          return sortedResultDataList;
        });
        if (event.eventType === 'resultFinished' && boundInfoRef.current.databaseType) {
          handleRefreshTreeByExecuteSQL([result], boundInfoRef.current.databaseType);
        }
        return;
      }
      if (event.eventType === 'message') {
        return;
      }
    },
    [archiveCurrentResultDataList, getExecutionSequence, handleRefreshTreeByExecuteSQL, resetCurrentExecutionState],
  );
  const { executing, executeSQL, stopExecuteSQL } = useSqlExecutor({ onExecutionEvent: handleSqlExecutionEvent });

  // Whether to show the split panel.
  const isSplitPane = useMemo(() => {
    const _isSplitPane =
      resultDataList.length > 0 ||
      historyResultDataList.length > 0 ||
      sqlExecutionLogState.records.length > 0 ||
      executing === true;
    if (!_isSplitPane) {
      setBoxRightConsoleHeight(0);
    }
    return _isSplitPane;
  }, [resultDataList, historyResultDataList, sqlExecutionLogState.records.length, executing]);

  const isActive = useMemo(() => {
    return activeConsoleId === editorId || !!props.isActive;
  }, [activeConsoleId, editorId]);

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
    if (loadSQL) {
      loadSQL().then((sql) => {
        sqlEditorRef.current?.setValue(sql, 'reset');
        updateWorkspaceTabBoundInfo({
          ...boundInfoRef.current,
          ddl: sql,
        });
      });
    }
  }, []);

  const handleUnfold = () => {
    setBoxRightConsoleHeight('50%');
  };

  const handlePackUp = () => {
    setBoxRightConsoleHeight(0);
  };

  const handleResultDataListChange = useCallback(
    (params: { resultDataList: IManageResultData[]; historyResultDataList: IManageResultData[] }) => {
      if (!params.resultDataList.length && !params.historyResultDataList.length) {
        resetCurrentExecutionState(true);
      }
      resultDataListRef.current = params.resultDataList;
      historyResultDataListRef.current = params.historyResultDataList;
      setResultDataList(params.resultDataList);
      setHistoryResultDataList(params.historyResultDataList);
    },
    [resetCurrentExecutionState],
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

    if (!boxRightConsoleHeight) {
      setBoxRightConsoleHeight('50%');
    }
    beginExecutionResultBatch();

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
    if (webExecutionId) {
      setSqlExecutionLogState((state) =>
        beginWebSqlExecution(state, {
          executionId: webExecutionId,
          sql: params.sql,
          context: executionLogContext,
        }),
      );
    }

    return executeSQL(executeSqlParams).then((res) => {
      if (!res?.length) {
        if (webExecutionId) {
          setSqlExecutionLogState((state) =>
            completeWebSqlExecution(state, {
              executionId: webExecutionId,
              sql: params.sql,
              context: executionLogContext,
              results: [],
            }),
          );
        }
        executionSnapshotRef.current = null;
        return;
      }
      const executionSequence = executionSequenceRef.current + 1;
      executionSequenceRef.current = executionSequence;
      // Execution always replaces the previous result; use View History to inspect older results.
      const shouldOverwriteResultTabs = true;
      const legacySqlCounts: Record<string, number> = {};
      const _resultDataList = processResultDataList(res, executeSqlParams).map((item, index) => {
        const sql = item.originalSql || params.sql;
        const sqlIdentity = getHistorySequenceKey(sql);
        const historySequence = (legacySqlCounts[sqlIdentity] || 0) + 1;
        legacySqlCounts[sqlIdentity] = historySequence;
        const resultSequence = item.resultSetId || index + 1;
        return {
          ...item,
          extra: {
            ...(item.extra || {}),
            executionSequence,
            resultKey: buildResultKey(webExecutionId || `legacy-${executionSequence}`, index + 1, resultSequence),
            resultSequence,
            historyKey: buildHistoryKey(boundInfo, sql, historySequence, resultSequence),
          },
          displayName: getResultDisplayName({
            executionSequence,
            resultSequence: index + 1,
            sql,
            showPrefix: !shouldOverwriteResultTabs,
          }),
        };
      });

      if (boundInfo.databaseType) {
        // Refresh the tree; only relational databases are supported.
        handleRefreshTreeByExecuteSQL(_resultDataList, boundInfo.databaseType);
      }

      setResultDataList((prev) => {
        const nextResultDataList = shouldOverwriteResultTabs
          ? _resultDataList
          : _resultDataList.reduce((currentResultDataList, item) => {
              return upsertResultFinished(currentResultDataList, item);
            }, prev);
        const sortedResultDataList = sortExecutionResults(nextResultDataList);
        resultDataListRef.current = sortedResultDataList;
        return sortedResultDataList;
      });

      if (webExecutionId) {
        setSqlExecutionLogState((state) =>
          completeWebSqlExecution(state, {
            executionId: webExecutionId,
            sql: params.sql,
            context: executionLogContext,
            results: _resultDataList,
          }),
        );
      }

      executionSnapshotRef.current = null;

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
        setForceOutputTab(true);
        if (webExecutionId) {
          setSqlExecutionLogState((state) =>
            failWebSqlExecution(state, {
              executionId: webExecutionId,
              sql: params.sql,
              context: executionLogContext,
              error,
            }),
          );
        }
        restoreExecutionSnapshotIfEmpty();
        rethrowNonCancellationSqlExecutionError(error);
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
          useAI={isConsole}
          sqlActionEnabled={sqlActionEnabled}
        />
      </div>
      <SplitPaneUnpack onUnfold={handleUnfold} onPackUp={handlePackUp} className={styles.boxRightResult}>
        {isSplitPane && (
          <>
            {!!(resultDataList.length || historyResultDataList.length || sqlExecutionLogState.records.length) && (
              <SearchResult
                resultDataList={resultDataList}
                historyResultDataList={historyResultDataList}
                executionLogRecords={sqlExecutionLogState.records}
                resultBatchKey={resultBatchKey}
                forceOutputTab={forceOutputTab}
                onClearExecutionLog={handleClearExecutionLog}
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
