export interface IMarkdownSelectionCell {
  col: number;
  row: number;
  field?: unknown;
  value?: unknown;
  dataValue?: unknown;
}

const escapeMarkdownCell = (value: unknown) => {
  if (value === null || value === undefined) {
    return 'NULL';
  }

  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\r\n|\r|\n/g, '<br>')
    .replace(/\\/g, '\\\\')
    .replace(/\|/g, '\\|');
};

export const formatSelectionAsMarkdown = (
  selectedCells: IMarkdownSelectionCell[][],
  getColumnTitle: (cell: IMarkdownSelectionCell) => unknown = (cell) => cell.field,
) => {
  const cells = selectedCells.flat().filter((cell) => cell.row > 0 && cell.col > 0);
  if (!cells.length) {
    return null;
  }

  const columns = [...new Set(cells.map((cell) => cell.col))].sort((left, right) => left - right);
  const rows = [...new Set(cells.map((cell) => cell.row))].sort((left, right) => left - right);
  const cellByPosition = new Map(cells.map((cell) => [`${cell.row}:${cell.col}`, cell]));

  const header = columns.map((col) => {
    const cell = cells.find((item) => item.col === col);
    return escapeMarkdownCell(cell ? getColumnTitle(cell) : '');
  });
  const separator = columns.map(() => '---');
  const body = rows.map((row) =>
    columns.map((col) => {
      const cell = cellByPosition.get(`${row}:${col}`);
      if (!cell) {
        return '';
      }
      return escapeMarkdownCell(cell.dataValue !== undefined ? cell.dataValue : cell.value);
    }),
  );

  return [header, separator, ...body].map((line) => `| ${line.join(' | ')} |`).join('\n');
};
