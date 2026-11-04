import { memo, useCallback, useState, useEffect, useRef } from 'react';
import { Button } from 'antd';
import i18n from '@/i18n';
import SearchResult from '@/blocks/SearchResult';
import { processResultDataList } from '@/utils/database';
import { IExecuteSqlParams, IManageResultData, IViewTableParams } from '@/typings';
import useViewTable from '@/hooks/useViewTable';
import useViewTablePaging from '@/hooks/useViewTablePaging';
import { replaceViewTableResult } from '@/hooks/viewTablePagingModel';
import SqlExecutionLoading from '@/components/SqlExecutionLoading';
import { useStyles } from './style';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';
import { IMPORT_TARGET_TABLE_REFRESH_EVENT } from '@/store/importExport/taskCenterUtils';
import { getViewTableTargetKey } from './viewTableTarget';

interface IProps {
  className?: string;
  viewTableParams: IViewTableParams;
}

const ViewTable = memo<IProps>((props) => {
  const { viewTableParams } = props;
  const { styles } = useStyles();
  const [resultDataList, setResultDataList] = useState<IManageResultData[]>();
  const [loadInterrupted, setLoadInterrupted] = useState(false);
  const requestGenerationRef = useRef(0);
  const {
    executing: initialExecuting,
    executeSQL: executeInitialTable,
    stopExecuteSQL: stopInitialTable,
  } = useViewTable();
  const { resultData: pagedResultData, executing: pagingExecuting, executePage, stopExecuteSQL: stopPaging } =
    useViewTablePaging();
  // The workspace tab layer keeps every open tab mounted and rebuilds the tab
  // bodies whenever unrelated workspace state changes, so `viewTableParams` is a
  // new object on most parent renders. Loading is keyed on the table identity
  // and reads the latest params from a ref, otherwise every tab switch re-issues
  // the table browse request.
  const viewTableParamsRef = useRef(viewTableParams);
  const viewTableTargetKey = getViewTableTargetKey(viewTableParams);

  // This effect must stay above the loading effect: effects run in declaration
  // order within one commit, so an in-place table change refreshes the ref
  // before the load reads it.
  useEffect(() => {
    viewTableParamsRef.current = viewTableParams;
  }, [viewTableParams]);

  const refreshCurrentTable = useCallback(() => {
    const params = viewTableParamsRef.current;
    if (params) {
      const requestGeneration = beginLatestRequest(requestGenerationRef);
      setLoadInterrupted(false);
      executeInitialTable(params)
        .then((data) => {
          if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
          const _resultDataList = processResultDataList(data, params);
          setResultDataList(_resultDataList);
        })
        .catch(() => {
          // A cancelled or failed first page leaves the tab empty. Loading is
          // keyed on the table identity now, so nothing retries on its own:
          // surface the state and let the retry button below re-run this load.
          if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
          setLoadInterrupted(true);
        });
    }
  }, [executeInitialTable]);

  useEffect(() => {
    refreshCurrentTable();
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, [refreshCurrentTable, viewTableTargetKey]);

  useEffect(() => {
    const handleImportRefresh = (event: Event) => {
      const target = (event as CustomEvent<IViewTableParams>).detail;
      if (getViewTableTargetKey(target) === viewTableTargetKey) {
        refreshCurrentTable();
      }
    };
    window.addEventListener(IMPORT_TARGET_TABLE_REFRESH_EVENT, handleImportRefresh);
    return () => window.removeEventListener(IMPORT_TARGET_TABLE_REFRESH_EVENT, handleImportRefresh);
  }, [refreshCurrentTable, viewTableTargetKey]);

  useEffect(() => {
    if (pagedResultData) {
      setResultDataList((current) => replaceViewTableResult(current, pagedResultData));
    }
  }, [pagedResultData]);

  const handleResultPagingChange = useCallback(
    (_resultData: IManageResultData, executeSqlParams: IExecuteSqlParams) => {
      if (executeSqlParams.dataSourceId == null || !executeSqlParams.sql) {
        return;
      }
      return executePage(executeSqlParams, _resultData);
    },
    [executePage],
  );

  return (
    <div className={styles.container}>
      {(initialExecuting || pagingExecuting) && (
        <SqlExecutionLoading onCancel={pagingExecuting ? stopPaging : stopInitialTable} />
      )}
      {resultDataList && (
        <SearchResult
          viewTable
          resultDataList={resultDataList}
          onResultPagingChange={handleResultPagingChange}
        />
      )}
      {!resultDataList && loadInterrupted && !initialExecuting && !pagingExecuting && (
        <div className={styles.retryBox}>
          <div className={styles.retryText}>{i18n('common.text.tableDataNotLoaded')}</div>
          <Button type="primary" size="small" onClick={refreshCurrentTable}>
            {i18n('common.button.retry')}
          </Button>
        </div>
      )}
    </div>
  );
});

export default ViewTable;
