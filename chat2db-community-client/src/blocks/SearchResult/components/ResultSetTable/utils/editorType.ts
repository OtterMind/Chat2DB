import type { IResultSetEditorOption } from '@/typings/database';
import { SelectEditor, type SelectEditorTheme } from '@/blocks/CanvasTable/editor/SelectIEditor';
import { MultiSelectEditor } from '@/blocks/CanvasTable/editor/MultiSelectIEditor';

const RESULT_SET_EDITOR_MAP: Record<string, string> = {
  DATE: 'custom-date-editor',
  TIME: 'custom-time-editor',
  DATETIME: 'custom-datetime-editor',
  TIMESTAMP: 'custom-timestamp-editor',
};

export const resolveResultSetEditor = (
  editorType?: string,
  editorOptions?: readonly IResultSetEditorOption[],
  theme: SelectEditorTheme = {},
) => {
  if (editorType === 'SELECT' && editorOptions?.length) {
    const editor = new SelectEditor(editorOptions, theme);
    if (editor.options.length) {
      return editor;
    }
  }
  if (editorType === 'MULTI_SELECT' && editorOptions?.length) {
    const editor = new MultiSelectEditor(editorOptions, theme);
    if (editor.options.length && editor.options.every((option) => option.value.length > 0 && !option.value.includes(','))) {
      return editor;
    }
  }
  return editorType ? RESULT_SET_EDITOR_MAP[editorType] || 'custom-input-editor' : 'custom-input-editor';
};
