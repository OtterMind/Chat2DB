import {
  memo,
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useState,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
  useRef,
} from 'react';
import classnames from 'classnames';
import { Dropdown, type MenuProps } from 'antd';
import { ArrowDownUp, Check, SlidersHorizontal } from 'lucide-react';
import CustomTabs, { ITabItem } from '@/components/Tabs';
import { IManageResultData } from '@/typings';
import SearchResultItem from './components/SearchResultItem';
import Abstract from '@/components/Abstract';
import i18n from '@/i18n';
import { useStyles } from './style';
import { Empty, EmptyImage, IconButton, IconfontSvg } from '@chat2db/ui';
import SQLPreview from '@/components/SQLPreview';
import ExecutionConsole from './components/ExecutionConsole';
import ExecutionMessages, { IExecutionMessageItem } from './components/ExecutionMessages';
import type { SqlExecutionLogRecord } from '@/service/sqlExecutionLog';
import type { SqlExecutionResultIdentity } from '@/service/sqlExecutionStream';
import {
  createResultTabOrderStorageKey,
  getResultTabPreferenceStorage,
  orderExecutionResultsByBatch,
  persistResultTabOrder,
  readResultTabOrder,
  subscribeResultTabOrder,
  type ResultTabOrder,
} from './resultTabPreferences';
import {
  ABSTRACT_TAB_ID,
  CONSOLE_TAB_ID,
  MESSAGES_TAB_ID,
  getConsoleResultTabLabel,
  getPreferredActiveTabId,
  getResultIdentity,
  hasLegacyResultTab,
  hasTabularResult,
  reduceActiveTabSelection,
} from './tabSelection';
import { ShortcutAction } from '@/constants/shortcut';
import { useGlobalStore } from '@/store/global';
import { retainPinnedResults } from './resultTabPinning';

interface IProps {
  className?: string;
  resultDataList: IManageResultData[];
  historyResultDataList?: IManageResultData[];
  executionLogRecords?: SqlExecutionLogRecord[];
  resultBatchKey?: number;
  forceOutputTab?: boolean;
  keepExecutionLogHistory?: boolean;
  keepResultHistory?: boolean;
  showExecutionResultCoordinates?: boolean;
  closeActiveResultShortcutEnabled?: boolean;
  viewTable?: boolean;
  onClearExecutionLog?: () => void;
  onKeepExecutionLogHistoryChange?: (keepHistory: boolean) => void;
  onKeepResultHistoryChange?: (keepHistory: boolean) => void;
  onResultDataListChange?: (params: {
    resultDataList: IManageResultData[];
    historyResultDataList: IManageResultData[];
    closedResultIdentities: SqlExecutionResultIdentity[];
  }) => void;
}

const RESULT_TAB_ORDER_STORAGE_KEY = createResultTabOrderStorageKey('community', __RUNTIME_ENV__);

export interface ISearchResultRef {
  handleDemo: () => void;
}

function getResultVersion(item: IManageResultData, consoleMode: boolean) {
  if (!consoleMode) {
    return [
      getResultIdentity(item),
      item.extra?.executionSequence,
      item.extra?.statementSequence,
      item.extra?.resultSequence,
      item.extra?.resultKey,
      item.resultSetId,
      item.executionMetrics?.startedAtEpochMs,
      item.executionMetrics?.finishedAtEpochMs,
      item.executionMetrics?.totalDurationMs,
      item.executionMetrics?.executeDurationMs,
      item.executionMetrics?.fetchDurationMs,
      item.executionMetrics?.fetchedRowCount,
      item.dataList?.length,
      item.extra?.messages?.length,
    ].join('|');
  }
  return [getResultIdentity(item), hasTabularResult(item), item.success].join('|');
}

function getLatestTerminalLogVersion(records?: SqlExecutionLogRecord[]) {
  if (!records) return undefined;
  for (let index = records.length - 1; index >= 0; index -= 1) {
    const record = records[index];
    if (record.status === 'failed' || record.status === 'cancelled') {
      return `${record.id}:${record.status}`;
    }
  }
  return undefined;
}

