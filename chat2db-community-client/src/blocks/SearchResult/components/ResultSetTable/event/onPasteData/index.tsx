import * as VTable from '@visactor/vtable';
import { readClipboard } from '@/utils/clipboard';
import { getInternalResultGridClipboard } from '@/utils/internalClipboard';
import { applyPasteData } from './pasteData';
import type { OperationRecordUtils } from '../../hooks/useOperationRecord';

const onPasteData = (
  tableInstance: VTable.ListTable,
  operationRecordUtils?: Pick<OperationRecordUtils, 'isCreateRow'>,
  readOnlyFields?: ReadonlySet<string>,
) => {
  // Gets the currently selected cell information
  const selectedCells = tableInstance.getSelectedCellInfos();
  if (!selectedCells || selectedCells.length === 0) return;

  // Get the pasted text content
  readClipboard().then((text) => {
    if (!text) return;
    applyPasteData(tableInstance, selectedCells, text, {
      isCreateRow: operationRecordUtils?.isCreateRow,
      internalClipboardGrid: getInternalResultGridClipboard(text) ?? undefined,
      readOnlyFields,
    });
  });
};

export default onPasteData;
