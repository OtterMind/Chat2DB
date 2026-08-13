import { memo, useState, ForwardedRef, forwardRef, useImperativeHandle, useRef, useMemo } from 'react';
import Pagination from '@/components/Pagination';
import i18n from '@/i18n';
import { IconButton } from '@chat2db/ui';
import ExportBar from '../ExportBar';
import { useStyles } from './style';
import { IChartItem, IManageResultData, IResultConfig } from '@/typings';
import { useUpdateEffect } from 'ahooks';
import { isEqualMemo, keyboardKey } from '@/utils';
import sqlService from '@/service/sql';
import _ from 'lodash';
import EditorChartModal, { EditChartModalRef } from '@/blocks/BI/ChartCardBox/EditorChartModal';
import DingChartModal, { DingChartModalRef } from '@/blocks/BI/ChartCardBox/DingChartModal';
import ChartNoAxesCombined from '@/components/LucideIcons/ChartNoAxesCombined';
import { useZoerStore } from '@/store/zoer';
import { Columns3 } from 'lucide-react';

export enum ToolbarOperationType {
  ADD_BLANK_ROW = 'addBlankRow',
  DELETE_ROW = 'deleteRow',
  REVOKE = 'revoke',
  VIEW_SQL = 'viewSql',
  UPDATE_SUBMIT = 'updateSubmit',
  EXECUTE_SQL = 'executeSql',
}

interface IProps {
  resultData: IManageResultData;
  handleToolbarOperation: (type: ToolbarOperationType) => void;
  hasOperationRecord: boolean;
  activeFilterCount?: number;
  onClearAllFilters?: () => void;
  onManageColumns: () => void;
}

export interface ResultSetToolbarRef {
  getPagingParams: () => { pageNo: number; pageSize: number };
}

