import { ForwardedRef, forwardRef, memo, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { Button, Input, Tooltip } from 'antd';
import i18n from '@/i18n';
import Iconfont from '@/components/Iconfont';
import { IManageResultData, IResultCell } from '@/typings';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import { useStyles } from './style';
import { getResultFieldAtTableColumn } from '../ResultSetTable/columnState';

interface IOpenRowDetailParams {
  tableInstance: ITableInstance;
  col: number;
  row: number;
  rowId?: string | number;
  field?: string;
}

export interface IViewDataParams {
  tableInstance: ITableInstance;
  col: number;
  row: number;
  field: string;
  cellMeta?: IResultCell;
  rowId?: string | number;
}

export interface IChangeDataParams extends IViewDataParams {
  value: string | null;
}

interface IRowDetailItem {
  label: string;
  field: string;
  col: number;
  value: string | number | boolean | null;
  largeValue?: boolean;
  readOnly?: boolean;
  cellMeta?: IResultCell;
}

interface IRowDetailState {
  tableInstance: ITableInstance;
  row: number;
  rowId?: string | number;
  activeField?: string;
  items: IRowDetailItem[];
}

interface IProps {
  resultData: IManageResultData;
  isFieldReadOnly?: (field: string) => boolean;
  onActiveFieldChange?: (params: IViewDataParams) => void;
  onViewData?: (params: IViewDataParams) => void;
  onChangeData?: (params: IChangeDataParams) => void;
}

export interface RowDetailRef {
  openPanel: (params: IOpenRowDetailParams) => void;
}

const formatValue = (value: any, cellMeta?: IResultCell) => {
  if (cellMeta?.largeValue) {
    // Large fields contain only a backend preview; the full content is loaded on demand.
    return cellMeta.value ?? null;
  }
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value === 'object') {
    return JSON.stringify(value, null, 2);
  }
  return value;
};

const RowDetail = forwardRef((props: IProps, ref: ForwardedRef<RowDetailRef>) => {
  const { resultData, isFieldReadOnly, onActiveFieldChange, onViewData, onChangeData } = props;
  const { styles, cx } = useStyles();
  const [rowDetail, setRowDetail] = useState<IRowDetailState | null>(null);
  const activeItemRef = useRef<HTMLDivElement>(null);

  const headerMap = useMemo(() => {
    return resultData.headerList || [];
  }, [resultData.headerList]);

  const openPanel = ({ tableInstance, col, row, field: activeField }: IOpenRowDetailParams) => {
    const recordCol = col > 0 ? col : 1;
    const record = tableInstance.getRecordByCell(recordCol, row);
    if (!record) {
      return;
    }

    const cellMetaList: IResultCell[] = record.__CHAT2DB_CELL_META__ || [];
    const items = headerMap.slice(1).map((header, index) => {
      const sourceIndex = index + 1;
      const field = String(sourceIndex);
      const tableCol = tableInstance.getTableIndexByField(field);
      const cellMeta = cellMetaList[sourceIndex];
      const column = tableInstance.columns?.find((item: any) => String(item.field) === field) as any;
      return {
        label: header?.name || column?.originalData?.name || field,
        field,
        col: tableCol,
        value: formatValue(record[field], cellMeta),
        largeValue: cellMeta?.largeValue,
        readOnly: isFieldReadOnly?.(field) ?? false,
        cellMeta,
      };
    });

    setRowDetail({
      tableInstance,
      row,
      rowId: record.CHAT2DB_ROW_NUMBER,
      activeField: activeField || getResultFieldAtTableColumn(tableInstance, recordCol, row),
      items,
    });
  };

  useEffect(() => {
    activeItemRef.current?.scrollIntoView({ block: 'nearest' });
  }, [rowDetail?.row, rowDetail?.activeField]);

  useImperativeHandle(
    ref,
    () => ({
      openPanel,
    }),
    [headerMap, isFieldReadOnly],
  );

  const handleViewData = (item: IRowDetailItem) => {
    if (!rowDetail) {
      return;
    }
    onViewData?.({
      tableInstance: rowDetail.tableInstance,
      col: item.col,
      row: rowDetail.row,
      rowId: rowDetail.rowId,
      field: item.field,
      cellMeta: item.cellMeta,
    });
  };

  const handleFieldActivate = (item: IRowDetailItem) => {
    if (!rowDetail) {
      return;
    }
    setRowDetail((current) => (current ? { ...current, activeField: item.field } : current));
    onActiveFieldChange?.({
      tableInstance: rowDetail.tableInstance,
      col: item.col,
      row: rowDetail.row,
      rowId: rowDetail.rowId,
      field: item.field,
      cellMeta: item.cellMeta,
    });
  };

  const handleValueChange = (field: string, value: string) => {
    setRowDetail((current) => {
      if (!current) {
        return current;
      }
      return {
        ...current,
        items: current.items.map((item) => (item.field === field ? { ...item, value } : item)),
      };
    });
  };

  const handleValueBlur = (item: IRowDetailItem) => {
    if (!rowDetail || !resultData.canEdit || item.largeValue || item.readOnly || !onChangeData) {
      return;
    }
    onChangeData({
      tableInstance: rowDetail.tableInstance,
      col: item.col,
      row: rowDetail.row,
      rowId: rowDetail.rowId,
      field: item.field,
      value: item.value === null || item.value === undefined ? null : String(item.value),
      cellMeta: item.cellMeta,
    });
  };

  return (
    <div className={styles.container}>
      <div className={styles.fields}>
        {rowDetail?.items.map((item, index) => {
          const isNull = item.value === null || item.value === undefined;
          const isActive = item.field === rowDetail.activeField;
          return (
            <div
              ref={isActive ? activeItemRef : undefined}
              className={styles.item}
              data-active={isActive}
              key={`${item.field}-${index}`}
            >
              <div className={styles.field}>{item.label}</div>
              <div className={styles.valueRow}>
                <div className={styles.valueWrapper}>
                  <Input
                    value={item.value === null || item.value === undefined ? '' : String(item.value)}
                    placeholder={isNull ? '<null>' : undefined}
                    readOnly={!resultData.canEdit || item.largeValue || item.readOnly || !onChangeData}
                    className={cx(styles.valueInput, isActive && styles.activeValueInput, isNull && styles.nullValue)}
                    onFocus={() => handleFieldActivate(item)}
                    onChange={(event) => handleValueChange(item.field, event.target.value)}
                    onBlur={() => handleValueBlur(item)}
                  />
                </div>
                <div className={styles.action}>
                  {onViewData && (
                    <Tooltip title={i18n('common.button.viewData')}>
                      <Button
                        type="text"
                        aria-label={i18n('common.button.viewData')}
                        data-row-detail-action="true"
                        icon={<Iconfont code="&#xe788;" />}
                        className={styles.actionButton}
                        onClick={() => handleViewData(item)}
                      />
                    </Tooltip>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
});

export default memo(RowDetail);
