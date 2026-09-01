import type { ITableInstance } from '@/blocks/CanvasTable/typings';

export const RESULT_TABLE_CELL_PREVIEW_CHARACTERS = 200;
const expandedRowsByTable = new WeakMap<object, Set<number>>();

function getExpandedRows(table: object) {
  let expandedRows = expandedRowsByTable.get(table);
  if (!expandedRows) {
    expandedRows = new Set();
    expandedRowsByTable.set(table, expandedRows);
  }
  return expandedRows;
}

export function isResultTableRowExpanded(
  table: Pick<ITableInstance, 'getDefaultRowHeight' | 'getRowHeight'>,
  row: number,
) {
  const expandedRows = expandedRowsByTable.get(table);
  if (!expandedRows?.has(row)) {
    return false;
  }
  if (table.getRowHeight(row) <= table.getDefaultRowHeight(row)) {
    expandedRows.delete(row);
    return false;
  }
  return true;
}

export function getCollapsedResultCellPreview(
  value: unknown,
  expanded: boolean,
  maxCharacters = RESULT_TABLE_CELL_PREVIEW_CHARACTERS,
) {
  if (expanded || typeof value !== 'string' || !Number.isFinite(maxCharacters) || maxCharacters <= 0) {
    return undefined;
  }

  if (value.length <= maxCharacters && !/\r|\n/.test(value)) {
    return undefined;
  }

  const preview = value.slice(0, maxCharacters).replace(/\r\n?|\n/g, ' ');
  return `${preview}...`;
}

export function updateResultTableRowExpansion(table: ITableInstance, row: number, rowHeight: number) {
  const firstBodyRow = table.columnHeaderLevelCount;
  const lastBodyRow = table.rowCount - table.bottomFrozenRowCount;
  if (row < firstBodyRow || row >= lastBodyRow) {
    return 0;
  }

  const expandedRows = getExpandedRows(table);
  if (rowHeight > table.getDefaultRowHeight(row)) {
    expandedRows.add(row);
  } else {
    expandedRows.delete(row);
  }

  for (let col = 1; col < table.colCount; col += 1) {
    table.scenegraph.updateCellContent(col, row);
  }
  table.scenegraph.updateNextFrame();
  return Math.max(0, table.colCount - 1);
}

export function resetResultTableLayout(table: ITableInstance) {
  expandedRowsByTable.delete(table);
  table.internalProps._heightResizedRowMap.clear();
  table.clearSelected();
  table.clearRowHeightCache();
}