function getSqlExecutionResultIdentity(result: IManageResultData): SqlExecutionResultIdentity | undefined {
  const executionId = result.extra?.executionId;
  const resultKey = result.extra?.resultKey;
  return typeof executionId === 'string' && executionId && typeof resultKey === 'string' && resultKey
    ? { executionId, resultKey }
    : undefined;
}

const SearchResult = forwardRef((props: IProps, ref: ForwardedRef<ISearchResultRef>) => {
  const { className, viewTable = false } = props;
  const consoleMode = props.executionLogRecords !== undefined;
  const { styles } = useStyles();
  const { dataTableSettings, updateDataTableSettings } = useGlobalStore((state) => ({
    dataTableSettings: state.dataTableSettings,
    updateDataTableSettings: state.updateDataTableSettings,
  }));
  const showFieldType = dataTableSettings.showFieldType ?? true;
  const showFieldComment = dataTableSettings.showFieldComment ?? true;
  const [resultDataList, setResultDataList] = useState<IManageResultData[] | null>(props.resultDataList);
  const [historyResultDataList, setHistoryResultDataList] = useState<IManageResultData[]>(
    props.historyResultDataList || [],
  );
  const [showHistory, setShowHistory] = useState(false);
  const [pinnedResultTabKeys, setPinnedResultTabKeys] = useState<Set<string>>(() => new Set());
  const resultDataListRef = useRef(resultDataList);
  const historyResultDataListRef = useRef(historyResultDataList);
  const pinnedResultTabKeysRef = useRef(pinnedResultTabKeys);
  resultDataListRef.current = resultDataList;
  historyResultDataListRef.current = historyResultDataList;
  pinnedResultTabKeysRef.current = pinnedResultTabKeys;
  const consoleResultDataList = useMemo(
    () => [...(resultDataList || []), ...historyResultDataList],
    [resultDataList, historyResultDataList],
  );
  const [resultTabOrder, setResultTabOrder] = useState<ResultTabOrder>(() =>
    readResultTabOrder(getResultTabPreferenceStorage(), RESULT_TAB_ORDER_STORAGE_KEY),
  );
  const orderedConsoleResultDataList = useMemo(
    () => orderExecutionResultsByBatch(consoleResultDataList, resultTabOrder),
    [consoleResultDataList, resultTabOrder],
  );
  const [tabSelection, dispatchTabSelection] = useReducer(reduceActiveTabSelection, {
    activeTabId: getPreferredActiveTabId(props.resultDataList[props.resultDataList.length - 1], consoleMode),
    followPreferredTabs: true,
  });
  const activeTabId = tabSelection.activeTabId;
  const knownResultVersionMapRef = useRef<Map<string, string>>(new Map());
  const latestTerminalLogVersion = getLatestTerminalLogVersion(props.executionLogRecords);
  const visibleHistoryResultDataList = useMemo(
    () => (consoleMode ? [] : historyResultDataList),
    [consoleMode, historyResultDataList],
  );

  useImperativeHandle(ref, () => ({
    handleDemo: () => {},
  }));

  useEffect(
    () =>
      subscribeResultTabOrder((storageKey, order) => {
        if (storageKey === RESULT_TAB_ORDER_STORAGE_KEY) {
          setResultTabOrder(order);
        }
      }),
    [],
  );

  useEffect(() => {
    if (consoleMode) {
      dispatchTabSelection({ type: 'startAutoFollow', tabId: CONSOLE_TAB_ID });
    } else {
      dispatchTabSelection({ type: 'resetPreference' });
      setShowHistory(false);
    }
  }, [props.resultBatchKey, consoleMode]);

  useEffect(() => {
    const incomingResultDataList = props.resultDataList || [];
    const incomingHistoryResultDataList = props.historyResultDataList || [];
    const nextResultDataList = retainPinnedResults(
      incomingResultDataList,
      [...(resultDataListRef.current || []), ...historyResultDataListRef.current],
      pinnedResultTabKeysRef.current,
      incomingHistoryResultDataList,
    );
    const previousResultVersions = knownResultVersionMapRef.current;
    const changedResults = incomingResultDataList.filter((item) => {
      const resultKey = getResultIdentity(item);
      if (!resultKey) {
        return false;
      }
      return previousResultVersions.get(resultKey) !== getResultVersion(item, consoleMode);
    });
    const latestChangedResult = changedResults[changedResults.length - 1];

    knownResultVersionMapRef.current = new Map(
      nextResultDataList
        .map((item) => [getResultIdentity(item), getResultVersion(item, consoleMode)] as const)
        .filter((entry): entry is readonly [string, string] => !!entry[0]),
    );
    setResultDataList(nextResultDataList);

    if (latestChangedResult) {
      dispatchTabSelection({
        type: 'prefer',
        tabId: getPreferredActiveTabId(latestChangedResult, consoleMode, props.forceOutputTab),
      });
    }
  }, [props.resultDataList, props.historyResultDataList, consoleMode, props.forceOutputTab]);

  useEffect(() => {
    const nextHistoryResultDataList = props.historyResultDataList || [];
    setHistoryResultDataList(nextHistoryResultDataList);
  }, [props.historyResultDataList]);

  useEffect(() => {
    if (!visibleHistoryResultDataList.length && showHistory) {
      setShowHistory(false);
    }
  }, [visibleHistoryResultDataList.length, showHistory]);

  useEffect(() => {
    if (consoleMode && latestTerminalLogVersion) {
      dispatchTabSelection({ type: 'activateAutomatically', tabId: CONSOLE_TAB_ID });
    }
  }, [consoleMode, latestTerminalLogVersion]);

  useEffect(() => {
    if (consoleMode && props.forceOutputTab) {
      dispatchTabSelection({ type: 'activateAutomatically', tabId: CONSOLE_TAB_ID });
    }
  }, [consoleMode, props.forceOutputTab, props.resultBatchKey]);

  const onChange = useCallback(
    (uuid) => {
      dispatchTabSelection({ type: consoleMode ? 'activateByUser' : 'activate', tabId: uuid });
    },
    [consoleMode],
  );

  const tabsList = useMemo(() => {
    const visibleResultDataList = consoleMode
      ? orderedConsoleResultDataList
      : showHistory
        ? [...(resultDataList || []), ...visibleHistoryResultDataList]
        : resultDataList || [];
    if (!visibleResultDataList?.length) return [];
    const newResultDataList = visibleResultDataList
      ?.filter(consoleMode ? hasTabularResult : hasLegacyResultTab)
      .sort(
        (left, right) =>
          Number(pinnedResultTabKeys.has(String(right.uuid))) -
          Number(pinnedResultTabKeys.has(String(left.uuid))),
      );

    const tabsListRes =
      newResultDataList?.map((queryResultData, index) => {
        return {
          prefixIcon: <IconfontSvg key={index} className={styles.resultTabIcon} size="sm" code="icon-table" />,
          popover: (
            <SQLPreview
              source="search-result-tab-popover"
              sql={`${
                queryResultData.comment ? `-- ${queryResultData.comment}\n` : ''
              }${queryResultData.originalSql?.replaceAll('\r\n', '\n')}`}
            />
          ),
          label: consoleMode
            ? getConsoleResultTabLabel(
                queryResultData.displayName ||
                  queryResultData.comment ||
                  i18n('common.text.executionResult', index + 1),
                props.showExecutionResultCoordinates ?? true,
              )
            : queryResultData.displayName ||
              queryResultData.comment ||
              i18n('common.text.executionResult', index + 1),
          key: queryResultData.uuid!,
          pinned: pinnedResultTabKeys.has(String(queryResultData.uuid)),
          children: (
            <SearchResultItem
              active={activeTabId === queryResultData.uuid}
              viewTable={viewTable || queryResultData.canEdit}
              resultData={queryResultData}
            />
          ),
        };
      }) || [];

    return tabsListRes;
  }, [
    activeTabId,
    resultDataList,
    visibleHistoryResultDataList,
    showHistory,
    consoleMode,
    orderedConsoleResultDataList,
    props.showExecutionResultCoordinates,
    styles.resultTabIcon,
    viewTable,
    pinnedResultTabKeys,
  ]);

  const resultTabKeySet = useMemo(
    () => new Set(consoleResultDataList.map((item) => String(item.uuid)).filter(Boolean)),
    [consoleResultDataList],
  );

  const handlePinResultTab = useCallback((tab: ITabItem) => {
    const key = String(tab.key);
    setPinnedResultTabKeys((current) => {
      const next = new Set(current);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }, []);

  const handleResultTabOrderChange = useCallback((nextOrder: ResultTabOrder) => {
    setResultTabOrder(nextOrder);
    persistResultTabOrder(getResultTabPreferenceStorage(), RESULT_TAB_ORDER_STORAGE_KEY, nextOrder);
  }, []);

  const handleResultSettingsClick = useCallback<NonNullable<MenuProps['onClick']>>(
    ({ key }) => {
      if (key === 'toggle-result-tab-order') {
        handleResultTabOrderChange(
          resultTabOrder === 'oldest-first' ? 'newest-first' : 'oldest-first',
        );
      } else if (key === 'keep-result-history') {
        props.onKeepResultHistoryChange?.(!(props.keepResultHistory ?? true));
      } else if (key === 'show-field-type') {
        updateDataTableSettings({ ...dataTableSettings, showFieldType: !showFieldType });
      } else if (key === 'show-field-comment') {
        updateDataTableSettings({ ...dataTableSettings, showFieldComment: !showFieldComment });
      }
    },
    [
      dataTableSettings,
      handleResultTabOrderChange,
      props.keepResultHistory,
      props.onKeepResultHistoryChange,
      resultTabOrder,
      showFieldComment,
      showFieldType,
      updateDataTableSettings,
    ],
  );

  const executionMessages = useMemo<IExecutionMessageItem[]>(() => {
    if (consoleMode || !resultDataList?.length) {
      return [];
    }
    return resultDataList.flatMap((item, index) =>
      (item.extra?.messages || []).map((message) => ({
        ...message,
        comment: item.comment,
        resultSetId: item.resultSetId,
        executionIndex: index + 1,
      })),
    );
  }, [resultDataList, consoleMode]);

  const historyExecutionMessages = useMemo<IExecutionMessageItem[]>(() => {
    if (consoleMode || !historyResultDataList.length) {
      return [];
    }
    return historyResultDataList.flatMap((item, index) =>
      (item.extra?.messages || []).map((message) => ({
        ...message,
        comment: item.comment,
        resultSetId: item.resultSetId,
        executionIndex: index + 1,
      })),
    );
  }, [historyResultDataList, consoleMode]);

  const abstract = useMemo(() => {
    if (consoleMode || !resultDataList?.length) {
      return undefined;
    }
    return {
      prefixIcon: <IconfontSvg className={styles.abstractIcon} size="sm" code="icon-terminal" />,
      popover: i18n('common.text.overview'),
      label: i18n('common.text.overview'),
      key: ABSTRACT_TAB_ID,
      children: <Abstract data={resultDataList} />,
      canClosed: false,
    };
  }, [resultDataList, consoleMode, styles.abstractIcon]);

  const messageTab = useMemo(() => {
    if (consoleMode || !executionMessages.length) {
      return undefined;
    }
    return {
      prefixIcon: <IconfontSvg className={styles.abstractIcon} size="sm" code="icon-terminal" />,
      popover: i18n('common.title.message'),
      label: `${i18n('common.title.message')} (${executionMessages.length})`,
      key: MESSAGES_TAB_ID,
      children: <ExecutionMessages data={executionMessages} />,
      canClosed: false,
    };
  }, [executionMessages, consoleMode, styles.abstractIcon]);

  const historyMessageTab = useMemo(() => {
    if (consoleMode || !showHistory || !historyExecutionMessages.length) {
      return undefined;
    }
    return {
      prefixIcon: <IconfontSvg className={styles.abstractIcon} size="sm" code="icon-terminal" />,
      popover: i18n('common.text.historyMessages'),
      label: `${i18n('common.text.historyMessages')} (${historyExecutionMessages.length})`,
      key: 'history-messages',
      children: <ExecutionMessages data={historyExecutionMessages} />,
      canClosed: false,
    };
  }, [historyExecutionMessages, showHistory, consoleMode, styles.abstractIcon]);

  const isResultAvailable = useCallback(
    (resultKey: string) =>
      consoleResultDataList.some(
        (item) => hasTabularResult(item) && item.extra?.resultKey === resultKey,
      ),
    [consoleResultDataList],
  );

  const handleOpenResult = useCallback(
    (resultKey: string) => {
      const currentResult = (resultDataList || []).find(
        (item) => hasTabularResult(item) && item.extra?.resultKey === resultKey,
      );
      if (currentResult?.uuid) {
        dispatchTabSelection({ type: consoleMode ? 'activateByUser' : 'activate', tabId: currentResult.uuid });
        return;
      }
      const historyResult = historyResultDataList.find(
        (item) => hasTabularResult(item) && item.extra?.resultKey === resultKey,
      );
      if (historyResult?.uuid) {
        if (!consoleMode) {
          setShowHistory(true);
        }
        dispatchTabSelection({ type: consoleMode ? 'activateByUser' : 'activate', tabId: historyResult.uuid });
      }
    },
    [resultDataList, historyResultDataList, consoleMode],
  );

  const consoleTab = useMemo(() => {
    if (!consoleMode) {
      return undefined;
    }
    return {
      prefixIcon: <IconfontSvg className={styles.abstractIcon} size="sm" code="icon-terminal" />,
      popover: i18n('common.text.output'),
      label: i18n('common.text.output'),
      key: CONSOLE_TAB_ID,
      styles: {
        flex: '0 0 96px',
        width: '96px',
        maxWidth: '96px',
      },
      children: (
        <ExecutionConsole
          records={props.executionLogRecords || []}
          keepHistory={props.keepExecutionLogHistory ?? true}
          onClear={props.onClearExecutionLog || (() => {})}
          onKeepHistoryChange={props.onKeepExecutionLogHistoryChange || (() => {})}
          onOpenResult={handleOpenResult}
          isResultAvailable={isResultAvailable}
        />
      ),
      canClosed: false,
    };
  }, [
    consoleMode,
    props.executionLogRecords,
    props.keepExecutionLogHistory,
    props.onClearExecutionLog,
    props.onKeepExecutionLogHistoryChange,
    handleOpenResult,
    isResultAvailable,
    styles.abstractIcon,
  ]);

  const onEdit = useCallback(
    (type: 'add' | 'remove', data: ITabItem[]) => {
      if (type === 'remove') {
        const closedKeys = new Set((data || []).map((item) => item.key));
        const closedResultIdentities = [...(resultDataList || []), ...historyResultDataList]
          .filter((result) => closedKeys.has(result.uuid || ''))
          .map(getSqlExecutionResultIdentity)
          .filter((identity): identity is SqlExecutionResultIdentity => identity !== undefined);
        const newResultDataList = resultDataList?.filter((d) => {
          return data.findIndex((item) => item.key === d.uuid) === -1;
        });
        const newHistoryResultDataList = historyResultDataList.filter((d) => !closedKeys.has(d.uuid || ''));

        const nextResultDataList = newResultDataList || [];
        const nextHistoryResultDataList = newHistoryResultDataList || [];
        setResultDataList(nextResultDataList);
        setHistoryResultDataList(nextHistoryResultDataList);
        props.onResultDataListChange?.({
          resultDataList: nextResultDataList,
          historyResultDataList: nextHistoryResultDataList,
          closedResultIdentities,
        });
      }
    },
    [resultDataList, historyResultDataList, props.onResultDataListChange],
  );

  const tabsItems = useMemo(() => {
    const staticTabs = consoleMode ? [consoleTab] : [abstract, messageTab, historyMessageTab];
    return [...staticTabs.filter(Boolean), ...tabsList] as ITabItem[];
  }, [tabsList, consoleMode, consoleTab, abstract, messageTab, historyMessageTab]);

  useEffect(() => {
    const availableTabIds = tabsItems.map((item) => String(item.key));
    dispatchTabSelection({ type: 'tabsChanged', availableTabIds });
  }, [tabsItems]);

  return (
    <div className={classnames(className, styles.searchResult)}>
      {!consoleMode && !!visibleHistoryResultDataList.length && !viewTable && (
        <div className={styles.historyBar}>
          <button
            className={styles.historyButton}
            onClick={() => {
              setShowHistory((value) => !value);
              if (showHistory && visibleHistoryResultDataList.some((item) => item.uuid === activeTabId)) {
                dispatchTabSelection({
                  type: 'activateByUser',
                  tabId: consoleMode ? CONSOLE_TAB_ID : ABSTRACT_TAB_ID,
                });
              }
            }}
          >
            {showHistory
              ? i18n('common.button.hideHistoryResult')
              : `${i18n('common.button.viewHistoryResult')} (${visibleHistoryResultDataList.length})`}
          </button>
        </div>
      )}
      {tabsItems?.length ? (
        <CustomTabs
          hideAdd
          activeKey={activeTabId}
          className={styles.tabs}
          onChange={onChange as any}
          onEdit={onEdit as any}
          items={tabsItems}
          concealTabHeader={viewTable}
          height={30}
          tabMaxWidth="200px"
          uniformTabWidth
          tabBarExtraContent={
            consoleMode ? (
              <Dropdown
                menu={{
                  selectable: true,
                  selectedKeys: [
                    ...((props.keepResultHistory ?? true) ? ['keep-result-history'] : []),
                    ...(showFieldType ? ['show-field-type'] : []),
                    ...(showFieldComment ? ['show-field-comment'] : []),
                  ],
                  items: [
                    {
                      key: 'toggle-result-tab-order',
                      icon: <ArrowDownUp size={14} />,
                      label: `${i18n('common.text.order')}: ${i18n(
                        resultTabOrder === 'oldest-first'
                          ? 'common.text.oldestFirst'
                          : 'common.text.newestFirst',
                      )}`,
                    },
                    {
                      key: 'keep-result-history',
                      icon: <Check opacity={(props.keepResultHistory ?? true) ? 1 : 0} size={14} />,
                      label: i18n('common.button.keepHistoryResult'),
                    },
                    { type: 'divider' },
                    {
                      key: 'show-field-type',
                      icon: <Check opacity={showFieldType ? 1 : 0} size={14} />,
                      label: i18n('common.text.showFieldType'),
                    },
                    {
                      key: 'show-field-comment',
                      icon: <Check opacity={showFieldComment ? 1 : 0} size={14} />,
                      label: i18n('common.text.showFieldComment'),
                    },
                  ],
                  onClick: handleResultSettingsClick,
                }}
                placement="bottomRight"
                trigger={['click']}
              >
                <button
                  type="button"
                  className={styles.iconButtonTrigger}
                  aria-label={i18n('common.text.resultSettings')}
                >
                  <IconButton
                    aria-hidden
                    icon={SlidersHorizontal}
                    size={{ boxSize: 24, iconSize: 14, borderRadius: 4 }}
                    title={i18n('common.text.resultSettings')}
                    tooltipPlacement="top"
                  />
                </button>
              </Dropdown>
            ) : undefined
          }
          activateOnContextMenu={consoleMode}
          activeTabScrollKey={consoleMode ? resultTabOrder : undefined}
          closeActiveTabOnCloseShortcut={consoleMode && props.closeActiveResultShortcutEnabled}
          closeShortcutAction={consoleMode ? ShortcutAction.CloseCurrentConsole : undefined}
          contextActions={{ pin: true }}
          contextActionAvailability={(tab) => ({ pin: resultTabKeySet.has(String(tab.key)) })}
          contextActionHandlers={{ pin: handlePinResultTab }}
        />
      ) : (
        <div className={styles.noData}>
          <Empty image={EmptyImage.Common} title={i18n('common.text.noData')} />
        </div>
      )}
    </div>
  );
});

export default memo(SearchResult);
