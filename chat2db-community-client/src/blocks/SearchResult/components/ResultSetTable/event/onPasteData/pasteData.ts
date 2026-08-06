import {
  getClonedCreateCellValue,
  normalizePasteTargetCell,
  type CreateRowPasteColumn,
} from '@/blocks/SearchResult/utils';

type PasteCellValue = string | null;

export interface PasteCellInfo {
  row: number;
  col: number;
}

export type PasteSelection = PasteCellInfo[][];

export interface PasteTable {
  rowCount: number;
  colCount: number;
  changeCellValue: (col: number, row: number, value: PasteCellValue) => void;
  columns?: CreateRowPasteColumn[];
  getHeaderField?: (col: number, row: number) => string | number | undefined;
  getRecordByCell?: (col: number, row: number) => { CHAT2DB_ROW_NUMBER?: string | number | null } | undefined;
}

export interface PasteOptions {
  isCreateRow?: (rowId?: string | number | null) => boolean;
  internalClipboardGrid?: string[][];
  readOnlyFields?: ReadonlySet<string>;
}

export type ParsedPasteData =
  | {
      type: 'cell';
      value: PasteCellValue;
    }
  | {
      type: 'grid';
      rows: PasteCellValue[][];
    };

export function normalizePastedCellValue(cell: string): PasteCellValue {
  if (cell.trim() === '') {
    return null;
  }
  if (cell === "''") {
    return '';
  }
  return cell;
}

export function getPasteSelectionSize(selection: PasteSelection) {
  return selection.reduce((count, row) => count + row.length, 0);
}

export function parsePasteData(
  text: string,
  selectionSize: number,
  internalClipboardGrid?: string[][],
): ParsedPasteData {
  const internalClipboardSize =
    internalClipboardGrid?.reduce((count, row) => count + row.length, 0) ?? 0;
  if (internalClipboardGrid && internalClipboardSize > 1) {
    return {
      type: 'grid',
      rows: internalClipboardGrid.map((row) => row.map(normalizePastedCellValue)),
    };
  }

  if (selectionSize <= 1) {
    return {
      type: 'cell',
      value: normalizePastedCellValue(text),
    };
  }

  return {
    type: 'grid',
    rows: text.split(/\r\n|\n|\r/).map((row) => row.split('\t').map(normalizePastedCellValue)),
  };
}

function isInsideTable(table: PasteTable, row: number, col: number) {
  return row <= table.rowCount && col <= table.colCount;
}

function isReadOnlyPasteTarget(table: PasteTable, options: PasteOptions | undefined, row: number, col: number) {
  const field = table.getHeaderField?.(col, row);
  return field !== undefined && options?.readOnlyFields?.has(String(field));
}

function normalizeCreateRowPasteCell(
  table: PasteTable,
  options: PasteOptions | undefined,
  row: number,
  col: number,
  value: PasteCellValue,
) {
  const rowId = table.getRecordByCell?.(col, row)?.CHAT2DB_ROW_NUMBER;
  if (!options?.isCreateRow?.(rowId)) {
    return value;
  }

  const visibleColumns = table.columns?.filter((column: any) => column.hide !== true);
  const header = visibleColumns?.[col - 1]?.originalData;
  if (!header) {
    return value;
  }
  return getClonedCreateCellValue(header, value);
}

export function applyPasteData(table: PasteTable, selection: PasteSelection, text: string, options?: PasteOptions) {
  if (!text || !selection.length) {
    return;
  }

  const selectionSize = getPasteSelectionSize(selection);
  const parsed = parsePasteData(text, selectionSize, options?.internalClipboardGrid);
  if (parsed.type === 'cell') {
    const firstCell = selection[0]?.[0];
    if (!firstCell) {
      return;
    }
    const { row, col } = normalizePasteTargetCell(firstCell);
    if (!isInsideTable(table, row, col) || isReadOnlyPasteTarget(table, options, row, col)) {
      return;
    }
    table.changeCellValue(col, row, normalizeCreateRowPasteCell(table, options, row, col, parsed.value));
    return;
  }

  if (selectionSize === 1) {
    const firstCell = selection[0]?.[0];
    if (!firstCell) {
      return;
    }
    const anchor = normalizePasteTargetCell(firstCell);
    parsed.rows.forEach((sourceRow, rowOffset) => {
      sourceRow.forEach((value, colOffset) => {
        const row = anchor.row + rowOffset;
        const col = anchor.col + colOffset;
        if (!isInsideTable(table, row, col) || isReadOnlyPasteTarget(table, options, row, col)) {
          return;
        }
        table.changeCellValue(col, row, normalizeCreateRowPasteCell(table, options, row, col, value));
      });
    });
    return;
  }

  for (let i = 0; i < selection.length; i++) {
    const selectedRow = selection[i];
    for (let j = 0; j < selectedRow.length; j++) {
      const { row, col } = normalizePasteTargetCell(selectedRow[j]);
      if (!isInsideTable(table, row, col) || isReadOnlyPasteTarget(table, options, row, col)) {
        continue;
      }

      const pasteRowIndex = i % parsed.rows.length;
      const pasteColIndex = j % parsed.rows[pasteRowIndex].length;
      table.changeCellValue(
        col,
        row,
        normalizeCreateRowPasteCell(table, options, row, col, parsed.rows[pasteRowIndex][pasteColIndex]),
      );
    }
  }
}
