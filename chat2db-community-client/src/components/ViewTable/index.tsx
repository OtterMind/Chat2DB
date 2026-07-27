import { memo, useState, useEffect, useRef } from 'react';
import SearchResult from '@/blocks/SearchResult';
import { processResultDataList } from '@/utils/database';
import { IManageResultData, IViewTableParams } from '@/typings';
import { Spin } from 'antd';
import i18n from '@/i18n';
import useViewTable from '@/hooks/useViewTable';
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
  const { executing, executeSQL, stopExecuteSQL } = useViewTable();

  useEffect(() => {
    if (viewTableParams) {
      const requestGeneration = beginLatestRequest(requestGenerationRef);
      executeSQL(viewTableParams).then((data) => {
        if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
        const _resultDataList = processResultDataList(data, viewTableParams);
        setResultDataList(_resultDataList);
      });
    }
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  return (
    <div className={styles.container}>
      {executing && (
        <div className={styles.tableLoading}>
          <Spin />
          <div className={styles.stopExecuteSql} onClick={stopExecuteSQL}>
            {i18n('common.button.cancelRequest')}
          </div>
        </div>
      )}
      {resultDataList && <SearchResult viewTable resultDataList={resultDataList} />}
    </div>
  );
});

export default ViewTable;
