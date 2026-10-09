import type { ILargeCellChunk, ILargeCellValueRequest, IResultCell, ITableHeaderItem } from '@/typings/database';
import {
  decodeLargeCellChunk,
  getLargeCellViewerValue,
  isBinaryDisplayMode,
  LARGE_CELL_MAX_CHUNK_SIZE,
} from '../ViewData/largeCellValue';

export interface ResultCopyRecord {
  [field: string]: unknown;
  CHAT2DB_ROW_NUMBER?: string | number;
  __CHAT2DB_CELL_META__?: IResultCell[];
}

export interface ResultCopyCell {
  row: number;
  col: number;
  field?: unknown;
  value?: unknown;
  dataValue?: unknown;
  originData?: ResultCopyRecord;
}

export type ResultCopySelection = ResultCopyCell[][];
export type ResultCopyFormat =
  | 'cells'
  | 'CREATE'
  | 'UPDATE_COPY'
  | 'WHERE'
  | 'IN_VALUES'
  | 'tsv'
  | 'headers'
  | 'tsvWithHeaders'
  | 'markdown';
export interface ResultCopyOperation {
  type: 'CREATE' | 'UPDATE_COPY' | 'WHERE' | 'IN_VALUES';
  rowId?: string | number;
  dataList: ResultCopyRecord;
  selectCols: string[];
  selectedCell?: IResultCell;
}

// Browser requests accept an AbortSignal; the desktop (JCEF) bridge accepts a callback that
// receives { id, reject }. The copy flow therefore passes this shape through to the service layer
// unchanged and wraps it in one abort handle that behaves the same in both runtimes.
export type CopyRequestSignal =
  | AbortSignal
  | ((params: { id: string; reject: (reason?: unknown) => void }) => void);

export interface CopyAbort {
  signal: CopyRequestSignal;
  isAborted(): boolean;
  onAbort(listener: () => void): void;
  offAbort(listener: () => void): void;
  throwIfAborted(): void;
  abort(): void;
}

export function createCopyAbort(): CopyAbort {
  if (typeof window !== 'undefined' && window.javaQuery !== undefined) {
    const pending = new Map<string, (reason?: unknown) => void>();
    const listeners = new Set<() => void>();
    let aborted = false;
    const cancelPending = () =>
      pending.forEach((reject) => reject({ message: 'signal is aborted without reason' }));
    return {
      signal: ({ id, reject }) => {
        if (aborted) {
          reject({ message: 'signal is aborted without reason' });
          return;
        }
        pending.set(id, reject);
      },
      isAborted: () => aborted,
      onAbort: (listener) => {
        listeners.add(listener);
      },
      offAbort: (listener) => {
        listeners.delete(listener);
      },
      throwIfAborted: () => {
        if (aborted) throw new DOMException('The operation was aborted.', 'AbortError');
      },
      abort: () => {
        if (aborted) return;
        aborted = true;
        cancelPending();
        pending.clear();
        listeners.forEach((listener) => listener());
      },
    };
  }
  const controller = new AbortController();
  const listeners = new Set<() => void>();
  controller.signal.addEventListener('abort', () => listeners.forEach((listener) => listener()));
  return {
    signal: controller.signal,
    isAborted: () => controller.signal.aborted,
    onAbort: (listener) => {
      listeners.add(listener);
    },
    offAbort: (listener) => {
      listeners.delete(listener);
    },
    throwIfAborted: () => controller.signal.throwIfAborted(),
    abort: () => controller.abort(),
  };
}

export type ReadCopyChunk = (
  params: ILargeCellValueRequest,
  signal: CopyRequestSignal,
) => Promise<ILargeCellChunk>;

export function snapshotResultCopySelection(selection: ResultCopySelection): ResultCopySelection {
  const records = new Map<ResultCopyRecord, ResultCopyRecord>();
  return selection.map((row) =>
    row.map((cell) => {
      const original = cell.originData;
      if (!original) return { ...cell };
      if (!records.has(original)) {
        records.set(original, {
          ...original,
          __CHAT2DB_CELL_META__: original.__CHAT2DB_CELL_META__?.map((meta) => ({ ...meta })),
        });
      }
      return { ...cell, originData: records.get(original) };
    }),
  );
}

export function getResultCopyFields(
  row: ResultCopyCell[],
  format: ResultCopyFormat,
  visibleFields: string[],
  headers: ITableHeaderItem[],
): string[] {
  if (format === 'headers') return [];
  if (format === 'tsv' || format === 'tsvWithHeaders') return visibleFields;
  const selected = row.filter((cell) => cell.row > 0 && cell.col > 0).map((cell) => String(cell.field));
  if (format !== 'UPDATE_COPY') return selected;
  const keyFields = headers.flatMap((header, index) => (index > 0 && header.primaryKey ? [String(index)] : []));
  const locatorFields = keyFields.length ? keyFields : headers.slice(1).map((_, index) => String(index + 1));
  return [...new Set([...selected, ...locatorFields])];
}

