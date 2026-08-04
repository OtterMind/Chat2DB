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
import useHeaderTooltip from './hooks/useHeaderTooltip';
import { ITableOperationUtils } from './typings';
import { useGlobalStore } from '@/store/global';
import { getActiveTableInstance } from '@/blocks/CanvasTable/lifecycle';

interface IProps {
  className?: string;
  active: boolean;
  resultData: IManageResultData;
  // There are operational changes in the table
  onOperationChange?: (hasOperationRecord: any) => void;
  // table
  onTableOperationUtils: ITableOperationUtils;
  tableInstance: ITableInstance | null;
  setTableInstance: (tableInstance: ITableInstance | null) => void;
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
  const { active, resultData, onOperationChange, onTableOperationUtils, tableInstance, setTableInstance } = props;
  const { styles, theme } = useStyles();
  const activeTableInstance = getActiveTableInstance(active, tableInstance);

  // Registry data manipulation method
  const { operationRecordUtils, hasOperationRecord, reCalculateCellStyle } = useOperationRecord({
    // Keep edit tracking attached until CanvasTable completes the active editor during release.
    tableInstance,
    theme,
  });

  const { dataTableSettings } = useGlobalStore((s) => ({
    dataTableSettings: s.dataTableSettings,
  }));

  // Filter and sort
  const { activeFilterCount, clearAllFilters } = useFilterAndSort({
    theme,
    tableInstance: activeTableInstance,
    resultData,
    sortAfter: reCalculateCellStyle,
    filterAfter: reCalculateCellStyle,
    setOrderByText: props.setOrderByText,
  });
  const headerTooltip = useHeaderTooltip({ tableInstance: activeTableInstance });

  const [columns, records] = useMemo(() => {
    return dataTreating({ data: resultData, theme, dataTableSettings });
  }, [resultData, theme.appearance, dataTableSettings]);

  useEffect(() => {
    onOperationChange?.(hasOperationRecord);
  }, [hasOperationRecord]);

  useEffect(() => {
    props.onFilterCountChange?.(activeFilterCount);
  }, [activeFilterCount]);

  useEffect(() => {
    if (!activeTableInstance || !operationRecordUtils) return;
    // monitors the right mouse click on a cell
    const { id: onContextmenuCellId } = onContextmenuCell({
      resultData,
      tableInstance: activeTableInstance,
      operationRecordUtils,
      onTableOperationUtils,
    });
    return () => {
      activeTableInstance.off(onContextmenuCellId);
    };
  }, [activeTableInstance, operationRecordUtils]);

  useEffect(() => {
    if (!tableInstance || !operationRecordUtils) return;
    const onChangeCellValueId = onChangeCellValue(tableInstance, operationRecordUtils.handleCellValueChange);
    return () => {
      tableInstance.off(onChangeCellValueId);
    };
  }, [tableInstance, operationRecordUtils]);

  useEffect(() => {
    if (!activeTableInstance || !props.onSelectionChange) {
      return;
    }

    let frameId: number | null = null;
    let latestActiveCell: { col: number; row: number } | undefined;
    const emitSelection = () => {
      frameId = null;
      const cells = (activeTableInstance.getSelectedCellInfos() || [])
        .flat()
        .filter((cell) => cell.col > 0 && !activeTableInstance.isHeader(cell.col, cell.row));
      const fallbackCell = cells[cells.length - 1];
      const activeCell =
        latestActiveCell || (fallbackCell ? { col: fallbackCell.col, row: fallbackCell.row } : undefined);
      const activeRecord = activeCell
        ? activeTableInstance.getRecordByCell(activeCell.col, activeCell.row)
        : undefined;
      props.onSelectionChange?.({
        values: cells.map((cell) => (cell.dataValue !== undefined ? cell.dataValue : cell.value)),
        rowCount: new Set(cells.map((cell) => cell.row)).size,
        activeCell: activeCell
          ? {
              tableInstance: activeTableInstance,
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
        !activeTableInstance.isHeader(event.col, event.row)
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
      activeTableInstance.on('selected_cell', scheduleSelection),
      activeTableInstance.on('drag_select_end', scheduleSelection),
      activeTableInstance.on('selected_clear', clearSelection),
      activeTableInstance.on('change_cell_value', scheduleSelection),
    ];
    scheduleSelection();

    return () => {
      if (frameId !== null) {
        cancelAnimationFrame(frameId);
      }
      eventIds.forEach((eventId) => activeTableInstance.off(eventId));
    };
  }, [activeTableInstance, props.onSelectionChange]);

  // callback after initialization is completed
  const onInit = useCallback(
    (_tableInstance) => {
      setTableInstance(_tableInstance);
    },
    [setTableInstance],
  );

  const onRelease = useCallback(() => {
    setTableInstance(null);
    props.onSelectionChange?.({ values: [], rowCount: 0 });
  }, [props.onSelectionChange, setTableInstance]);

  useImperativeHandle(ref, () => {
    return {
      operationRecordUtils,
      tableInstance: activeTableInstance,
      activeFilterCount,
      clearAllFilters,
    };
  }, [operationRecordUtils, activeTableInstance, activeFilterCount, clearAllFilters]);

  const onCopy = useCallback(() => {
    if (!activeTableInstance) return;
    onCopyData(activeTableInstance);
  }, [activeTableInstance]);

  const onPaste = useCallback(() => {
    if (!activeTableInstance) return;
    onPasteData(activeTableInstance, operationRecordUtils);
  }, [activeTableInstance, operationRecordUtils]);

  return (
    <>
      <CanvasTable
        active={active}
        columns={columns}
        records={records}
        onInit={onInit}
        onRelease={onRelease}
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
        }}
      />
      {/* plug-in area */}
      <>{headerTooltip}</>
    </>
  );
});

export default memo(ResultSetTable);
