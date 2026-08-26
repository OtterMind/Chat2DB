import type { ITableInstance } from '@/blocks/CanvasTable/typings';
import { RESULT_TABLE_MAX_AUTO_ROW_HEIGHT } from './layoutOptions';

/** Keep automatic rows readable without preventing manual row resizing. */
export function capResultTableAutoRowHeights(
  table: ITableInstance,
  maxHeight = RESULT_TABLE_MAX_AUTO_ROW_HEIGHT,
) {
  if (table.heightMode !== 'autoHeight' || !Number.isFinite(maxHeight) || maxHeight <= 0) {
    return 0;
  }

  const resizedRows = table.internalProps._heightResizedRowMap;
  const firstBodyRow = table.columnHeaderLevelCount;
  const lastBodyRow = table.rowCount - table.bottomFrozenRowCount;
  let cappedRows = 0;

  for (let row = firstBodyRow; row < lastBodyRow; row += 1) {
    if (resizedRows.has(row) || table.getRowHeight(row) <= maxHeight) {
      continue;
    }
    table.scenegraph.setRowHeight(row, maxHeight);
    cappedRows += 1;
  }

  return cappedRows;
}
