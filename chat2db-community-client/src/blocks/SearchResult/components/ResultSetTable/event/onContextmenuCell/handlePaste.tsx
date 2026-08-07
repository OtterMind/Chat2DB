import * as VTable from '@visactor/vtable';
import { readClipboard } from '@/utils/clipboard';
import { normalizeCreateRowPasteValues, normalizePasteTargetRange } from '@/blocks/SearchResult/utils';
import type { OperationRecordUtils } from '../../hooks/useOperationRecord';

type PasteOperationRecordUtils = Pick<OperationRecordUtils, 'isCreateRow'>;

function getSelectedPasteRange(tableInstance: VTable.ListTable, rangeIndex: number) {
  const range = tableInstance.stateManager.select.ranges[rangeIndex];
  return normalizePasteTargetRange({
    startCol: range.start.col,
    startRow: range.start.row,
    endCol: range.end.col,
    endRow: range.end.row,
  });
}

function normalizePasteValues(
  tableInstance: VTable.ListTable,
  operationRecordUtils: PasteOperationRecordUtils | undefined,
  col: number,
  row: number,
  values: (string | number)[][],
) {
  return normalizeCreateRowPasteValues({
    values,
    startCol: col,
    startRow: row,
    columns: tableInstance.columns.filter((column: any) => column.hide !== true),
    getRowId: (targetRow, targetCol) => tableInstance.getRecordByCell(targetCol, targetRow)?.CHAT2DB_ROW_NUMBER,
    isCreateRow: operationRecordUtils?.isCreateRow,
  });
}

// Existing null cells are pasted as empty strings by the current clipboard format.
const handlePaste = async (
  tableInstance: VTable.ListTable,
  operationRecordUtils?: PasteOperationRecordUtils,
  readOnlyFields: ReadonlySet<string> = new Set(),
) => {
  if (tableInstance.editorManager?.editingEditor || !tableInstance.stateManager.select.ranges?.length) {
    return;
  }

  const { col, row } = getSelectedPasteRange(tableInstance, 0);
  const pastedData = await readClipboard();
  const values = pastedData.split('\n').map((rowCells) => {
    const cells = rowCells.split('\t');
    return cells.map((cell, cellIndex) => (cellIndex === cells.length - 1 ? cell.trim() : cell));
  });
  const normalizedValues = normalizePasteValues(tableInstance, operationRecordUtils, col, row, values);
  normalizedValues.forEach((rowValues, rowOffset) => {
    rowValues.forEach((value, colOffset) => {
      const targetCol = col + colOffset;
      const targetRow = row + rowOffset;
      const field = tableInstance.getHeaderField(targetCol, targetRow);
      if (
        targetCol > tableInstance.colCount ||
        targetRow > tableInstance.rowCount ||
        readOnlyFields.has(String(field))
      ) {
        return;
      }
      tableInstance.changeCellValue(targetCol, targetRow, value);
    });
  });
};

export default handlePaste;
