export interface ResultSelectionCellPosition {
  col: number;
  row: number;
}

export type ResultSelectionCause = 'table-selection' | 'value-change';

export function resolveResultSelectionActiveCell(
  cells: readonly ResultSelectionCellPosition[],
  latestActiveCell?: ResultSelectionCellPosition,
): ResultSelectionCellPosition | undefined {
  if (
    latestActiveCell &&
    cells.some((cell) => cell.col === latestActiveCell.col && cell.row === latestActiveCell.row)
  ) {
    return latestActiveCell;
  }
  const fallbackCell = cells[cells.length - 1];
  return fallbackCell ? { col: fallbackCell.col, row: fallbackCell.row } : undefined;
}

export function resolveResultInspectorActiveCell<T>(
  inspectorActiveCell: T | undefined,
  tableActiveCell: T | undefined,
  cause: ResultSelectionCause,
  preserveInspectorForTableSelection = false,
): T | undefined {
  return (cause === 'value-change' || preserveInspectorForTableSelection) && inspectorActiveCell
    ? inspectorActiveCell
    : tableActiveCell;
}
