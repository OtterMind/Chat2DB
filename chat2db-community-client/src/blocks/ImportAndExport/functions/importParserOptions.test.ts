import assert from 'node:assert/strict';
import {
  DEFAULT_IMPORT_OPTIONS,
  firstDataRowIndex,
  getImportValidationIssues,
  importOptionsKey,
  isPreviewCurrent,
} from './importParserOptions';

const issueCodes = (input: Parameters<typeof getImportValidationIssues>[0]) =>
  getImportValidationIssues(input).map((issue) => issue.code);

const baseInput = {
  duplicateHeaders: [],
  mapping: { id: 'id' },
  blockedColumns: [],
  options: DEFAULT_IMPORT_OPTIONS,
  skipValue: '__skip__',
};

assert.equal(firstDataRowIndex({ ...DEFAULT_IMPORT_OPTIONS, startRow: 3, headerRow: 2 }), 3);
assert.equal(firstDataRowIndex({ ...DEFAULT_IMPORT_OPTIONS, hasHeader: false, startRow: 3, headerRow: 0 }), 3);

assert.deepEqual(
  issueCodes({
    ...baseInput,
    duplicateHeaders: ['id'],
  }),
  ['duplicateHeaders'],
);

assert.deepEqual(
  issueCodes({
    ...baseInput,
    mapping: { id: 'id', order_id: 'id' },
  }),
  ['duplicateTargetMapping'],
);

assert.deepEqual(
  issueCodes({
    ...baseInput,
    options: { ...DEFAULT_IMPORT_OPTIONS, startRow: 3, headerRow: 2, endRow: 3 },
  }),
  ['invalidRange'],
);

assert.deepEqual(
  issueCodes({
    ...baseInput,
    mapping: { id: '__skip__' },
    blockedColumns: ['name'],
  }),
  ['noMappedColumns', 'requiredUnmapped'],
);

assert.equal(
  importOptionsKey({
    ...DEFAULT_IMPORT_OPTIONS,
    sheetName: 'Orders',
    startRow: 2,
  }),
  importOptionsKey({
    ...DEFAULT_IMPORT_OPTIONS,
    startRow: 2,
    sheetName: 'Orders',
  }),
);

const previewKey = importOptionsKey({ ...DEFAULT_IMPORT_OPTIONS, delimiter: ',' });
assert.equal(isPreviewCurrent(previewKey, { ...DEFAULT_IMPORT_OPTIONS, delimiter: ',' }), true);
assert.equal(isPreviewCurrent(previewKey, { ...DEFAULT_IMPORT_OPTIONS, delimiter: ';' }), false);
assert.equal(isPreviewCurrent(undefined, DEFAULT_IMPORT_OPTIONS), false);

console.log('Import parser option tests passed');
