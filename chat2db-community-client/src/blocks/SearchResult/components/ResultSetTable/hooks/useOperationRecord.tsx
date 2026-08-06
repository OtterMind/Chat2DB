import { useState, useRef, useCallback, useEffect, useMemo } from 'react';
import { ICellChangeRecord } from '../typings';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import { Theme as AntdTheme } from 'antd-style';
import {
  getRowOriginData,
  findRowNumberById,
  findColNumberById,
  findRowNumbersByIds,
} from '@/blocks/CanvasTable/utils';
import { getClonedCreateCellValue } from '@/blocks/SearchResult/utils';
import { v4 as uuidv4 } from 'uuid';
import type { IResultCell } from '@/typings/database';
import { buildUpdateOperation } from './operationRecord';

export interface OperationRecordUtils {
  handleCloneRow: () => void;
  handleAddBlankRow: (blankRow: { [key: string]: any }, rowId: string) => void;
  handleDeleteRow: () => void;
  handleRevocation: () => void;
  handleCellValueChange: (record: ICellChangeRecord) => void;
  clearOperationRecord: () => void;
  getOperationChangeDetail: () => any;
  isCreateRow: (rowId?: string | number | null) => boolean;
}

type IUseOperationRecord = (props: { tableInstance: ITableInstance | null; theme: Omit<AntdTheme, 'prefixCls'> }) => {
  operationRecordUtils: OperationRecordUtils;
  hasOperationRecord: boolean;
  // Rematch cell style
  reCalculateCellStyle: () => void;
};

