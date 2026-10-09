import type { ITableInstance } from '@/blocks/CanvasTable/typings';
import type { ITableHeaderItem } from '@/typings/database';
import type { ITableOperationUtils } from './typings';
import i18n from '@/i18n';
import sqlService from '@/service/sql';
import { copyToClipboardAsync } from '@/utils';
import feedback from '@/utils/feedback';
import { setInternalResultGridClipboard } from '@/utils/internalClipboard';
import { getLargeCellErrorMessage } from '../ViewData/largeCellValueMessage';
import { LARGE_CELL_ERROR_MESSAGE } from '../ViewData/largeCellValue';
import { formatSelectionAsMarkdown } from './event/onContextmenuCell/markdownTable';
import {
  buildResultCopyOperations,
  completeResultCopyValues,
  createCopyAbort,
  type CopyAbort,
  getResultCopyFields,
  resultCopySelectionToRows,
  snapshotResultCopySelection,
  type ResultCopyFormat,
} from './copyValues';

interface CopyColumn {
  field: string | number;
  hide?: boolean;
  title?: string;
  originalData?: ITableHeaderItem;
}

let activeCopy: { table: ITableInstance; abort: CopyAbort } | undefined;

export function cancelResultCopy(table?: ITableInstance) {
  if (!table || activeCopy?.table === table) activeCopy?.abort.abort();
}

export async function copyResultData(
  table: ITableInstance,
  format: ResultCopyFormat = 'cells',
  options?: { headers: ITableHeaderItem[]; operations: ITableOperationUtils },
): Promise<boolean> {
  activeCopy?.abort.abort();
  const abort = createCopyAbort();
  activeCopy = { table, abort };
  let closeLoading: (() => void) | undefined;
  const showLoading = () => {
    closeLoading ??= feedback.loading(i18n('common.largeCellValue.status.copyLoading'), 0);
  };
  const closeMessage = () => closeLoading?.();
  abort.onAbort(closeMessage);
  try {
    const selection = snapshotResultCopySelection(table.getSelectedCellInfos() || []);
    const columns = (table.columns as CopyColumn[]).map((column) => ({ ...column }));
    const visibleColumns = columns.filter((column) => column.hide !== true);
    const visibleFields = visibleColumns.map((column) => String(column.field));
    const headers = options?.headers || [];
    if (!selection.flat().length) return false;
    // Validate the IN selection before requesting any large values.
    if (format === 'IN_VALUES') buildResultCopyOperations(selection, format);
    const fields = selection.map((row) => getResultCopyFields(row, format, visibleFields, headers));
    await completeResultCopyValues(
      selection,
      fields,
      (params, requestSignal) => {
        const request = { ...params, errorLevel: false as const };
        return sqlService.getLargeCellValue(request, { signal: requestSignal });
      },
      abort,
      showLoading,
    );
    abort.throwIfAborted();
    let data: string | string[][];
    let gridRows: string[][] | undefined;
    if (format === 'CREATE' || format === 'UPDATE_COPY' || format === 'WHERE' || format === 'IN_VALUES') {
      const operations = buildResultCopyOperations(selection, format);
      if (!options) return false;
      data =
        format === 'IN_VALUES'
          ? await options.operations.generateCopyInValues(operations, abort.signal)
          : await options.operations.generateCopySQL(operations, abort.signal);
    } else if (format === 'markdown') {
      data =
        formatSelectionAsMarkdown(selection, (cell) => {
          const column = columns.find((item) => String(item.field) === String(cell.field));
          return column?.originalData?.name || column?.title || cell.field;
        }) || '';
    } else if (format === 'headers') {
      data = [visibleColumns.map((column) => column.originalData?.name || column.title || '')];
    } else if (format === 'tsv' || format === 'tsvWithHeaders') {
      gridRows = selection
        .filter((row) => row.some((cell) => cell.row > 0))
        .map((row) => {
          const record = row.find((cell) => cell.originData)?.originData;
          return visibleFields.map((field) => String(record?.[field] ?? ''));
        });
      if (format === 'tsvWithHeaders') {
        gridRows.unshift(visibleColumns.map((column) => column.originalData?.name || column.title || ''));
      }
      data = gridRows;
    } else {
      gridRows = resultCopySelectionToRows(selection);
      data = gridRows;
    }
    abort.throwIfAborted();
    if (!(await copyToClipboardAsync(data))) throw new Error('common.sqlInValues.copyFailed');
    abort.throwIfAborted();
    if (gridRows) setInternalResultGridClipboard(gridRows);
    feedback.success(i18n('common.button.copySuccessfully'));
    return true;
  } catch (error: unknown) {
    if (!abort.isAborted()) {
      const message = error instanceof Error ? error.message : '';
      const knownErrors = [
        'common.sqlInValues.emptySelection',
        'common.sqlInValues.singleColumnRequired',
        'common.sqlInValues.copyFailed',
      ] as const;
      const errorKey = knownErrors.find((key) => key === message);
      feedback.warning(
        errorKey
          ? i18n(errorKey)
          : getLargeCellErrorMessage(error, LARGE_CELL_ERROR_MESSAGE.LOAD_FAILED),
      );
    }
    return false;
  } finally {
    closeMessage();
    abort.offAbort(closeMessage);
    if (activeCopy?.abort === abort) activeCopy = undefined;
  }
}
