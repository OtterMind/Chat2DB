import { copyToClipboard } from '@/utils';
import { formatSelectionAsMarkdown } from './markdownTable';
import type { ITableInstance } from '@/blocks/CanvasTable/typings';

const handleCopyAsMarkdown = (tableInstance: ITableInstance) => {
  const columns = tableInstance.columns as any[];
  const markdown = formatSelectionAsMarkdown(tableInstance.getSelectedCellInfos() || [], (cell) => {
    const column = columns.find((item) => String(item.field) === String(cell.field));
    return column?.title ?? cell.field ?? '';
  });

  if (!markdown) {
    return false;
  }

  return copyToClipboard(markdown);
};

export default handleCopyAsMarkdown;
