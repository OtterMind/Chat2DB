import isEqual from 'lodash/isEqual';

export async function resolvePendingResultEditOperations<T>(params: {
  completeActiveEditor: () => Promise<void>;
  getOperations: () => T[] | undefined;
}) {
  await params.completeActiveEditor();
  return params.getOperations() || [];
}

interface ResultEditorTable {
  editorManager?: {
    editingEditor?: {
      getValue?: () => unknown;
    };
    editCell?: {
      col: number;
      row: number;
    };
  };
  getCellOriginValue: (col: number, row: number) => unknown;
}

export function hasActiveResultEditorChange(tableInstance: ResultEditorTable | null | undefined) {
  const editorManager = tableInstance?.editorManager;
  const editor = editorManager?.editingEditor;
  const editCell = editorManager?.editCell;
  if (!tableInstance || !editor?.getValue || !editCell) {
    return false;
  }
  const currentValue = editor.getValue();
  if (currentValue === undefined) {
    return false;
  }
  return !isEqual(currentValue, tableInstance.getCellOriginValue(editCell.col, editCell.row));
}

export function hasPendingResultEdit(hasOperationRecord: boolean, hasActiveEditorChange: boolean) {
  return hasOperationRecord || hasActiveEditorChange;
}
