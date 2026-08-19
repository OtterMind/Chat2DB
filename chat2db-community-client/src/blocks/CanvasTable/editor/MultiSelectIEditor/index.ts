import type { IResultSetEditorOption } from '@/typings/database';
import { SelectEditor, type SelectEditorTheme } from '../SelectIEditor';

export class MultiSelectEditor extends SelectEditor {
  constructor(options: readonly IResultSetEditorOption[], theme: SelectEditorTheme) {
    super(options, theme, true);
  }
}
