import { applyPasteData, parsePasteData, PasteSelection, PasteTable } from './pasteData';
import { GENERATED_VALUE } from '@/blocks/SearchResult/utils';

function assertEqual(actual: any, expected: any, message: string) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}

function createTable(rowCount = 10, colCount = 10) {
  const calls: Array<{ col: number; row: number; value: string | null }> = [];
  const table: PasteTable = {
    rowCount,
    colCount,
    changeCellValue: (col, row, value) => {
      calls.push({ row, col, value });
    },
    getHeaderField: (col) => String(col),
  };
  return { table, calls };
}

{
  const { table, calls } = createTable();
  const selection: PasteSelection = [[{ row: 2, col: 2 }]];

  applyPasteData(table, selection, 'locked', { readOnlyFields: new Set(['2']) });

  assertEqual(calls, [], 'single-cell paste does not modify a frozen field');
}

{
  const { table, calls } = createTable();
  const selection: PasteSelection = [[{ row: 2, col: 1 }]];

  applyPasteData(table, selection, 'a\tb\tc', {
    internalClipboardGrid: [['a', 'b', 'c']],
    readOnlyFields: new Set(['2']),
  });

  assertEqual(
    calls,
    [
      { row: 2, col: 1, value: 'a' },
      { row: 2, col: 3, value: 'c' },
    ],
    'anchor grid paste skips a frozen field and keeps writable targets',
  );
}

{
  const { table, calls } = createTable();
  const selection: PasteSelection = [
    [
      { row: 1, col: 1 },
      { row: 1, col: 2 },
    ],
    [
      { row: 2, col: 1 },
      { row: 2, col: 2 },
    ],
  ];

  applyPasteData(table, selection, 'a\tb\nc\td', { readOnlyFields: new Set(['2']) });

  assertEqual(
    calls,
    [
      { row: 1, col: 1, value: 'a' },
      { row: 2, col: 1, value: 'c' },
    ],
    'multi-cell paste skips every frozen-field target',
  );
}

const multiLineSql = 'select \n  * \nfrom \n  ai_chat_message;';
const multiLineSqlWithTabIndent = 'select\n\t*\nfrom\n\tai_chat_message;';

assertEqual(
  parsePasteData(multiLineSql, 1),
  {
    type: 'cell',
    value: multiLineSql,
  },
  'single selected cell keeps plain multiline text as one cell value',
);

assertEqual(
  parsePasteData("''", 1),
  {
    type: 'cell',
    value: '',
  },
  "single selected cell keeps legacy empty-string sentinel",
);

assertEqual(
  parsePasteData(multiLineSqlWithTabIndent, 1),
  {
    type: 'cell',
    value: multiLineSqlWithTabIndent,
  },
  'single selected cell keeps tab-indented multiline text as one cell value',
);

assertEqual(
  parsePasteData(multiLineSqlWithTabIndent, 1, [[multiLineSqlWithTabIndent]]),
  {
    type: 'cell',
    value: multiLineSqlWithTabIndent,
  },
  'internal single-cell copy keeps tab-indented multiline text as one cell value',
);

assertEqual(
  parsePasteData(' \n ', 1),
  {
    type: 'cell',
    value: null,
  },
  'single selected cell keeps legacy blank-to-null behavior',
);

assertEqual(
  parsePasteData('a\tb\r\nc\td', 4),
  {
    type: 'grid',
    rows: [
      ['a', 'b'],
      ['c', 'd'],
    ],
  },
  'multi-cell selection parses CRLF TSV as grid data',
);

assertEqual(
  parsePasteData('1\t2\t5', 1),
  {
    type: 'cell',
    value: '1\t2\t5',
  },
  'external TSV remains one value when only one destination cell is selected',
);

assertEqual(
  parsePasteData('1\t2\t5', 1, [['1', '2', '5']]),
  {
    type: 'grid',
    rows: [['1', '2', '5']],
  },
  'internal result-grid metadata identifies a multi-cell clipboard from one destination cell',
);