const useOperationRecord: IUseOperationRecord = ({ tableInstance, theme }) => {
  const columns = useMemo(() => {
    return tableInstance?.columns || [];
  }, [tableInstance]);
  // cell
  const [cellChangeRecordList, setCellChangeRecordList] = useState<ICellChangeRecord[]>([]);
  // new record
  const [createRowRecordList, setCreateRowRecordList] = useState<string[]>([]);
  // row delete record
  const [deleteRowRecordList, setDeleteRowRecordList] = useState<string[]>([]);
  // Whether to monitor cell value changes
  const isListenCellValueChange = useRef(true);

  const cellChangeRecordLisLRef = useRef(cellChangeRecordList);
  const createRowRecordListRef = useRef(createRowRecordList);
  const deleteRowRecordListRef = useRef(deleteRowRecordList);

  const runWithCellValueTrackingPaused = useCallback((operation: () => void) => {
    const previousValue = isListenCellValueChange.current;
    isListenCellValueChange.current = false;
    try {
      operation();
    } finally {
      isListenCellValueChange.current = previousValue;
    }
  }, []);

  // callback for monitoring cell value changes
  const handleCellValueChange = useCallback(
    (record: ICellChangeRecord) => {
      if (!isListenCellValueChange.current || !tableInstance) return;
      // If the edited cell is a new row, it will not be recorded.
      const originData = getRowOriginData(tableInstance, record.rowId);
      const CHAT2DB_ROW_NUMBER = originData.CHAT2DB_ROW_NUMBER;
      if (createRowRecordListRef.current.includes(CHAT2DB_ROW_NUMBER)) {
        return;
      }
      // If the cell you are editing is a deleted row, you need to make this row non-deleted.
      if (deleteRowRecordListRef.current.includes(CHAT2DB_ROW_NUMBER)) {
        handleRevocationRowStyle(CHAT2DB_ROW_NUMBER);
        // Delete deleted row records
        deleteRowRecordListRef.current = deleteRowRecordListRef.current.filter((rowId) => {
          const flag = rowId === CHAT2DB_ROW_NUMBER;
          return !flag;
        });
        setDeleteRowRecordList(deleteRowRecordListRef.current);
      }

      // Normal cell value change record
      const _cellChangeRecordList = cellChangeRecordLisLRef.current;
      // Replace an existing record for this cell, or append a new one.
      const index = _cellChangeRecordList.findIndex(
        (_item) => _item.field === record.field && _item.rowId === record.rowId,
      );
      const newRecord = record;
      if (index > -1) {
        const previousRecord = _cellChangeRecordList.splice(index, 1)?.[0];
        const currentValue = previousRecord.currentValue;
        // Preserve the initial value rather than any intermediate edit.
        newRecord.currentValue = currentValue;
        newRecord.restoreValue =
          newRecord.restoreValue !== undefined ? newRecord.restoreValue : previousRecord.restoreValue;
        newRecord.restoreCellMeta = newRecord.restoreCellMeta || previousRecord.restoreCellMeta;
        // Remove the record and cell marker when the value returns to its original state.
        if (currentValue === record.changedValue) {
          handleRevocationCellStyle({
            field: newRecord.field,
            rowId: newRecord.rowId,
            value: newRecord.restoreValue,
            cellMeta: newRecord.restoreCellMeta,
          });
          // Delete this record
          cellChangeRecordLisLRef.current = [..._cellChangeRecordList];
          setCellChangeRecordList(cellChangeRecordLisLRef.current);
          return;
        }
      }

      cellChangeRecordLisLRef.current = [..._cellChangeRecordList, record];
      setCellChangeRecordList(cellChangeRecordLisLRef.current);
    },
    [tableInstance],
  );

  // handles new rows
  const handleCreateRowRecord = useCallback(
    (rows: string[]) => {
      const _createRowRecordList = createRowRecordListRef.current;
      createRowRecordListRef.current = [..._createRowRecordList, ...rows];
      setCreateRowRecordList(createRowRecordListRef.current);
    },
    [tableInstance],
  );

  const isCreateRow = useCallback((rowId?: string | number | null) => {
    if (rowId === null || rowId === undefined) {
      return false;
    }
    return createRowRecordListRef.current.includes(rowId.toString());
  }, []);

  // processes deleted rows. If it is found that the newly added row has been deleted, delete the new row record.
  const handleDeleteRowRecord = useCallback(
    (rows: string[]) => {
      const _createRowRecordList = createRowRecordListRef.current;
      const _deleteRowRecordList = deleteRowRecordListRef.current;

      rows.forEach((row) => {
        const index = _createRowRecordList.findIndex((item) => item === row);

        if (index > -1) {
          _createRowRecordList.splice(index, 1);
          // updates the line number of subsequent new lines
          // _createRowRecordList = _createRowRecordList.map((item) => (item > row ? item - 1 : item));
          createRowRecordListRef.current = [..._createRowRecordList];
          setCreateRowRecordList(createRowRecordListRef.current);
          return;
        }

        const deleteIndex = _deleteRowRecordList.findIndex((item) => item === row);

        // If there is already a deletion record, it will not be added again.
        if (deleteIndex > -1) {
          return;
        }
        deleteRowRecordListRef.current = [...deleteRowRecordListRef.current, row];
      });

      setDeleteRowRecordList(deleteRowRecordListRef.current);
    },
    [tableInstance],
  );

  // handles undo operations
  const handleRevocation = useCallback(() => {
    if (!tableInstance) return;
    const cells = tableInstance.getSelectedCellInfos() || [];
    if (cells.length === 0) return;
    runWithCellValueTrackingPaused(() => {
      const _createRowRecordList = [...createRowRecordListRef.current];
      const _deleteRowRecordList = [...deleteRowRecordListRef.current];
      const _cellChangeRecordList = [...cellChangeRecordLisLRef.current];
      // records the rows that need to be deleted
      const deleteRows: string[] = [];

      cells.forEach((cell) => {
        const rowId: string = cell?.[0]?.originData?.CHAT2DB_ROW_NUMBER;
        if (rowId === undefined) return;
        // Determines whether the current row is a new row or a deleted row, and performs row-level undo
        const createIndex = _createRowRecordList.findIndex((item) => item === rowId);
        const deleteIndex = _deleteRowRecordList.findIndex((item) => item === rowId);
        if (createIndex > -1 || deleteIndex > -1) {
          if (createIndex > -1) {
            _createRowRecordList.splice(createIndex, 1);
            // If the undoing of a new row requires deleting the new row
            deleteRows.push(rowId);
          } else {
            _deleteRowRecordList.splice(deleteIndex, 1);
          }
          handleRevocationRowStyle(rowId);
          return;
        }
        // Perform cell-level undo
        cell.forEach((item) => {
          _cellChangeRecordList.forEach((record, index) => {
            if (record.field === item.field && record.rowId === item.originData.CHAT2DB_ROW_NUMBER) {
              handleRevocationCellStyle({
                field: record.field,
                rowId: record.rowId,
                value: record.restoreValue !== undefined ? record.restoreValue : record.currentValue,
                cellMeta: record.restoreCellMeta,
              });

              _cellChangeRecordList.splice(index, 1);
            }
          });
        });
      });
      if (deleteRows.length > 0) {
        // What needs to be passed in here is not the line number, but the line number-1
        const rowNumbers = findRowNumbersByIds(tableInstance, deleteRows).map((row) => row - 1);
        tableInstance.deleteRecords(rowNumbers);
      }
      cellChangeRecordLisLRef.current = [..._cellChangeRecordList];
      createRowRecordListRef.current = [..._createRowRecordList];
      deleteRowRecordListRef.current = [..._deleteRowRecordList];
      setCellChangeRecordList(cellChangeRecordLisLRef.current);
      setCreateRowRecordList(createRowRecordListRef.current);
      setDeleteRowRecordList(deleteRowRecordListRef.current);
    });
  }, [tableInstance, runWithCellValueTrackingPaused]);

  // Delete row
  const handleDeleteRow = useCallback(() => {
    if (!tableInstance) return;

    const cells = tableInstance.getSelectedCellInfos() || [];
    if (cells.length === 0) return;
    // currently deleted line number set
    const curDeleteRows: any = [];
    cells.map((row) => {
      if (row?.[0]?.originData?.CHAT2DB_ROW_NUMBER === undefined) return;
      curDeleteRows.push(row[0].originData.CHAT2DB_ROW_NUMBER);
    });

    // New line number collection
    const createRows = createRowRecordListRef.current;

    // filters out new rows that need to be deleted
    const rowsToDelete = curDeleteRows.filter((row) => createRows.includes(row));

    const rowsToDeleteRows: number[] = [];
    rowsToDelete?.map((item) => {
      const target = cells?.find((_res) => _res?.[0]?.originData?.CHAT2DB_ROW_NUMBER === item) || [];
      if (target?.[0]?.row !== undefined) {
        rowsToDeleteRows.push(target[0].row - 1);
      }
    });

    if (rowsToDeleteRows.length > 0) {
      tableInstance.deleteRecords(rowsToDeleteRows);
    }

    const deletedRowIds = new Set(curDeleteRows);
    const revokedCellRecords = cellChangeRecordLisLRef.current.filter((record) => deletedRowIds.has(record.rowId));
    const remainingCellRecords = cellChangeRecordLisLRef.current.filter((record) => !deletedRowIds.has(record.rowId));
    handleDeleteRowRecord(curDeleteRows);
    if (revokedCellRecords.length > 0) {
      cellChangeRecordLisLRef.current = remainingCellRecords;
      setCellChangeRecordList(remainingCellRecords);
    }

    runWithCellValueTrackingPaused(() => {
      revokedCellRecords.forEach((_item) => {
        handleRevocationCellStyle({
          field: _item.field,
          rowId: _item.rowId,
          value: _item.restoreValue !== undefined ? _item.restoreValue : _item.currentValue,
          cellMeta: _item.restoreCellMeta,
        });
      });
    });
  }, [tableInstance, runWithCellValueTrackingPaused]);

  // clone line
  const handleCloneRow = useCallback(() => {
    if (!tableInstance) return;
    const cells = tableInstance.getSelectedCellInfos() || [];
    const maxLength = tableInstance?.records?.length || 0;
    // Currently newly added row data
    const curCreateRows: any = [];
    // New line number collection
    const curCreateRowsNumber: string[] = [];
    cells.map((row) => {
      const uuid = uuidv4();
      // This must be deconstructed, otherwise there will be problems with subsequent operations.
      const clonedRow = {
        ...(row[0].originData || {}),
        CHAT2DB_ROW_NUMBER: uuid,
      };
      columns.forEach((column) => {
        const field = column.field?.toString();
        const header = (column as any).originalData;
        if (!field || !header) {
          return;
        }
        clonedRow[field] = getClonedCreateCellValue(header, clonedRow[field]);
      });
      curCreateRows.push(clonedRow);
      // generates a unique uuid
      curCreateRowsNumber.push(uuid);
    });

    // Add data to table
    tableInstance.addRecords(curCreateRows);
    // records the newly added line number
    handleCreateRowRecord(curCreateRowsNumber);
    // Scroll to the new line
    tableInstance.scrollToCell({ row: maxLength });
  }, [columns, tableInstance]);

  // Add blank lines
  const handleAddBlankRow = useCallback(
    (blankRow, rowId) => {
      if (!tableInstance) return;
      const maxLength = tableInstance.records.length || 0;
      // Currently newly added row data
      const curCreateRows: any = [blankRow];
      // Add data to table
      tableInstance.addRecords(curCreateRows);
      // records the newly added line number
      handleCreateRowRecord([rowId]);
      // Scroll to the new line
      tableInstance.scrollToCell({ row: maxLength });
    },
    [tableInstance],
  );

  // Undo the style of the current cell
  const handleRevocationCellStyle = useCallback(
    ({
      field,
      rowId,
      value,
      cellMeta,
    }: {
      field: string;
      rowId: string;
      value?: string | null;
      cellMeta?: IResultCell;
    }) => {
      if (!tableInstance) return;
      const rowNumber = findRowNumberById(tableInstance, rowId);
      const colNumber = findColNumberById(tableInstance, field);
      const originData = getRowOriginData(tableInstance, rowId);
      if (colNumber >= 1) {
        tableInstance.arrangeCustomCellStyle(
          {
            col: colNumber,
            row: rowNumber,
          },
          '',
        );
      }
      if (value !== undefined) {
        if (colNumber >= 1) {
          runWithCellValueTrackingPaused(() => {
            tableInstance.changeCellValue(colNumber, rowNumber, value);
          });
        } else if (originData) {
          originData[field] = value;
          tableInstance.render();
        }
      }
      if (cellMeta) {
        const cellMetaList = originData?.__CHAT2DB_CELL_META__;
        if (cellMetaList) {
          cellMetaList[Number(field)] = { ...cellMeta };
        }
      }
    },
    [tableInstance, runWithCellValueTrackingPaused],
  );

  // Undo the style of the current row
  const handleRevocationRowStyle = useCallback(
    (rowId: string) => {
      if (!tableInstance) return;
      const rowNumber = findRowNumberById(tableInstance, rowId);
      tableInstance.arrangeCustomCellStyle(
        {
          range: {
            start: { row: rowNumber, col: 0 },
            end: { row: rowNumber, col: Math.max(0, tableInstance.colCount - 1) },
          },
        },
        '',
      );
    },
    [tableInstance],
  );

  // Clear all operation records
  const clearOperationRecord = useCallback(() => {
    setCellChangeRecordList([]);
    setCreateRowRecordList([]);
    setDeleteRowRecordList([]);
    cellChangeRecordLisLRef.current = [];
    createRowRecordListRef.current = [];
    deleteRowRecordListRef.current = [];
    // // Clear all styles
    const rowLength = tableInstance?.records?.length;
    const colLength = tableInstance ? Math.max(0, tableInstance.colCount - 1) : 0;
    if (!rowLength || !colLength) return;
    tableInstance?.arrangeCustomCellStyle(
      { range: { start: { row: 0, col: 0 }, end: { row: rowLength, col: colLength } } },
      '',
    );
  }, [tableInstance]);

  // Register custom cell style
  useEffect(() => {
    if (!tableInstance) return;
    const { colorSuccessBgHover, colorErrorBgHover, colorPrimaryBgHover } = theme;
    // const { colorSuccessBgHover } = theme;

    tableInstance.registerCustomCellStyle('custom-update-cell', {
      bgColor: colorPrimaryBgHover,
    });

    tableInstance.registerCustomCellStyle('custom-create-cell', {
      bgColor: colorSuccessBgHover,
    });

    tableInstance.registerCustomCellStyle('custom-delete-cell', {
      bgColor: colorErrorBgHover,
    });
  }, [tableInstance, theme.appearance]);

  useEffect(() => {
    if (!tableInstance) return;
    // TODO: There is a problem here, only as of VTable version 1.10.0
    // bug description: Change the color of a cell to A, then to B, then to A. At this time, the cell has no color.
    // Resetting this undocumented VTable arrangement fixes update colors that cycle A -> B -> A.
    // Registered create/delete *style definitions* remain intact after the reset, but the arrangement
    // (cell -> style) mappings are cleared. Re-apply create/delete arrangements below so a plain cell
    // edit does not wipe green (new-row) / red (deleted-row) highlights until the next sort/filter.
    if (tableInstance?.customCellStylePlugin) {
      tableInstance.customCellStylePlugin.customCellStyleArrangement = [];
    }

    cellChangeRecordList.map((record) => {
      const rowNumber = findRowNumberById(tableInstance, record.rowId);
      const colNumber = findColNumberById(tableInstance, record.field);
      if (!rowNumber || colNumber < 1) return;
      tableInstance?.arrangeCustomCellStyle(
        {
          col: colNumber,
          row: rowNumber,
        },
        'custom-update-cell',
      );
    });

    findRowNumbersByIds(tableInstance, createRowRecordList).forEach((row) => {
      tableInstance?.arrangeCustomCellStyle(
        { range: { start: { row, col: 0 }, end: { row, col: Math.max(0, tableInstance.colCount - 1) } } },
        'custom-create-cell',
      );
    });
    findRowNumbersByIds(tableInstance, deleteRowRecordList)?.forEach((row) => {
      tableInstance?.arrangeCustomCellStyle(
        { range: { start: { row, col: 0 }, end: { row, col: Math.max(0, tableInstance.colCount - 1) } } },
        'custom-delete-cell',
      );
    });
  }, [cellChangeRecordList, createRowRecordList, deleteRowRecordList, columns, tableInstance]);

  useEffect(() => {
    if (!tableInstance) return;
    const rowNumbers = findRowNumbersByIds(tableInstance, createRowRecordList);
    rowNumbers.map((row) => {
      tableInstance?.arrangeCustomCellStyle(
        // VTable errors when the range exceeds the actual columns; the dependency should clamp this internally.
        { range: { start: { row, col: 0 }, end: { row, col: Math.max(0, tableInstance.colCount - 1) } } },
        'custom-create-cell',
      );
    });
  }, [createRowRecordList, tableInstance, columns]);

  useEffect(() => {
    if (!tableInstance) return;
    const rowNumbers = findRowNumbersByIds(tableInstance, deleteRowRecordList);

    rowNumbers?.map((row) => {
      tableInstance?.arrangeCustomCellStyle(
        // VTable errors when the range exceeds the actual columns; the dependency should clamp this internally.
        { range: { start: { row, col: 0 }, end: { row, col: Math.max(0, tableInstance.colCount - 1) } } },
        'custom-delete-cell',
      );
    });
  }, [deleteRowRecordList, tableInstance, columns]);

  // Get all operation records
  const getOperationChangeDetail = useCallback(() => {
    if (!tableInstance) return;
    const operations: any = [];

    const currentCellChangeRecordList = cellChangeRecordLisLRef.current;
    const currentDeleteRowRecordList = deleteRowRecordListRef.current;
    const currentCreateRowRecordList = createRowRecordListRef.current;
    const updateRows = Array.from(new Set(currentCellChangeRecordList.map((record) => record.rowId)));
    // deleted line number
    const deleteRows = currentDeleteRowRecordList;
    // New line number
    const createRows = currentCreateRowRecordList;

    updateRows.forEach((rowId) => {
      const operation = buildUpdateOperation(
        rowId,
        getRowOriginData(tableInstance, rowId),
        currentCellChangeRecordList,
      );
      const containsPartialLargeValue = Object.values(operation.dataList || {}).some(
        (value: any) => value === 'CHAT2DB_LARGE_VALUE_PREVIEW:PARTIAL',
      );
      if (containsPartialLargeValue) {
        return;
      }
      operations.push(operation);
    });

    deleteRows.forEach((rowId) => {
      operations.push({
        rowId,
        type: 'DELETE',
        oldDataList: getRowOriginData(tableInstance, rowId),
      });
    });

    createRows.forEach((rowId) => {
      const dataList = getRowOriginData(tableInstance, rowId);
      if (Object.values(dataList || {}).some((value: any) => value === 'CHAT2DB_LARGE_VALUE_PREVIEW:PARTIAL')) {
        return;
      }
      operations.push({
        rowId,
        type: 'CREATE',
        dataList,
      });
    });

    // Clear selected status
    tableInstance.clearSelected();

    return operations;
  }, [tableInstance]);

  // Recalculate the styles of all cells
  // After all cell positions are changed, the position of the color block needs to be recalculated.
  const reCalculateCellStyle = useCallback(() => {
    if (!tableInstance) return;
    const _cellChangeRecordList = cellChangeRecordLisLRef.current || [];
    const _createRowRecordList = createRowRecordListRef.current || [];
    const _deleteRowRecordList = deleteRowRecordListRef.current || [];
    if (!_cellChangeRecordList.length && !_createRowRecordList.length && !_deleteRowRecordList.length) return;
    const rowNumber = tableInstance.records.length;
    const colNumber = Math.max(0, tableInstance.colCount - 1);
    tableInstance.arrangeCustomCellStyle(
      {
        range: {
          start: { row: 0, col: 0 },
          end: { row: rowNumber, col: colNumber },
        },
      },
      '',
    );
    setCellChangeRecordList([..._cellChangeRecordList]);
    setCreateRowRecordList([..._createRowRecordList]);
    setDeleteRowRecordList([..._deleteRowRecordList]);
  }, [tableInstance, handleRevocationRowStyle]);

  // monitors meter head movement
  useEffect(() => {
    if (!tableInstance) return;
    const change_header_position_id = tableInstance.on('change_header_position', () => {
      reCalculateCellStyle();
    });
    // const after_render_id = tableInstance.on('after_sort', () => {
    //   reCalculateCellStyle();
    // });
    return () => {
      tableInstance.off(change_header_position_id);
      // tableInstance.off(after_render_id);
    };
  }, [tableInstance]);

  // How to operate table data
  const operationRecordUtils = useMemo(() => {
    return {
      handleCloneRow,
      handleAddBlankRow,
      handleDeleteRow,
      handleRevocation,
      handleCellValueChange,
      clearOperationRecord,
      getOperationChangeDetail,
      isCreateRow,
    };
  }, [
    handleCloneRow,
    handleAddBlankRow,
    handleDeleteRow,
    handleRevocation,
    handleCellValueChange,
    clearOperationRecord,
    getOperationChangeDetail,
    isCreateRow,
  ]);

  // Is there any current operation record?
  const hasOperationRecord = useMemo(() => {
    return cellChangeRecordList.length > 0 || createRowRecordList.length > 0 || deleteRowRecordList.length > 0;
  }, [cellChangeRecordList, createRowRecordList, deleteRowRecordList]);

  return { operationRecordUtils, hasOperationRecord, reCalculateCellStyle };
};

export default useOperationRecord;