const ResultSetToolbar = forwardRef((props: IProps, ref: ForwardedRef<ResultSetToolbarRef>) => {
  const {
    resultData,
    hasOperationRecord,
    handleToolbarOperation,
    activeFilterCount = 0,
    onClearAllFilters,
    onManageColumns,
  } = props;
  const { styles, cx } = useStyles();
  const editorChartModalRef = useRef<EditChartModalRef>(null);
  const dingChartModalRef = useRef<DingChartModalRef>(null);
  const [chartDetail, setChartDetail] = useState<IChartItem | null>(null);
  const [paginationConfig, setPaginationConfig] = useState<IResultConfig>({
    pageNo: resultData.pageNo,
    pageSize: resultData.pageSize,
    total: resultData.fuzzyTotal,
    hasNextPage: resultData.hasNextPage,
  });
  const zoerBoundInfo = useZoerStore((s) => s.zoerBoundInfo);

  const showCreateChart = useMemo(() => !zoerBoundInfo, [zoerBoundInfo]);

  useUpdateEffect(() => {
    // Use the latest fuzzy total when the displayed total is not numeric (for example, "1000+").
    let total = paginationConfig.total;
    const numericTotal = _.toNumber(total);
    if (_.isNaN(numericTotal)) {
      total = resultData.fuzzyTotal;
    }

    setPaginationConfig({
      pageNo: resultData.pageNo,
      pageSize: resultData.pageSize,
      total: resultData.fuzzyTotal,
      hasNextPage: resultData.hasNextPage,
    });

    setChartDetail(null);
  }, [resultData]);

  const onPageNoChange = (pageNo: number) => {
    setPaginationConfig({
      ...paginationConfig,
      pageNo,
    });
    setTimeout(() => {
      handleToolbarOperation(ToolbarOperationType.EXECUTE_SQL);
    }, 0);
  };

  const onPageSizeChange = (pageSize: number) => {
    setPaginationConfig({
      ...paginationConfig,
      pageNo: 1,
      pageSize,
    });
    setTimeout(() => {
      handleToolbarOperation(ToolbarOperationType.EXECUTE_SQL);
    }, 0);
  };

  const onClickTotalBtn = (): Promise<number> => {
    if (!resultData.executeSqlParams) return Promise.reject('executeSqlParams is not exist');
    return sqlService.getDMLCount(resultData.executeSqlParams).then((res) => {
      const config = { ...paginationConfig, total: res };
      setPaginationConfig(config);
      return res;
    });
  };

  const handleRefresh = () => {
    handleToolbarOperation(ToolbarOperationType.EXECUTE_SQL);
  };

  const createChart = () => {
    editorChartModalRef.current?.controlEditChartModal('editChart', {
      ...(chartDetail || {}),
      metaData: resultData,
      databaseInfo: resultData.executeSqlParams,
    });
  };

  useImperativeHandle(ref, () => ({
    getPagingParams: () => {
      return {
        pageNo: paginationConfig.pageNo,
        pageSize: paginationConfig.pageSize,
      };
    },
  }));

  return (
    <div className={styles.toolBar}>
      <div className={styles.toolBarItem}>
        <Pagination
          paginationConfig={paginationConfig}
          onPageNoChange={onPageNoChange}
          onPageSizeChange={onPageSizeChange}
          onClickTotalBtn={onClickTotalBtn}
        />
      </div>
      <div className={cx(styles.toolBarItem)}>
        <IconButton
          title={i18n('common.button.refresh') + `${keyboardKey.command} + R`}
          onClick={handleRefresh}
          size="sm"
          code="icon-refresh"
        />
      </div>
      {activeFilterCount >= 2 && onClearAllFilters && (
        <div className={cx(styles.toolBarItem)}>
          <IconButton
            title={i18n('workspace.text.clearAllFilters', activeFilterCount)}
            onClick={onClearAllFilters}
            size="sm"
            code="icon-data-filter"
          />
        </div>
      )}
      {resultData.canEdit && (
        <div className={cx(styles.toolBarItem, styles.editTableDataBar)}>
          {/* Add a row. */}
          <IconButton
            title={i18n('editTableData.tips.addRow')}
            onClick={() => {
              handleToolbarOperation(ToolbarOperationType.ADD_BLANK_ROW);
            }}
            size="sm"
            code="icon-add"
          />
          {/* Delete a row. */}
          <IconButton
            title={i18n('editTableData.tips.deleteRow')}
            onClick={() => {
              handleToolbarOperation(ToolbarOperationType.DELETE_ROW);
            }}
            size="sm"
            code="icon-minus"
          />
          {/* Undo. */}
          <IconButton
            // disabled={revokeDisableBarState}
            title={i18n('editTableData.tips.revert')}
            onClick={() => {
              handleToolbarOperation(ToolbarOperationType.REVOKE);
            }}
            size="sm"
            code="icon-revert-edit"
          />
          {/* View SQL. */}
          <IconButton
            disabled={!hasOperationRecord}
            title={i18n('editTableData.tips.previewPendingChanges')}
            onClick={() => {
              handleToolbarOperation(ToolbarOperationType.VIEW_SQL);
            }}
            size="sm"
            code="icon-view-sql"
          />
          {/* Submit for execution. */}
          <IconButton
            disabled={!hasOperationRecord}
            title={i18n('editTableData.tips.submit') + `${keyboardKey.command} + S`}
            onClick={() => {
              handleToolbarOperation(ToolbarOperationType.UPDATE_SUBMIT);
            }}
            size="sm"
            code="icon-submit-edit"
          />
        </div>
      )}
      <div className={cx(styles.toolBarItem, styles.editTableDataBar)}>
        {showCreateChart && (
          <>
            {/* Generate a report. */}
            <IconButton
              title={i18n('editTableData.tips.createChart')}
              onClick={() => {
                createChart();
              }}
              size="sm"
              className={styles.createChartIcon}
              icon={ChartNoAxesCombined}
            />
            <EditorChartModal
              submitEditorChartCallback={(data) => {
                dingChartModalRef.current?.openModal(data);
                setChartDetail(data);
              }}
              ref={editorChartModalRef}
            />
            <DingChartModal ref={dingChartModalRef} />
          </>
        )}
        <IconButton
          title={i18n('common.text.showHideColumns')}
          onClick={onManageColumns}
          size="sm"
          className={styles.createChartIcon}
          icon={Columns3}
        />
      </div>
      <div className={styles.toolBarRight}>
        <ExportBar resultData={resultData} />
      </div>
    </div>
  );
});

export default memo(ResultSetToolbar, (prevProps, nextProps) => {
  return isEqualMemo(
    [prevProps.resultData, nextProps.resultData],
    [prevProps.hasOperationRecord, nextProps.hasOperationRecord],
    [prevProps.handleToolbarOperation, nextProps.handleToolbarOperation],
    [prevProps.activeFilterCount, nextProps.activeFilterCount],
    [prevProps.onClearAllFilters, nextProps.onClearAllFilters],
    [prevProps.onManageColumns, nextProps.onManageColumns],
  );
});
