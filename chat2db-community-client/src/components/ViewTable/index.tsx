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
  active?: boolean;
  viewTableParams: IViewTableParams;
}

const ViewTable = memo<IProps>((props) => {
  const { active = true, viewTableParams } = props;
  const { styles } = useStyles();
  const [resultDataList, setResultDataList] = useState<IManageResultData[]>();
  const requestGenerationRef = useRef(0);
  const loadStartedRef = useRef(false);
  const { executing, executeSQL, stopExecuteSQL } = useViewTable();

  useEffect(() => {
    if (!active || !viewTableParams || loadStartedRef.current) {
      return;
    }

    loadStartedRef.current = true;
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    executeSQL(viewTableParams).then((data) => {
      if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
      const _resultDataList = processResultDataList(data, viewTableParams);
      setResultDataList(_resultDataList);
    });
  }, [active]);

  useEffect(
    () => () => {
      invalidateLatestRequest(requestGenerationRef);
      loadStartedRef.current = false;
    },
    [],
  );

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
      {resultDataList && <SearchResult active={active} viewTable resultDataList={resultDataList} />}
    </div>
  );
});

export default ViewTable;