export async function readCompleteCopyValue(meta: IResultCell, readChunk: ReadCopyChunk, abort: CopyAbort) {
  if (!meta.largeValueId) throw new Error('largeCellValue.fullValueUnsupported');
  const decoder = new TextDecoder('utf-8', { fatal: true });
  const parts: string[] = [];
  let offset = 0;
  let binary = isBinaryDisplayMode(meta.valueType);
  let eof = false;
  while (!eof) {
    abort.throwIfAborted();
    const chunk = await readChunk(
      {
        largeValueId: meta.largeValueId,
        offset,
        limit: LARGE_CELL_MAX_CHUNK_SIZE - (LARGE_CELL_MAX_CHUNK_SIZE % 3),
        format: 'base64',
      },
      abort.signal,
    );
    abort.throwIfAborted();
    const loaded = decodeLargeCellChunk(chunk);
    if (
      chunk.offset !== offset ||
      chunk.nextOffset !== offset + loaded.bytes.length ||
      (!chunk.eof && !loaded.bytes.length)
    ) {
      throw new Error('largeCellValue.readFailed');
    }
    binary = isBinaryDisplayMode(chunk.displayMode || meta.valueType);
    parts.push(
      binary
        ? getLargeCellViewerValue({ viewerMode: 'hex', chunks: [loaded] })
        : decoder.decode(loaded.bytes, { stream: !chunk.eof }),
    );
    offset = chunk.nextOffset;
    eof = chunk.eof;
  }
  return { value: `${binary ? '0x' : ''}${parts.join('')}`, sizeBytes: offset };
}

export async function completeResultCopyValues(
  selection: ResultCopySelection,
  fields: string[][],
  readChunk: ReadCopyChunk,
  abort: CopyAbort,
  onLoad: () => void = () => undefined,
) {
  const values = new Map<string, Awaited<ReturnType<typeof readCompleteCopyValue>>>();
  for (let rowIndex = 0; rowIndex < selection.length; rowIndex += 1) {
    const record = selection[rowIndex].find((cell) => cell.row > 0 && cell.originData)?.originData;
    if (!record) continue;
    for (const field of fields[rowIndex]) {
      abort.throwIfAborted();
      const metadata = record.__CHAT2DB_CELL_META__;
      const meta = metadata?.[Number(field)];
      if (!meta || (!meta.largeValue && !meta.truncated && !meta.largeValueId)) continue;
      if (!meta.largeValueId) throw new Error('largeCellValue.fullValueUnsupported');
      let full = values.get(meta.largeValueId);
      if (!full) {
        onLoad();
        full = await readCompleteCopyValue(meta, readChunk, abort);
        values.set(meta.largeValueId, full);
      }
      record[field] = full.value;
      metadata[Number(field)] = {
        ...meta,
        value: full.value,
        largeValue: false,
        truncated: false,
        largeValueId: undefined,
        unsupportedReason: undefined,
        loadedBytes: full.sizeBytes,
        loadedChars: full.value.length,
      };
    }
  }
  selection.forEach((row) =>
    row.forEach((cell) => {
      if (cell.row > 0 && cell.col > 0 && cell.originData && cell.field !== undefined) {
        cell.value = cell.originData[String(cell.field)];
        cell.dataValue = cell.value;
      }
    }),
  );
}

export function buildResultCopyOperations(
  selection: ResultCopySelection,
  type: ResultCopyOperation['type'],
): ResultCopyOperation[] {
  let inField: string | undefined;
  return selection
    .filter((row) => row.some((cell) => cell.row > 0))
    .map((row) => {
      const cells = row.filter((cell) => cell.row > 0 && cell.col > 0);
      const record = cells[0]?.originData;
      if (!record) throw new Error('common.sqlInValues.emptySelection');
      const selectCols = cells.map((cell) => String(cell.field));
      if (type === 'IN_VALUES') {
        if (selectCols.length !== 1 || (inField !== undefined && inField !== selectCols[0])) {
          throw new Error('common.sqlInValues.singleColumnRequired');
        }
        [inField] = selectCols;
      }
      return {
        type,
        rowId: record.CHAT2DB_ROW_NUMBER,
        dataList: record,
        selectCols,
        ...(type === 'IN_VALUES' ? { selectedCell: record.__CHAT2DB_CELL_META__?.[Number(inField)] } : {}),
      };
    });
}

export function resultCopySelectionToRows(selection: ResultCopySelection): string[][] {
  const cells = selection.flat();
  if (!cells.length) return [];
  let minRow = Infinity;
  let minCol = Infinity;
  let maxRow = -1;
  let maxCol = -1;
  cells.forEach((cell) => {
    minRow = Math.min(minRow, cell.row);
    minCol = Math.min(minCol, cell.col);
    maxRow = Math.max(maxRow, cell.row);
    maxCol = Math.max(maxCol, cell.col);
  });
  const rows: string[][] = Array.from({ length: maxRow - minRow + 1 }, () => Array(maxCol - minCol + 1).fill(''));
  cells.forEach((cell) => {
    rows[cell.row - minRow][cell.col - minCol] =
      cell.value === null || cell.value === undefined ? '' : cell.value === '' ? "''" : String(cell.value);
  });
  return rows;
}
