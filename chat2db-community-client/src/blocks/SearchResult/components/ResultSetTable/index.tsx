import { memo, useEffect, useMemo, forwardRef, useImperativeHandle, ForwardedRef, useCallback } from 'react';
import { useStyles } from './style';
import CanvasTable from '@/blocks/CanvasTable';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import { IManageResultData } from '@/typings/database';
import onContextmenuCell from './event/onContextmenuCell';
import onChangeCellValue from './event/onChangeCellValue';
import onCopyData from './event/onCopyData';
import onPasteData from './event/onPasteData';
import dataTreating from './utils/dataTreating';
import useOperationRecord, { OperationRecordUtils } from './hooks/useOperationRecord';
import useFilterAndSort from './hooks/useFilterAndSort';
import { ITableOperationUtils } from './typings';
import { useGlobalStore } from '@/store/global';

interface IProps {
  className?: string;
  resultData: IManageResultData;
  // There are operational changes in the table
  onOperationChange?: (hasOperationRecord: any) => void;
  // table
  onTableOperationUtils: ITableOperationUtils;
  tableInstance: ITableInstance | null;
  setTableInstance: (tableInstance: ITableInstance) => void;
  setOrderByText?: (orderByText: string) => void;
  onFilterCountChange?: (count: number) => void;
  onSelectionChange?: (selection: IResultSetSelection) => void;
}

export interface IResultSetSelection {
  values: unknown[];
  rowCount: number;
  activeCell?: {
    tableInstance: ITableInstance;
    col: number;
    row: number;
    rowId?: string | number;
  };
}

export interface ResultSetTableRef {
  operationRecordUtils: OperationRecordUtils;
  tableInstance: ITableInstance | null;
  activeFilterCount: number;
  clearAllFilters: () => void;
}

const ResultSetTable = forwardRef((props: IProps, ref: ForwardedRef<ResultSetTableRef>) => {
  const { resultData, onOperationChange, onTableOperationUtils, tableInstance, setTableInstance } = props;
  const { styles, theme } = useStyles();
  const { customFontSize, showFieldType, showFieldComment } = useGlobalStore((state) => ({
    customFontSize: state.baseSetting.customFontSize ?? 13,
    showFieldType: state.dataTableSettings.showFieldType ?? true,
    showFieldComment: state.dataTableSettings.showFieldComment ?? true,
  }));

  // Registry data manipulation method
  const { operationRecordUtils, hasOperationRecord, reCalculateCellStyle } = useOperationRecord({
    tableInstance,
    theme,
  });

  // Filter and sort
  const { activeFilterCount, clearAllFilters } = useFilterAndSort({
    theme,
    tableInstance,
    resultData,
    sortAfter: reCalculateCellStyle,
    filterAfter: reCalculateCellStyle,
    setOrderByText: props.setOrderByText,
  });
  const [columns, records] = useMemo(() => {
    return dataTreating({
      data: resultData,
      theme,
      visibility: { showFieldType, showFieldComment },
    });
  }, [resultData, theme.appearance, customFontSize, showFieldType, showFieldComment]);

  useEffect(() => {
    onOperationChange?.(hasOperationRecord);
  }, [hasOperationRecord]);

  useEffect(() => {
    props.onFilterCountChange?.(activeFilterCount);
  }, [activeFilterCount]);

  useEffect(() => {
    if (!tableInstance || !operationRecordUtils) return;
    // monitors the right mouse click on a cell
    const { id: onContextmenuCellId } = onContextmenuCell({
      resultData,
      tableInstance,
      operationRecordUtils,
      onTableOperationUtils,
    });
    // monitors cell value changes
    const onChangeCellValueId = onChangeCellValue(tableInstance, operationRecordUtils.handleCellValueChange);
    // monitors copied data
    return () => {
      tableInstance?.off(onContextmenuCellId);
      tableInstance?.off(onChangeCellValueId);
    };
  }, [tableInstance, operationRecordUtils]);

  useEffect(() => {
    if (!tableInstance || !props.onSelectionChange) {
      return;
    }

    let frameId: number | null = null;
    let latestActiveCell: { col: number; row: number } | undefined;
    const emitSelection = () => {
      frameId = null;
      const cells = (tableInstance.getSelectedCellInfos() || [])
        .flat()
        .filter((cell) => cell.col > 0 && !tableInstance.isHeader(cell.col, cell.row));
      const fallbackCell = cells[cells.length - 1];
      const activeCell =
        latestActiveCell || (fallbackCell ? { col: fallbackCell.col, row: fallbackCell.row } : undefined);
      const activeRecord = activeCell ? tableInstance.getRecordByCell(activeCell.col, activeCell.row) : undefined;
      props.onSelectionChange?.({
        values: cells.map((cell) => (cell.dataValue !== undefined ? cell.dataValue : cell.value)),
        rowCount: new Set(cells.map((cell) => cell.row)).size,
        activeCell: activeCell
          ? {
              tableInstance,
              col: activeCell.col,
              row: activeCell.row,
              rowId: activeRecord?.CHAT2DB_ROW_NUMBER,
            }
          : undefined,
      });
    };
    const scheduleSelection = (event?: { col?: number; row?: number }) => {
      if (
        event?.col !== undefined &&
        event?.row !== undefined &&
        event.col > 0 &&
        !tableInstance.isHeader(event.col, event.row)
      ) {
        latestActiveCell = { col: event.col, row: event.row };
      }
      if (frameId !== null) {
        cancelAnimationFrame(frameId);
      }
      frameId = requestAnimationFrame(emitSelection);
    };
    const clearSelection = () => {
      latestActiveCell = undefined;
      scheduleSelection();
    };

    const eventIds = [
      tableInstance.on('selected_cell', scheduleSelection),
      tableInstance.on('drag_select_end', scheduleSelection),
      tableInstance.on('selected_clear', clearSelection),
      tableInstance.on('change_cell_value', scheduleSelection),
    ];
    scheduleSelection();

    return () => {
      if (frameId !== null) {
        cancelAnimationFrame(frameId);
      }
      eventIds.forEach((eventId) => tableInstance.off(eventId));
    };
  }, [tableInstance, props.onSelectionChange]);

  // callback after initialization is completed
  const onInit = useCallback((_tableInstance) => {
    setTableInstance(_tableInstance);
  }, []);

  useImperativeHandle(ref, () => {
    return {
      operationRecordUtils,
      tableInstance,
      activeFilterCount,
      clearAllFilters,
    };
  }, [operationRecordUtils, tableInstance, activeFilterCount, clearAllFilters]);

  const onCopy = useCallback(() => {
    if (!tableInstance) return;
    onCopyData(tableInstance);
  }, [tableInstance]);

  const onPaste = useCallback(() => {
    if (!tableInstance) return;
    onPasteData(tableInstance, operationRecordUtils);
  }, [tableInstance, operationRecordUtils]);

  return (
    <>
      <CanvasTable
        columns={columns}
        records={records}
        onInit={onInit}
        className={styles.canvasTable}
        onCopy={onCopy}
        onPaste={onPaste}
        options={{
          rowSeriesNumber: {
            title: undefined,
            width: 'auto' as any,
            disableColumnResize: true,
          },
          keyboardOptions: {
            copySelected: false, // Start copying
            pasteValueToCell: false, // Turn on paste
            selectAllOnCtrlA: true, // Turn on all selections
          },
          frozenColCount: 1, // Number of frozen columns
          defaultHeaderRowHeight: 'auto',
        }}
      />
    </>
  );
});

export default memo(ResultSetTable);
