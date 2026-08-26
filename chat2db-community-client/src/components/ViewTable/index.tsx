import { memo, useCallback, useState, useEffect, useRef } from 'react';
import SearchResult from '@/blocks/SearchResult';
import { processResultDataList } from '@/utils/database';
import { IExecuteSqlParams, IManageResultData, IViewTableParams } from '@/typings';
import useViewTable from '@/hooks/useViewTable';
import useViewTablePaging from '@/hooks/useViewTablePaging';
import { replaceViewTableResult } from '@/hooks/viewTablePagingModel';
import SqlExecutionLoading from '@/components/SqlExecutionLoading';
import { useStyles } from './style';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

interface IProps {
  className?: string;
  viewTableParams: IViewTableParams;
}

const ViewTable = memo<IProps>((props) => {
  const { viewTableParams } = props;
  const { styles } = useStyles();
  const [resultDataList, setResultDataList] = useState<IManageResultData[]>();
  const requestGenerationRef = useRef(0);
  const {
    executing: initialExecuting,
    executeSQL: executeInitialTable,
    stopExecuteSQL: stopInitialTable,
  } = useViewTable();
  const { resultData: pagedResultData, executing: pagingExecuting, executePage, stopExecuteSQL: stopPaging } =
    useViewTablePaging();
  useEffect(() => {
    if (viewTableParams) {
      const requestGeneration = beginLatestRequest(requestGenerationRef);
      executeInitialTable(viewTableParams).then((data) => {
        if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
        const _resultDataList = processResultDataList(data, viewTableParams);
        setResultDataList(_resultDataList);
      });
    }
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, [executeInitialTable]);

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
      return executePage(executeSqlParams);
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
    </div>
  );
});

export default ViewTable;
