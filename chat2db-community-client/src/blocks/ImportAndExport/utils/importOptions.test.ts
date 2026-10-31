import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildImportPreviewKey,
  DEFAULT_EXCEL_OPTIONS,
  DEFAULT_JSON_OPTIONS,
  DEFAULT_SQL_IMPORT_OPTIONS,
} from './importOptions';
import { DEFAULT_CSV_OPTIONS } from './csvOptions';

// These defaults are the contract the backend applies when a client omits the options, so both
// sides must move together: ExcelOptions/JsonOptions/SqlImportOptions in the domain API.
assert.deepEqual(DEFAULT_EXCEL_OPTIONS, {
  dateOrder: 'YMD',
  dateTimeOrder: 'DATE_TIME',
  dateDelimiter: '-',
  yearDelimiter: '-',
  timeDelimiter: ':',
  decimalSymbol: '.',
  hasHeader: true,
  headerRow: 1,
  dataStartRow: 2,
  sheetIndex: 0,
  columnRange: '',
  emptyAsNull: true,
});

assert.deepEqual(DEFAULT_JSON_OPTIONS, {
  dateOrder: 'YMD',
  dateTimeOrder: 'DATE_TIME',
  dateDelimiter: '-',
  yearDelimiter: '-',
  timeDelimiter: ':',
  decimalSymbol: '.',
  encoding: 'UTF-8',
  structure: 'ARRAY',
  dataPath: '$',
  emptyAsNull: false,
});

assert.deepEqual(DEFAULT_SQL_IMPORT_OPTIONS, { encoding: 'AUTO' });

console.log('import option defaults match the backend defaults');

const source = {
  dataSourceId: 1,
  databaseName: 'app',
  schemaName: 'public',
  tableName: 'orders',
  fileId: 'file-1',
};

for (const [format, field, options] of [
  ['CSV', 'csvOptions', DEFAULT_CSV_OPTIONS],
  ['Excel', 'excelOptions', DEFAULT_EXCEL_OPTIONS],
  ['JSON', 'jsonOptions', DEFAULT_JSON_OPTIONS],
] as const) {
  test(`${format}: changing empty-cell policy preserves the preview without mutating submit options`, () => {
    const original = Object.freeze({ ...options });
    const key = buildImportPreviewKey({ ...source, [field]: original });
    assert.equal(buildImportPreviewKey({
      ...source, [field]: { ...original, emptyAsNull: !original.emptyAsNull },
    }), key);
    assert.equal(original.emptyAsNull, options.emptyAsNull);
    assert.equal(key.includes('emptyAsNull'), false);
  });
}

test('file and every target identity still invalidate a loaded preview', () => {
  const params = { ...source, csvOptions: DEFAULT_CSV_OPTIONS };
  const key = buildImportPreviewKey(params);
  for (const change of [
    { dataSourceId: 2 }, { databaseName: 'other' }, { schemaName: 'other' },
    { tableName: 'other' }, { fileId: 'file-2' },
  ]) {
    assert.notEqual(buildImportPreviewKey({ ...params, ...change }), key);
  }
});

test('reading configuration changes still require a new preview', () => {
  for (const change of [{ encoding: 'GBK' }, { delimiter: ';' }, { hasHeader: false }, { dataStartRow: 3 }]) {
    assert.notEqual(buildImportPreviewKey({
      ...source, csvOptions: { ...DEFAULT_CSV_OPTIONS, ...change },
    }), buildImportPreviewKey({ ...source, csvOptions: DEFAULT_CSV_OPTIONS }));
  }
  assert.notEqual(buildImportPreviewKey({
    ...source, excelOptions: { ...DEFAULT_EXCEL_OPTIONS, sheetIndex: 1 },
  }), buildImportPreviewKey({ ...source, excelOptions: DEFAULT_EXCEL_OPTIONS }));
  assert.notEqual(buildImportPreviewKey({
    ...source, jsonOptions: { ...DEFAULT_JSON_OPTIONS, dataPath: '$.records' },
  }), buildImportPreviewKey({ ...source, jsonOptions: DEFAULT_JSON_OPTIONS }));
});
