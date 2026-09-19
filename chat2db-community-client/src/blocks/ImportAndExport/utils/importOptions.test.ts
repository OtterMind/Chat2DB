import assert from 'node:assert/strict';
import {
  DEFAULT_EXCEL_OPTIONS,
  DEFAULT_JSON_OPTIONS,
  DEFAULT_SQL_IMPORT_OPTIONS,
} from './importOptions';

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
