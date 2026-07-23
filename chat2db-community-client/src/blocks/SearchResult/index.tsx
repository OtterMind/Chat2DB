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
import CustomTabs, { ITabItem } from '@/components/Tabs';
import Iconfont from '@/components/Iconfont';
import { IManageResultData } from '@/typings';
import SearchResultItem from './components/SearchResultItem';
import Abstract from '@/components/Abstract';
import i18n from '@/i18n';
import { useStyles } from './style';
import { Empty, EmptyImage, IconfontSvg } from '@chat2db/ui';
import SQLPreview from '@/components/SQLPreview';
import ExecutionConsole from './components/ExecutionConsole';
import ExecutionMessages, { IExecutionMessageItem } from './components/ExecutionMessages';
import type { SqlExecutionLogRecord } from '@/service/sqlExecutionLog';
import {
  ABSTRACT_TAB_ID,
  CONSOLE_TAB_ID,
  MESSAGES_TAB_ID,
  getPreferredActiveTabId,
  getResultIdentity,
  hasLegacyResultTab,
  hasTabularResult,
  reduceActiveTabSelection,
} from './tabSelection';

interface IProps {
  className?: string;
  resultDataList: IManageResultData[];
  historyResultDataList?: IManageResultData[];
  executionLogRecords?: SqlExecutionLogRecord[];
  resultBatchKey?: number;
  forceOutputTab?: boolean;
  viewTable?: boolean;
  onClearExecutionLog?: () => void;
  onResultDataListChange?: (params: {
    resultDataList: IManageResultData[];
    historyResultDataList: IManageResultData[];
  }) => void;
}

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
      item.duration,
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

const SearchResult = forwardRef((props: IProps, ref: ForwardedRef<ISearchResultRef>) => {
  const { className, viewTable = false } = props;
  const consoleMode = props.executionLogRecords !== undefined;
  const { styles } = useStyles();
  const [resultDataList, setResultDataList] = useState<IManageResultData[] | null>(props.resultDataList);
  const [historyResultDataList, setHistoryResultDataList] = useState<IManageResultData[]>(
    props.historyResultDataList || [],
  );
  const [showHistory, setShowHistory] = useState(false);
  const [tabSelection, dispatchTabSelection] = useReducer(reduceActiveTabSelection, {
    activeTabId: getPreferredActiveTabId(props.resultDataList[props.resultDataList.length - 1], consoleMode),
  });
  const activeTabId = tabSelection.activeTabId;
  const knownResultVersionMapRef = useRef<Map<string, string>>(new Map());
  const latestTerminalLogVersion = getLatestTerminalLogVersion(props.executionLogRecords);
  const visibleHistoryResultDataList = useMemo(
    () => (consoleMode ? historyResultDataList.filter(hasTabularResult) : historyResultDataList),
    [consoleMode, historyResultDataList],
  );

  useImperativeHandle(ref, () => ({
    handleDemo: () => {},
  }));

  useEffect(() => {
    dispatchTabSelection({ type: 'resetPreference' });
    setShowHistory(false);
  }, [props.resultBatchKey]);

  useEffect(() => {
    const nextResultDataList = props.resultDataList || [];
    const previousResultVersions = knownResultVersionMapRef.current;
    const changedResults = nextResultDataList.filter((item) => {
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
  }, [props.resultDataList, consoleMode, props.forceOutputTab]);

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
      dispatchTabSelection({ type: 'activate', tabId: CONSOLE_TAB_ID });
    }
  }, [consoleMode, latestTerminalLogVersion]);

  useEffect(() => {
    if (consoleMode && props.forceOutputTab) {
      dispatchTabSelection({ type: 'activate', tabId: CONSOLE_TAB_ID });
    }
  }, [consoleMode, props.forceOutputTab, props.resultBatchKey]);

  const onChange = useCallback((uuid) => {
    dispatchTabSelection({ type: 'activate', tabId: uuid });
  }, []);

  const tabsList = useMemo(() => {
    const visibleResultDataList = showHistory
      ? [...(resultDataList || []), ...visibleHistoryResultDataList]
      : resultDataList || [];
    if (!visibleResultDataList?.length) return [];
    const newResultDataList = visibleResultDataList?.filter(consoleMode ? hasTabularResult : hasLegacyResultTab);

    const tabsListRes =
      newResultDataList?.map((queryResultData, index) => {
        return {
          prefixIcon: (
            <Iconfont
              key={index}
              className={classnames(styles[queryResultData.success ? 'successIcon' : 'failIcon'], styles.statusIcon)}
              code={queryResultData.success ? '\ue605' : '\ue87c'}
            />
          ),
          popover: (
            <SQLPreview
              source="search-result-tab-popover"
              sql={`${
                queryResultData.comment ? `-- ${queryResultData.comment}\n` : ''
              }${queryResultData.originalSql?.replaceAll('\r\n', '\n')}`}
            />
          ),
          label:
            queryResultData.displayName || queryResultData.comment || i18n('common.text.executionResult', index + 1),
          key: queryResultData.uuid!,
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
  }, [activeTabId, resultDataList, visibleHistoryResultDataList, showHistory, consoleMode]);

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
      [...(resultDataList || []), ...historyResultDataList].some(
        (item) => hasTabularResult(item) && item.extra?.resultKey === resultKey,
      ),
    [resultDataList, historyResultDataList],
  );

  const handleOpenResult = useCallback(
    (resultKey: string) => {
      const currentResult = (resultDataList || []).find(
        (item) => hasTabularResult(item) && item.extra?.resultKey === resultKey,
      );
      if (currentResult?.uuid) {
        dispatchTabSelection({ type: 'activate', tabId: currentResult.uuid });
        return;
      }
      const historyResult = historyResultDataList.find(
        (item) => hasTabularResult(item) && item.extra?.resultKey === resultKey,
      );
      if (historyResult?.uuid) {
        setShowHistory(true);
        dispatchTabSelection({ type: 'activate', tabId: historyResult.uuid });
      }
    },
    [resultDataList, historyResultDataList],
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
      children: (
        <ExecutionConsole
          records={props.executionLogRecords || []}
          onClear={props.onClearExecutionLog || (() => {})}
          onOpenResult={handleOpenResult}
          isResultAvailable={isResultAvailable}
        />
      ),
      canClosed: false,
    };
  }, [
    consoleMode,
    props.executionLogRecords,
    props.onClearExecutionLog,
    handleOpenResult,
    isResultAvailable,
    styles.abstractIcon,
  ]);

  const onEdit = useCallback(
    (type: 'add' | 'remove', data: ITabItem[], list?: ITabItem[]) => {
      if (type === 'remove') {
        const isCloseAll = list === undefined;
        if (isCloseAll) {
          const nextResultDataList: IManageResultData[] = [];
          const nextHistoryResultDataList: IManageResultData[] = [];
          setResultDataList(nextResultDataList);
          setHistoryResultDataList(nextHistoryResultDataList);
          props.onResultDataListChange?.({
            resultDataList: nextResultDataList,
            historyResultDataList: nextHistoryResultDataList,
          });
          return;
        }
        const closedKeys = new Set((data || []).map((item) => item.key));
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
      {!!visibleHistoryResultDataList.length && !viewTable && (
        <div className={styles.historyBar}>
          <button
            className={styles.historyButton}
            onClick={() => {
              setShowHistory((value) => !value);
              if (showHistory && visibleHistoryResultDataList.some((item) => item.uuid === activeTabId)) {
                dispatchTabSelection({
                  type: 'activate',
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
