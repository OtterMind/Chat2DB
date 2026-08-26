import {
  memo,
  useEffect,
  useMemo,
  forwardRef,
  useImperativeHandle,
  ForwardedRef,
  useCallback,
  useRef,
  useState,
} from 'react';
import { useStyles } from './style';
import CanvasTable from '@/blocks/CanvasTable';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import { IManageResultData } from '@/typings/database';
import onContextmenuCell from './event/onContextmenuCell';
import onChangeCellValue from './event/onChangeCellValue';
import onCopyData from './event/onCopyData';
import onPasteData from './event/onPasteData';
import { buildResultColumns, buildResultRecords } from './utils/dataTreating';
import useOperationRecord, { OperationRecordUtils } from './hooks/useOperationRecord';
import useFilterAndSort from './hooks/useFilterAndSort';
import useHeaderTooltip from './hooks/useHeaderTooltip';
import { ITableOperationUtils } from './typings';
import { useGlobalStore } from '@/store/global';
import ColumnVisibilityModal, { ResultColumnVisibilityOption } from './ColumnVisibilityModal';
import {
  getResultFrozenColumnCount,
  getResultColumnFields,
  getResultColumnDisplayOrder,
  getNextFrozenResultColumnFields,
  hideResultColumnFields,
  mergeResultColumnOrderFromDisplay,
  orderResultColumns,
  reconcileHiddenResultColumnFields,
  getResultFieldAtTableColumn,
} from './columnState';
import { resolveResultSelectionActiveCell, ResultSelectionCause } from './selectionState';
import { RESULT_TABLE_CONTENT_LAYOUT_OPTIONS } from './layoutOptions';
import { capResultTableAutoRowHeights } from './rowHeight';

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
  cause: ResultSelectionCause;
  interactionRevision: number;
  activeCell?: {
    tableInstance: ITableInstance;
    col: number;
    row: number;
    rowId?: string | number;
    field?: string;
  };
}

export interface ResultSetTableRef {
  operationRecordUtils: OperationRecordUtils;
  tableInstance: ITableInstance | null;
  activeFilterCount: number;
  clearAllFilters: () => void;
  isFieldFrozen: (field: string | number) => boolean;
  openColumnVisibility: () => void;
  getInteractionRevision: () => number;
}