{
  const { table, calls } = createTable();
  const selection: PasteSelection = [[{ row: 2, col: 3 }]];

  applyPasteData(table, selection, multiLineSql);

  assertEqual(
    calls,
    [{ row: 2, col: 3, value: multiLineSql }],
    'single selected body cell writes the complete multiline value once',
  );
}

{
  const { table, calls } = createTable();
  const selection: PasteSelection = [[{ row: 7, col: 2 }]];

  applyPasteData(table, selection, '1\t2\t5', {
    internalClipboardGrid: [['1', '2', '5']],
  });

  assertEqual(
    calls,
    [
      { row: 7, col: 2, value: '1' },
      { row: 7, col: 3, value: '2' },
      { row: 7, col: 4, value: '5' },
    ],
    'internal one-row grid paste expands right from one destination cell',
  );
}

{
  const { table, calls } = createTable(10, 4);
  const selection: PasteSelection = [[{ row: 9, col: 3 }]];

  applyPasteData(table, selection, '1\t2\t5\n2\t1\t5', {
    internalClipboardGrid: [
      ['1', '2', '5'],
      ['2', '1', '5'],
    ],
  });

  assertEqual(
    calls,
    [
      { row: 9, col: 3, value: '1' },
      { row: 9, col: 4, value: '2' },
      { row: 10, col: 3, value: '2' },
      { row: 10, col: 4, value: '1' },
    ],
    'internal anchor paste expands right and down while clipping table overflow',
  );
}

{
  const { table, calls } = createTable();
  const selection: PasteSelection = [[{ row: 0, col: 0 }]];

  applyPasteData(table, selection, multiLineSql);

  assertEqual(
    calls,
    [{ row: 1, col: 1, value: multiLineSql }],
    'single selected header or row-number cell redirects to the first body cell',
  );
}

{
  const { table, calls } = createTable();
  const selection: PasteSelection = [
    [
      { row: 1, col: 1 },
      { row: 1, col: 2 },
    ],
    [
      { row: 2, col: 1 },
      { row: 2, col: 2 },
    ],
  ];

  applyPasteData(table, selection, 'a\tb\nc\td');

  assertEqual(
    calls,
    [
      { row: 1, col: 1, value: 'a' },
      { row: 1, col: 2, value: 'b' },
      { row: 2, col: 1, value: 'c' },
      { row: 2, col: 2, value: 'd' },
    ],
    'multi-cell selection keeps TSV grid paste behavior',
  );
}

{
  const { table, calls } = createTable();
  table.columns = [
    { originalData: { name: 'id', autoIncrement: 1 } as any },
    { originalData: { name: 'name' } as any },
  ];
  table.getRecordByCell = () => ({ CHAT2DB_ROW_NUMBER: 'new-row' });

  const selection: PasteSelection = [
    [
      { row: 3, col: 1 },
      { row: 3, col: 2 },
    ],
  ];

  applyPasteData(table, selection, '42\tAlice', {
    isCreateRow: (rowId) => rowId === 'new-row',
  });

  assertEqual(
    calls,
    [
      { row: 3, col: 1, value: GENERATED_VALUE },
      { row: 3, col: 2, value: 'Alice' },
    ],
    'multi-cell paste keeps generated sentinel for auto-increment columns on create rows',
  );
}

{
  const { table, calls } = createTable();
  table.columns = [{ originalData: { name: 'id', autoIncrement: 1 } as any }];
  table.getRecordByCell = () => ({ CHAT2DB_ROW_NUMBER: 'existing-row' });

  const selection: PasteSelection = [[{ row: 3, col: 1 }]];

  applyPasteData(table, selection, '42', {
    isCreateRow: (rowId) => rowId === 'new-row',
  });

  assertEqual(
    calls,
    [{ row: 3, col: 1, value: '42' }],
    'single-cell paste preserves auto-increment value on existing rows',
  );
}

console.log('onPasteData paste helper tests passed');