const ResultSetTable = forwardRef((props: IProps, ref: ForwardedRef<ResultSetTableRef>) => {
  const { resultData, onOperationChange, onTableOperationUtils, tableInstance, setTableInstance } = props;
  const { styles, theme } = useStyles();
  const [hiddenColumnFields, setHiddenColumnFields] = useState<Set<string>>(() => new Set());
  const [frozenColumnFields, setFrozenColumnFields] = useState<string[]>([]);
  const [columnOrder, setColumnOrder] = useState<string[]>([]);
  const [columnVisibilityOpen, setColumnVisibilityOpen] = useState(false);
  const interactionRevisionRef = useRef(0);
  const { customFontSize, showFieldType, showFieldComment } = useGlobalStore((state) => ({
    customFontSize: state.baseSetting.customFontSize ?? 13,
    showFieldType: state.dataTableSettings.showFieldType ?? true,
    showFieldComment: state.dataTableSettings.showFieldComment ?? true,
  }));
  const columnVisibilityOptions = useMemo<ResultColumnVisibilityOption[]>(
    () =>
      (resultData.headerList || []).slice(1).map((header, index) => ({
        field: String(index + 1),
        header,
      })),
    [resultData.headerList],
  );
  const resultColumnFields = useMemo(
    () => columnVisibilityOptions.map((column) => column.field),
    [columnVisibilityOptions],
  );

  useEffect(() => {
    setHiddenColumnFields((current) => {
      const next = reconcileHiddenResultColumnFields(resultColumnFields, current);
      return next.size === current.size && [...next].every((field) => current.has(field)) ? current : next;
    });
    setColumnOrder((current) => {
      const next = [...current.filter((field) => resultColumnFields.includes(field))];
      resultColumnFields.forEach((field) => {
        if (!next.includes(field)) {
          next.push(field);
        }
      });
      return next.length === current.length && next.every((field, index) => field === current[index])
        ? current
        : next;
    });
    setFrozenColumnFields((current) => current.filter((field) => resultColumnFields.includes(field)));
  }, [resultColumnFields]);

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
  const columns = useMemo(() => {
    const nextColumns = buildResultColumns({
      data: resultData,
      theme,
      visibility: { showFieldType, showFieldComment },
      hiddenFields: hiddenColumnFields,
      readOnlyFields: new Set(frozenColumnFields),
    });
    const displayOrder = getResultColumnDisplayOrder(columnOrder, frozenColumnFields);
    return orderResultColumns(nextColumns, displayOrder);
  }, [
    columnOrder,
    frozenColumnFields,
    resultData,
    theme.appearance,
    customFontSize,
    showFieldType,
    showFieldComment,
    hiddenColumnFields,
  ]);
  const records = useMemo(() => buildResultRecords(resultData), [resultData]);
  const headerTooltip = useHeaderTooltip({ tableInstance });

  useEffect(() => {
    if (!tableInstance) {
      return;
    }
    const capAutoRowHeights = () => capResultTableAutoRowHeights(tableInstance);
    const eventId = tableInstance.on('after_render', capAutoRowHeights);
    capAutoRowHeights();
    return () => tableInstance.off(eventId);
  }, [tableInstance]);

  const clearColumnSensitiveSelection = useCallback(() => {
    interactionRevisionRef.current += 1;
    tableInstance?.clearSelected();
    props.onSelectionChange?.({
      values: [],
      rowCount: 0,
      cause: 'table-selection',
      interactionRevision: interactionRevisionRef.current,
    });
  }, [props.onSelectionChange, tableInstance]);

  const handleColumnVisibilityConfirm = useCallback(
    (nextHiddenFields: Set<string>) => {
      const reconciledHiddenFields = reconcileHiddenResultColumnFields(resultColumnFields, nextHiddenFields);
      setHiddenColumnFields(reconciledHiddenFields);
      setFrozenColumnFields((current) => current.filter((field) => !reconciledHiddenFields.has(field)));
      setColumnVisibilityOpen(false);
      clearColumnSensitiveSelection();
    },
    [clearColumnSensitiveSelection, resultColumnFields],
  );

  const handleHideColumns = useCallback(
    (fields: string[]) => {
      const nextHiddenFields = hideResultColumnFields(resultColumnFields, hiddenColumnFields, fields);
      setHiddenColumnFields(nextHiddenFields);
      setFrozenColumnFields((current) => current.filter((field) => !nextHiddenFields.has(field)));
      clearColumnSensitiveSelection();
    },
    [clearColumnSensitiveSelection, hiddenColumnFields, resultColumnFields],
  );

  const handleShowAllColumns = useCallback(() => {
    setHiddenColumnFields(new Set());
    clearColumnSensitiveSelection();
  }, [clearColumnSensitiveSelection]);

  const handleFreezeColumns = useCallback(
    (fields: string[]) => {
      setFrozenColumnFields((current) =>
        getNextFrozenResultColumnFields(tableInstance?.columns || [], current, fields),
      );
      clearColumnSensitiveSelection();
    },
    [clearColumnSensitiveSelection, tableInstance],
  );

  const handleUnfreezeAllColumns = useCallback(() => {
    setFrozenColumnFields([]);
    clearColumnSensitiveSelection();
  }, [clearColumnSensitiveSelection]);

  const applyFrozenColumnCount = useCallback(() => {
    if (!tableInstance) {
      return;
    }
    tableInstance.setFrozenColCount(
      getResultFrozenColumnCount(tableInstance.columns || [], frozenColumnFields),
    );
  }, [frozenColumnFields, tableInstance]);

  useEffect(() => {
    applyFrozenColumnCount();
    reCalculateCellStyle();
  }, [applyFrozenColumnCount, columns, reCalculateCellStyle]);

  useEffect(() => {
    if (!tableInstance) {
      return;
    }
    const eventId = tableInstance.on('change_header_position', () => {
      const displayOrder = getResultColumnFields(tableInstance.columns || []);
      setColumnOrder((current) => {
        const nextOrder = mergeResultColumnOrderFromDisplay(current, displayOrder, frozenColumnFields);
        return nextOrder.length === current.length && nextOrder.every((field, index) => field === current[index])
          ? current
          : nextOrder;
      });
      applyFrozenColumnCount();
    });
    return () => tableInstance.off(eventId);
  }, [applyFrozenColumnCount, frozenColumnFields, tableInstance]);

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
      frozenColumnFields,
      onHideColumns: handleHideColumns,
      onShowAllColumns: handleShowAllColumns,
      onFreezeColumns: handleFreezeColumns,
      onUnfreezeAllColumns: handleUnfreezeAllColumns,
    });
    // monitors cell value changes
    const onChangeCellValueId = onChangeCellValue(
      tableInstance,
      operationRecordUtils.handleCellValueChange,
      new Set(frozenColumnFields),
    );
    // monitors copied data
    return () => {
      tableInstance?.off(onContextmenuCellId);
      tableInstance?.off(onChangeCellValueId);
    };
  }, [
    frozenColumnFields,
    handleHideColumns,
    handleShowAllColumns,
    handleFreezeColumns,
    handleUnfreezeAllColumns,
    onTableOperationUtils,
    operationRecordUtils,
    resultData,
    tableInstance,
  ]);

  useEffect(() => {
    if (!tableInstance || !props.onSelectionChange) {
      return;
    }

    let frameId: number | null = null;
    let latestActiveCell: { col: number; row: number } | undefined;
    let pendingCause: ResultSelectionCause = 'table-selection';
    const emitSelection = () => {
      frameId = null;
      const cells = (tableInstance.getSelectedCellInfos() || [])
        .flat()
        .filter((cell) => cell.col > 0 && !tableInstance.isHeader(cell.col, cell.row));
      const activeCell = resolveResultSelectionActiveCell(cells, latestActiveCell);
      latestActiveCell = activeCell;
      const activeRecord = activeCell ? tableInstance.getRecordByCell(activeCell.col, activeCell.row) : undefined;
      props.onSelectionChange?.({
        values: cells.map((cell) => (cell.dataValue !== undefined ? cell.dataValue : cell.value)),
        rowCount: new Set(cells.map((cell) => cell.row)).size,
        cause: pendingCause,
        interactionRevision: interactionRevisionRef.current,
        activeCell: activeCell
          ? {
              tableInstance,
              col: activeCell.col,
              row: activeCell.row,
              rowId: activeRecord?.CHAT2DB_ROW_NUMBER,
              field: getResultFieldAtTableColumn(tableInstance, activeCell.col, activeCell.row),
            }
          : undefined,
      });
    };
    const scheduleSelection = (
      cause: ResultSelectionCause,
      event?: { col?: number; row?: number },
    ) => {
      if (cause === 'table-selection' || frameId === null) {
        pendingCause = cause;
      }
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
      scheduleSelection('table-selection');
    };

    const eventIds = [
      tableInstance.on('selected_cell', (event) => scheduleSelection('table-selection', event)),
      tableInstance.on('drag_select_end', (event) => scheduleSelection('table-selection', event)),
      tableInstance.on('selected_clear', clearSelection),
      tableInstance.on('change_cell_value', (event) => scheduleSelection('value-change', event)),
    ];
    scheduleSelection('table-selection');

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
      isFieldFrozen: (field) => frozenColumnFields.includes(String(field)),
      openColumnVisibility: () => setColumnVisibilityOpen(true),
      getInteractionRevision: () => interactionRevisionRef.current,
    };
  }, [operationRecordUtils, tableInstance, activeFilterCount, clearAllFilters, frozenColumnFields]);

  const onCopy = useCallback(() => {
    if (!tableInstance) return;
    onCopyData(tableInstance);
  }, [tableInstance]);

  const onPaste = useCallback(() => {
    if (!tableInstance) return;
    onPasteData(tableInstance, operationRecordUtils, new Set(frozenColumnFields));
  }, [frozenColumnFields, tableInstance, operationRecordUtils]);

  const handleTablePointerDown = useCallback(() => {
    interactionRevisionRef.current += 1;
  }, []);

  const handleTableKeyDown = useCallback(() => {
    interactionRevisionRef.current += 1;
  }, []);

  return (
    <>
      <CanvasTable
        columns={columns}
        records={records}
        onInit={onInit}
        className={styles.canvasTable}
        onCopy={onCopy}
        onPaste={onPaste}
        onKeyDown={handleTableKeyDown}
        onPointerDown={handleTablePointerDown}
        customOptions={{ showFrozenColumnDivider: frozenColumnFields.length > 0 }}
        options={{
          ...RESULT_TABLE_CONTENT_LAYOUT_OPTIONS,
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
          unfreezeAllOnExceedsMaxWidth: false,
          frozenColDragHeaderMode: 'disabled',
          defaultHeaderRowHeight: 'auto',
        }}
      />
      {headerTooltip}
      <ColumnVisibilityModal
        open={columnVisibilityOpen}
        columns={columnVisibilityOptions}
        hiddenFields={hiddenColumnFields}
        onCancel={() => setColumnVisibilityOpen(false)}
        onConfirm={handleColumnVisibilityConfirm}
      />
    </>
  );
});

export default memo(ResultSetTable);
